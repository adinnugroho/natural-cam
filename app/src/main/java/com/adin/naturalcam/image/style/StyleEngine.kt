package com.adin.naturalcam.image.style

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StyleState
import kotlin.math.abs
import kotlin.math.exp

/**
 * Style engine (STYLE_PLAN 16): parameter-based transforms downstream of the
 * NATURAL base tone, upstream of gamut mapping. Center pads and strength 0
 * change nothing; strength 1 uses the bounded styled values (STYLE_PLAN 47).
 */
object StyleEngine {

    /** Resolved, bounded parameters (STYLE_PLAN 17). All deltas are zero at pad center. */
    data class ResolvedStyle(
        val toneContrast: Float,
        val midtoneLift: Float,
        val shoulderStrength: Float,
        val temperature: Float,
        val chromaScale: Float,
        val chromaCompression: Float,
        val paletteHueShift: Float,
        val paletteChromaBoost: Float,
        val shadowHueTint: Float,
    )

    fun resolve(style: StyleState): ResolvedStyle {
        val s = style.strength.coerceIn(0f, 1f)
        val (toneX, toneY) = shapeXY(style.tone)
        val (colorX, colorY) = shapeXY(style.color)
        val (paletteX, paletteY) = shapeXY(style.palette)
        return ResolvedStyle(
            // TONE: x = soft→hard contrast, y = lifted→deeper (STYLE_PLAN 8.2).
            toneContrast = 0.12f * toneX * s,
            midtoneLift = 0.08f * -toneY * s,
            shoulderStrength = 0.35f * toneX * s,
            // COLOR: x = cool→warm, y = muted→richer (STYLE_PLAN 9.2).
            temperature = 0.10f * colorX * s,
            chromaScale = 1f + 0.35f * colorY * s,
            chromaCompression = 0.55f * maxOf(0f, -colorY) * s,
            // PALETTE: x = green→rose hue rotation, y = gold→blue (STYLE_PLAN 10.2).
            paletteHueShift = paletteX * s,
            paletteChromaBoost = 0.18f * abs(paletteX) * s,
            // Palette undertone is deliberately small and chroma-weighted.
            shadowHueTint = 0.025f * paletteY * s,
        )
    }

    /** Nonlinear pad response: fine control near center, stronger at edges (STYLE_PLAN 18). */
    internal fun shape(value: Float): Float = value * abs(value)

    internal fun shapeXY(point: StylePoint) = Pair(shape(point.x), shape(point.y))

    fun apply(rgb: RgbImage, style: StyleState): RgbImage {
        if (style.strength <= 0f) return rgb
        val params = resolve(style)
        applyPalette(rgb, params)
        applyColor(rgb, params)
        applyTone(rgb, params)
        return rgb
    }

    /** Tone style via bounded tone-curve deltas (STYLE_PLAN 8.3); monotonic by construction. */
    private fun applyTone(rgb: RgbImage, params: ResolvedStyle) {
        val contrastK = 1f - params.toneContrast
        val lift = params.midtoneLift
        val shoulder = params.shoulderStrength
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                var r = rgb.r[i]
                var g = rgb.g[i]
                var b = rgb.b[i]
                // Midtone lift/deepen as a bounded luminance-dependent gain.
                if (lift > 0f && r < 0.35f) r *= 1f + lift * (1f - r / 0.35f)
                if (lift < 0f && r > 0.30f) r -= lift * (1f - (1f - r) / 0.70f)
                if (lift > 0f && g < 0.35f) g *= 1f + lift * (1f - g / 0.35f)
                if (lift < 0f && g > 0.30f) g -= lift * (1f - (1f - g) / 0.70f)
                if (lift > 0f && b < 0.35f) b *= 1f + lift * (1f - b / 0.35f)
                if (lift < 0f && b > 0.30f) b -= lift * (1f - (1f - b) / 0.70f)
                // Contrast around the 0.18 linear pivot (STYLE_PLAN 8.3).
                r = 0.18f + (r - 0.18f) * contrastK
                g = 0.18f + (g - 0.18f) * contrastK
                b = 0.18f + (b - 0.18f) * contrastK
                // Gentle shoulder when contrast is firmed (STYLE_PLAN 8.3).
                rgb.r[i] = if (shoulder > 0f && r > 0.7f) r - shoulder * (r - 0.7f) * 0.2f else r.coerceAtLeast(0f)
                rgb.g[i] = if (shoulder > 0f && g > 0.7f) g - shoulder * (g - 0.7f) * 0.2f else g.coerceAtLeast(0f)
                rgb.b[i] = if (shoulder > 0f && b > 0.7f) b - shoulder * (b - 0.7f) * 0.2f else b.coerceAtLeast(0f)
            }
        }
    }

    /** Bounded selective chroma with temperature; neutral grays preserved exactly (STYLE_PLAN 9.4). */
    private fun applyColor(rgb: RgbImage, params: ResolvedStyle) {
        if (params.temperature == 0f && params.chromaScale == 1f) return
        val scale = params.chromaScale
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                val r = rgb.r[i]
                val g = rgb.g[i]
                val b = rgb.b[i]
                val l = luminance(r, g, b)
                var cr = (r - l) * scale
                var cb = (b - l) * scale
                var cg = (g - l) * scale
                if (params.chromaCompression > 0f) {
                    val mag = maxOf(abs(cr), abs(cg), abs(cb))
                    val softKnee = 0.35f
                    if (mag > softKnee) {
                        val excess = (mag - softKnee) / 0.55f
                        val damp = 1f - params.chromaCompression * excess / (1f + excess * 0.7f)
                        cr *= damp
                        cb *= damp
                        cg *= damp
                    }
                }
                // Temperature waxes chroma toward warm/cool; zero-chroma pixels are untouched,
                // so gray grays remain exactly neutral (STYLE_PLAN 9.4).
                val chromaMag = cr * cr + cb * cb
                val chromaWeight = chromaMag / (chromaMag + 0.02f) // 0 at gray, →1 at saturated
                val warmShift = params.temperature * chromaWeight
                rgb.r[i] = l + cr * (1f + warmShift)
                rgb.g[i] = l + cg
                rgb.b[i] = l + cb * (1f - warmShift)
            }
        }
    }

    /** Hue-aware palette shifts: rose/green axis via chroma cross-feed, gold/blue via shadow tint (STYLE_PLAN 10.4, 11). */
    private fun applyPalette(rgb: RgbImage, params: ResolvedStyle) {
        if (params.paletteHueShift == 0f && params.paletteChromaBoost == 0f && params.shadowHueTint == 0f) return
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                val r = rgb.r[i]
                val b = rgb.b[i]
                val l = luminance(rgb.r[i], rgb.g[i], rgb.b[i])
                val cr = r - l
                val cb = b - l
                var newR = r + params.paletteHueShift * 0.20f * cb
                var newB = b - params.paletteHueShift * 0.20f * cr
                val chromaWeight = ((cr * cr + cb * cb) / (cr * cr + cb * cb + 0.02f)).coerceIn(0f, 1f)
                val shadowWeight = exp(-l * 2f) * 0.35f * chromaWeight
                newR += params.shadowHueTint * shadowWeight
                newB -= params.shadowHueTint * shadowWeight
                if (params.paletteChromaBoost > 0f) {
                    newR = l + (newR - l) * params.paletteChromaBoost
                    newB = l + (newB - l) * params.paletteChromaBoost
                }
                rgb.r[i] = newR.coerceAtLeast(0f)
                rgb.b[i] = newB.coerceAtLeast(0f)
            }
        }
    }
}

internal fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

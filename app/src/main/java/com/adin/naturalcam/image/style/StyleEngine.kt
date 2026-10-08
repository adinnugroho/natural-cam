package com.adin.naturalcam.image.style

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.SATURATION_CHROMA_DENOISE
import com.adin.naturalcam.image.core.SATURATION_RANGE
import com.adin.naturalcam.image.core.STYLE_CHROMA_DENOISE_STRENGTH
import com.adin.naturalcam.image.processing.BloomStage
import com.adin.naturalcam.image.processing.ChromaSmoother

import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.luminance
import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StylePresets
import com.adin.naturalcam.domain.StyleState
import kotlin.math.abs
import kotlin.math.exp

/**
 * Style engine (STYLE_PLAN 16): a strength-scaled denoise plus parameter-based
 * pad transforms, downstream of the NATURAL base tone and upstream of gamut
 * mapping. Pad deltas are zero at pad center; strength 0 is an exact NATURAL
 * identity; strength 1 uses the bounded styled values (STYLE_PLAN 12.3, 47).
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
            // Both signs are read straight from the pad: +x expands around the pivot
            // (firmer), +y lifts the shadows. The previous negation made every tone
            // pad do the opposite of its documented direction — a pad moved toward
            // "firm/deep" compressed contrast and brightened shadows instead.
            toneContrast = 0.12f * toneX * s,
            midtoneLift = 0.08f * toneY * s,
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

    /**
     * One-line debug description of a style state and everything it resolves to, for the
     * capture log (STYLE_PLAN 37): which built-in preset the pads correspond to, the pads
     * themselves, the independent amounts, and every resolved parameter. Values only —
     * never pixel data (AGENTS 55).
     */
    fun describe(style: StyleState): String {
        val p = resolve(style)
        val preset = StylePresets.entries.firstOrNull { it.matches(style) }?.id ?: "custom"
        return "$preset-v${style.version} tone=${style.tone.x}/${style.tone.y} " +
            "color=${style.color.x}/${style.color.y} palette=${style.palette.x}/${style.palette.y} " +
            "strength=${style.strength} bloom=${style.bloom} grain=${style.grain} " +
            "saturation=${style.saturation} | contrast=${p.toneContrast} midLift=${p.midtoneLift} " +
            "shoulder=${p.shoulderStrength} temp=${p.temperature} chroma=${p.chromaScale} " +
            "compress=${p.chromaCompression} hue=${p.paletteHueShift} " +
            "boost=${p.paletteChromaBoost} tint=${p.shadowHueTint}"
    }

    /**
     * Applies the style chain: a light strength-scaled denoise, the pad style, and
     * the signed Saturation control. Saturation and Bloom are independent controls —
     * they carry their own amounts and still apply with style strength at 0, unlike
     * the denoise and the pads, which scale with (or vanish at) strength 0
     * (STYLE_PLAN 12.3, 47). Grain is not here: it is applied to the delivered pixels
     * by the pipeline, so an equal shift on every channel keeps it colour-free
     * (see [GrainStage]).
     */
    fun apply(rgb: RgbImage, style: StyleState): RgbImage {
        val strength = style.strength.coerceIn(0f, 1f)
        val boost = style.saturation.coerceIn(0f, 1f)
        // Clean, then shape: the denoise runs first, so neither the pad tone curve nor
        // the saturation gain ever amplifies noise that could have been removed.
        //
        // The smooth half-resolution chroma smoother does this now, not the 2x2 block
        // reducer it replaced. A 2x2 average is block-periodic, so on a real frame it
        // *added* flat-area structure — +37% luma and +28% chroma, measured with only
        // the style chain toggled on a device DNG — instead of removing noise. Luma is
        // left to the base chain, where the coring sharpener decides what is grain.
        ChromaSmoother.apply(rgb, null, STYLE_CHROMA_DENOISE_STRENGTH * strength)
        // A boosted saturation widens the chroma it scales, noise included, so it pays
        // for its own amplification with the same smoother rather than a second pass on
        // a shifted 2x2 grid.
        if (boost > 0f) {
            ChromaSmoother.apply(rgb, null, SATURATION_CHROMA_DENOISE * boost)
        }
        if (strength > 0f) {
            val params = resolve(style)
            applyPalette(rgb, params)
            applyColor(rgb, params)
            applyTone(rgb, params)
        }
        // Signed and independent: 0 is neutral, and the user's own colour setting
        // applies with the pads and with style strength at 0, like Bloom and Grain.
        applySaturation(rgb, style.saturation)
        BloomStage.apply(rgb, style.bloom)
        return rgb
    }

    /**
     * Signed saturation as a chroma scale about the pixel's own luma. Scaling the
     * chroma offset by one factor leaves the pixel on the same colour direction
     * (hue) and only changes its distance from gray, which is exactly what a
     * saturation control should do; gray pixels have no chroma and stay untouched.
     */
    private fun applySaturation(rgb: RgbImage, saturation: Float) {
        val signed = saturation.coerceIn(-1f, 1f)
        if (signed == 0f) return
        val scale = 1f + SATURATION_RANGE * signed
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                val r = rgb.r[i]
                val g = rgb.g[i]
                val b = rgb.b[i]
                val l = luminance(r, g, b)
                rgb.r[i] = (l + (r - l) * scale).coerceAtLeast(0f)
                rgb.g[i] = (l + (g - l) * scale).coerceAtLeast(0f)
                rgb.b[i] = (l + (b - l) * scale).coerceAtLeast(0f)
            }
        }
    }

    /** Tone style via bounded tone-curve deltas (STYLE_PLAN 8.3); monotonic by construction. */
    private fun applyTone(rgb: RgbImage, params: ResolvedStyle) {
        // Neutral pads resolve to zero deltas; the pass below would then be an identity
        // that still walks three full-resolution channel arrays.
        if (params.toneContrast == 0f && params.midtoneLift == 0f && params.shoulderStrength == 0f) return
        // A firmer pad (+x) must expand the range around the pivot, not compress it.
        val contrastK = 1f + params.toneContrast
        val lift = params.midtoneLift
        val shoulder = params.shoulderStrength
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                var r = rgb.r[i]
                var g = rgb.g[i]
                var b = rgb.b[i]
                // Midtone lift/deepen as a bounded luminance-dependent gain. Lifting
                // (y > 0) raises only the shadows; deepening (y < 0) lowers the
                // midtones and highlights only, so the black point and the deep
                // shadow texture are never crushed (STYLE_PLAN 8.4).
                if (lift > 0f && r < 0.35f) r *= 1f + lift * (1f - r / 0.35f)
                if (lift < 0f && r > 0.30f) r += lift * (1f - (1f - r) / 0.70f)
                if (lift > 0f && g < 0.35f) g *= 1f + lift * (1f - g / 0.35f)
                if (lift < 0f && g > 0.30f) g += lift * (1f - (1f - g) / 0.70f)
                if (lift > 0f && b < 0.35f) b *= 1f + lift * (1f - b / 0.35f)
                if (lift < 0f && b > 0.30f) b += lift * (1f - (1f - b) / 0.70f)
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
                // A boost is a factor above 1 on the chroma offset: using the small
                // boost value *as* the factor collapsed R and B chroma to ~1% of
                // their offset, i.e. any nonzero palette X turned the frame grey.
                if (params.paletteChromaBoost > 0f) {
                    val boost = 1f + params.paletteChromaBoost
                    newR = l + (newR - l) * boost
                    newB = l + (newB - l) * boost
                }
                rgb.r[i] = newR.coerceAtLeast(0f)
                rgb.b[i] = newB.coerceAtLeast(0f)
            }
        }
    }
}

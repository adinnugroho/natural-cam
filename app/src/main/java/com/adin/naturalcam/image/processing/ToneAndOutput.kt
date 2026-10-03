package com.adin.naturalcam.image.processing

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.RgbGains
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.ToneConfig
import kotlin.math.pow

private const val LUT_SIZE = 4096
private const val LUT_SCALE = LUT_SIZE.toFloat()

private fun sampleUnitLut(table: FloatArray, x: Float): Float {
    if (x <= 0f) return table[0]
    if (x >= 1f) return table[LUT_SIZE]
    val position = x * LUT_SCALE
    val lower = position.toInt()
    val fraction = position - lower
    return table[lower] + (table[lower + 1] - table[lower]) * fraction
}

/**
 * All stages in this file mutate the input image and return it (ownership
 * transfer, AGENTS 45): full-frame float buffers at 12 MP are ~146 MB each,
 * so per-stage copies caused GC thrash on real devices. Copy first if the
 * caller needs the original. Math is unchanged from the copy-based versions.
 */

/** Exposure offset in linear light (stops). Physically-based multiply (AGENTS 22). */
object ExposureProcessor {

    fun apply(rgb: RgbImage, stops: Float): RgbImage {
        if (stops == 0f) return rgb
        val gain = 2f.pow(stops)
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                rgb.r[i] *= gain
                rgb.g[i] *= gain
                rgb.b[i] *= gain
            }
        }
        return rgb
    }
}

/**
 * Global NATURAL tone mapping in linear light around the real black point.
 * NATURAL uses a gentle sub-unity toe exponent below mid-gray to restore
 * readable shadows/midtones, while the upper branch keeps bright midtones
 * photographic and leaves highlight protection to [HighlightRollOff].
 */
object NaturalToneMapper {

    /** Linear light value treated as mid-gray. */
    const val PIVOT = 0.18f

    fun map(rgb: RgbImage, config: ToneConfig): RgbImage {
        if (config.midtoneGamma == 1f && config.contrast == 0f) return rgb
        val table = FloatArray(LUT_SIZE + 1) { index ->
            tone(index / LUT_SCALE, config)
        }
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                val luminance = linearLuminance(rgb.r[i], rgb.g[i], rgb.b[i])
                if (luminance > 0f) {
                    val mapped = if (luminance < 1f) sampleUnitLut(table, luminance) else tone(luminance, config)
                    val scale = mapped / luminance
                    rgb.r[i] *= scale
                    rgb.g[i] *= scale
                    rgb.b[i] *= scale
                }
            }
        }
        return rgb
    }

    internal fun tone(x: Float, config: ToneConfig): Float =
        if (x <= PIVOT) tone(x, config.midtoneGamma) else tone(x, 1f + config.contrast)

    internal fun tone(x: Float, exponent: Float): Float {
        if (x <= 0f) return 0f
        return PIVOT * (x / PIVOT).pow(exponent)
    }
}

/**
 * Smooth highlight shoulder: values above `start` compress toward white without
 * lifting sub-white values. The convex shoulder reaches exactly 1 at white,
 * preventing the washed/faded highlight behavior of an unnormalized exponential.
 */
object HighlightRollOff {

    fun apply(rgb: RgbImage, start: Float, compression: Float): RgbImage {
        if (compression <= 0f) return rgb
        require(start in 0f..1f) { "roll-off start must be within [0,1]" }
        val exponent = 1f + compression.coerceIn(0f, 1f) * 0.75f
        val span = 1f - start
        val table = FloatArray(LUT_SIZE + 1) { index ->
            start + span * (index / LUT_SCALE).pow(exponent)
        }
        CpuParallel.forEach(rgb.r.size) { startIndex, endIndex ->
            for (i in startIndex until endIndex) {
                val maximum = maxOf(rgb.r[i], rgb.g[i], rgb.b[i])
                if (maximum > start) {
                    val mapped = fastShoulder(maximum, start, exponent, span, table)
                    val scale = mapped / maximum
                    rgb.r[i] *= scale
                    rgb.g[i] *= scale
                    rgb.b[i] *= scale
                }
            }
        }
        return rgb
    }

    private fun fastShoulder(x: Float, start: Float, k: Float, span: Float, table: FloatArray): Float =
        when {
            x <= start -> x
            x < 1f && span > 0f -> sampleUnitLut(table, (x - start) / span)
            else -> shoulder(x, start, k)
        }
    internal fun shoulder(x: Float, start: Float, k: Float): Float {
        if (x <= start) return x
        val span = 1f - start
        if (span <= 0f) return 1f
        val exponent = 1f + (k - 1f).coerceAtLeast(0f) * 0.25f
        val t = ((x - start) / span).coerceIn(0f, 1f)
        return start + span * t.pow(exponent)
    }
}

private fun linearLuminance(r: Float, g: Float, b: Float): Float =
    0.2126f * r + 0.7152f * g + 0.0722f * b

/** Clips linear channels into the sRGB gamut before encoding (MVP strategy). */

object GamutMapper {

    fun clampToSrgbGamut(rgb: RgbImage): RgbImage {
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                rgb.r[i] = rgb.r[i].coerceIn(0f, 1f)
                rgb.g[i] = rgb.g[i].coerceIn(0f, 1f)
                rgb.b[i] = rgb.b[i].coerceIn(0f, 1f)
            }
        }
        return rgb
    }
}

/** Linear white-balance gains applied in linear light (PRD 17). */
object WhiteBalanceStage {

    fun apply(rgb: RgbImage, gains: RgbGains): RgbImage {
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                rgb.r[i] *= gains.r
                rgb.g[i] *= gains.g
                rgb.b[i] *= gains.b
            }
        }
        return rgb
    }

    /**
     * Conservative gray-world fallback when a DNG omits AsShotNeutral.
     * Prefer bright, low-chroma samples because colored subjects otherwise
     * bias the estimate and can turn whites purple.
     */
    fun auto(rgb: RgbImage): RgbGains {
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var count = 0
        for (i in rgb.r.indices) {
            val r = rgb.r[i]
            val g = rgb.g[i]
            val b = rgb.b[i]
            val luminance = (r + g + b) / 3f
            val chroma = maxOf(r, g, b) - minOf(r, g, b)
            if (luminance in 0.35f..0.98f && chroma <= maxOf(0.03f, luminance * 0.25f)) {
                sumR += r
                sumG += g
                sumB += b
                count++
            }
        }
        if (count < 32) {
            for (i in rgb.r.indices) {
                val r = rgb.r[i]
                val g = rgb.g[i]
                val b = rgb.b[i]
                if (r in 0.02f..0.95f && g in 0.02f..0.95f && b in 0.02f..0.95f) {
                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                }
            }
        }
        if (count < 32) return RgbGains(1f, 1f, 1f)
        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumB / count
        return RgbGains(
            r = (meanG / meanR).coerceIn(0.5f, 2f),
            g = 1f,
            b = (meanG / meanB).coerceIn(0.5f, 2f),
        )
    }
}

/**
 * Neutralizes only clipped, near-white RAW highlights. Once two transformed
 * channels exceed display white, their original chromaticity is no longer
 * recoverable; blending all channels toward their shared intensity avoids a
 * false magenta/cyan cast without touching saturated single-color highlights.
 */
object ClippedHighlightNeutralizer {

    fun apply(rgb: RgbImage): RgbImage {
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                val r = rgb.r[i]
                val g = rgb.g[i]
                val b = rgb.b[i]
                val minimum = minOf(r, g, b)
                val maximum = maxOf(r, g, b)
                val middle = r + g + b - minimum - maximum
                if (minimum > 0.6f && middle > 0.95f) {
                    val amount = ((middle - 0.95f) / 0.25f).coerceIn(0f, 1f)
                    val neutral = (r + g + b) / 3f
                    rgb.r[i] = r + (neutral - r) * amount
                    rgb.g[i] = g + (neutral - g) * amount
                    rgb.b[i] = b + (neutral - b) * amount
                }
            }
        }
        return rgb
}

}
/** Row-major 3x3 linear transform of an RGB image (AGENTS 21: centralized color logic). */
object ColorTransformStage {

    fun transform(rgb: RgbImage, matrix3x3: FloatArray): RgbImage {
        require(matrix3x3.size == 9) { "matrix3x3 must have 9 elements" }
        val m = matrix3x3
        CpuParallel.forEach(rgb.r.size) { start, end ->
            for (i in start until end) {
                val r = rgb.r[i]
                val g = rgb.g[i]
                val b = rgb.b[i]
                rgb.r[i] = m[0] * r + m[1] * g + m[2] * b
                rgb.g[i] = m[3] * r + m[4] * g + m[5] * b
                rgb.b[i] = m[6] * r + m[7] * g + m[8] * b
            }
        }
        return rgb
    }
}
/**
 * Linear → sRGB transfer (IEC 61966-2-1) and packing to 0xFFRRGGBB.
 * Non-finite values map to 0 so a single bad pixel can never poison an
 * encoder (numerical-safety rule, AGENTS 62).
 */
object OutputTransformer {

    private val srgbLut = FloatArray(LUT_SIZE + 1) { index ->
        val clamped = index / LUT_SCALE
        if (clamped <= 0.0031308f) {
            12.92f * clamped
        } else {
            1.055f * clamped.pow(1f / 2.4f) - 0.055f
        }
    }

    fun toArgb8888(rgb: RgbImage): IntArray {
        val out = IntArray(rgb.r.size)
        CpuParallel.forEach(out.size) { start, end ->
            for (i in start until end) {
                val r = encodeChannel(rgb.r[i])
                val g = encodeChannel(rgb.g[i])
                val b = encodeChannel(rgb.b[i])
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    internal fun encodeChannel(x: Float): Int {
        if (!x.isFinite() || x <= 0f) return 0
        val clamped = if (x > 1f) 1f else x
        return (sampleUnitLut(srgbLut, clamped) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}

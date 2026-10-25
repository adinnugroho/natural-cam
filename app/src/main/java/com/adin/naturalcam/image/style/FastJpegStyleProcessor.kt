package com.adin.naturalcam.image.style

import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.image.core.luminance
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Speed-first style pass for platform JPEG input.
 *
 * It deliberately works in encoded RGB instead of rebuilding a full linear-light RAW frame. This
 * is the bounded NATURAL fallback used when shutter-to-gallery latency outranks RAW rendering.
 * RAW captures keep the reference pipeline; this path preserves the visible style controls without
 * pretending the OEM JPEG can be deprocessed.
 */
internal object FastJpegStyleProcessor {
    fun apply(argb: IntArray, width: Int, height: Int, style: StyleState) {
        val strength = style.strength.coerceIn(0f, 1f)
        val saturation = style.saturation.coerceIn(-1f, 1f)
        val params = StyleEngine.resolve(style)
        val hasPadEffects =
            params.toneContrast != 0f ||
                params.midtoneLift != 0f ||
                params.shoulderStrength != 0f ||
                params.temperature != 0f ||
                params.chromaScale != 1f ||
                params.chromaCompression != 0f ||
                params.paletteHueShift != 0f ||
                params.paletteChromaBoost != 0f ||
                params.shadowHueTint != 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = argb[index]
                var r = ((pixel ushr 16) and 0xFF) / 255f
                var g = ((pixel ushr 8) and 0xFF) / 255f
                var b = (pixel and 0xFF) / 255f
                var luma = luminance(r, g, b)

                if (hasPadEffects) {
                    val cr = r - luma
                    val cb = b - luma
                    val chromaWeight = ((cr * cr + cb * cb) / (cr * cr + cb * cb + 0.02f)).coerceIn(0f, 1f)
                    var newR = r + params.paletteHueShift * 0.20f * cb
                    var newB = b - params.paletteHueShift * 0.20f * cr
                    val shadowWeight = exp(-luma * 2f) * 0.35f * chromaWeight
                    newR += params.shadowHueTint * shadowWeight
                    newB -= params.shadowHueTint * shadowWeight
                    if (params.paletteChromaBoost > 0f) {
                        val boost = 1f + params.paletteChromaBoost
                        newR = luma + (newR - luma) * boost
                        newB = luma + (newB - luma) * boost
                    }
                    r = newR
                    b = newB

                    var colorR = (r - luma) * params.chromaScale
                    var colorG = (g - luma) * params.chromaScale
                    var colorB = (b - luma) * params.chromaScale
                    val chromaMagnitude = max(abs(colorR), max(abs(colorG), abs(colorB)))
                    if (params.chromaCompression > 0f && chromaMagnitude > 0.35f) {
                        val excess = (chromaMagnitude - 0.35f) / 0.55f
                        val damp = 1f - params.chromaCompression * excess / (1f + excess * 0.7f)
                        colorR *= damp
                        colorG *= damp
                        colorB *= damp
                    }
                    val warmShift = params.temperature * chromaWeight
                    r = luma + colorR * (1f + warmShift)
                    g = luma + colorG
                    b = luma + colorB * (1f - warmShift)

                    r = tone(r, params.toneContrast, params.midtoneLift, params.shoulderStrength)
                    g = tone(g, params.toneContrast, params.midtoneLift, params.shoulderStrength)
                    b = tone(b, params.toneContrast, params.midtoneLift, params.shoulderStrength)
                }

                if (saturation != 0f) {
                    val scale = 1f + 0.6f * saturation
                    luma = luminance(r, g, b)
                    r = luma + (r - luma) * scale
                    g = luma + (g - luma) * scale
                    b = luma + (b - luma) * scale
                }

                if (style.bloom > 0f) {
                    val highlight = max(luminance(r, g, b) - 0.7f, 0f)
                    val amount = style.bloom.coerceIn(0f, 1f) * highlight * 0.2f
                    r += amount
                    g += amount
                    b += amount
                }

                argb[index] = pack(r, g, b)
            }
        }
    }

    private fun tone(value: Float, contrast: Float, lift: Float, shoulder: Float): Float {
        var result = value
        if (lift > 0f && result < 0.35f) result *= 1f + lift * (1f - result / 0.35f)
        if (lift < 0f && result > 0.30f) result += lift * (1f - (1f - result) / 0.70f)
        result = 0.18f + (result - 0.18f) * (1f + contrast)
        if (shoulder > 0f && result > 0.7f) result -= shoulder * (result - 0.7f) * 0.2f
        return result.coerceAtLeast(0f)
    }



    private fun pack(r: Float, g: Float, b: Float): Int {
        val red = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val green = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val blue = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

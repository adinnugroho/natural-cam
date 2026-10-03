package com.adin.naturalcam.image.processing

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.RgbImage
/**
 * Restrained 2×2 chroma denoising. Each pixel keeps its own linear luminance;
 * only color differences are blended toward the block mean. Real luminance
 * grain and texture therefore remain, with no synthetic grain added.
 *
 * The block filter is intentionally small and allocation-bounded for 12 MP
 * captures. ponytail: replace with an edge-aware chroma filter only if 2×2
 * color blocking becomes visible at a stronger measured tuning.
 */
object NoiseReducer {

    fun reduce(rgb: RgbImage, chromaStrength: Float, lumaStrength: Float): RgbImage {
        val chroma = chromaStrength.coerceIn(0f, 1f)
        val luma = lumaStrength.coerceIn(0f, 1f)
        if (chroma == 0f && luma == 0f) return rgb

        val blockRows = rgb.height / 2
        CpuParallel.forEach(blockRows, minItemsPerTask = 128) { startBlockRow, endBlockRow ->
            for (blockRow in startBlockRow until endBlockRow) {
                val y = blockRow * 2
                val top = y * rgb.width
                val bottom = top + rgb.width
                var x = 0
                while (x + 1 < rgb.width) {
                    val i0 = top + x
                    val i1 = i0 + 1
                    val i2 = bottom + x
                    val i3 = i2 + 1

                    val r0 = rgb.r[i0]; val g0 = rgb.g[i0]; val b0 = rgb.b[i0]
                    val r1 = rgb.r[i1]; val g1 = rgb.g[i1]; val b1 = rgb.b[i1]
                    val r2 = rgb.r[i2]; val g2 = rgb.g[i2]; val b2 = rgb.b[i2]
                    val r3 = rgb.r[i3]; val g3 = rgb.g[i3]; val b3 = rgb.b[i3]
                    val l0 = linearLuminance(r0, g0, b0)
                    val l1 = linearLuminance(r1, g1, b1)
                    val l2 = linearLuminance(r2, g2, b2)
                    val l3 = linearLuminance(r3, g3, b3)
                    val meanLuminance = (l0 + l1 + l2 + l3) * 0.25f
                    val meanChromaR = (r0 - l0 + r1 - l1 + r2 - l2 + r3 - l3) * 0.25f
                    val meanChromaG = (g0 - l0 + g1 - l1 + g2 - l2 + g3 - l3) * 0.25f
                    val meanChromaB = (b0 - l0 + b1 - l1 + b2 - l2 + b3 - l3) * 0.25f

                    writePixel(rgb, i0, r0, g0, b0, l0, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    writePixel(rgb, i1, r1, g1, b1, l1, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    writePixel(rgb, i2, r2, g2, b2, l2, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    writePixel(rgb, i3, r3, g3, b3, l3, meanLuminance, meanChromaR, meanChromaG, meanChromaB, chroma, luma)
                    x += 2
                }
            }
        }
        return rgb
    }

    private fun writePixel(
        rgb: RgbImage,
        index: Int,
        r: Float,
        g: Float,
        b: Float,
        sourceLuminance: Float,
        meanLuminance: Float,
        meanChromaR: Float,
        meanChromaG: Float,
        meanChromaB: Float,
        chromaStrength: Float,
        lumaStrength: Float,
    ) {
        val outputLuminance = sourceLuminance +
            lumaStrength * (meanLuminance - sourceLuminance)
        val sourceChromaR = r - sourceLuminance
        val sourceChromaG = g - sourceLuminance
        val sourceChromaB = b - sourceLuminance
        rgb.r[index] = outputLuminance + sourceChromaR +
            chromaStrength * (meanChromaR - sourceChromaR)
        rgb.g[index] = outputLuminance + sourceChromaG +
            chromaStrength * (meanChromaG - sourceChromaG)
        rgb.b[index] = outputLuminance + sourceChromaB +
            chromaStrength * (meanChromaB - sourceChromaB)
    }

    private fun linearLuminance(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * Conservative unsharp masking (PRD 20): real detail over perceived sharpness,
 * amount clamped to 0.5 to make halos structurally hard to produce. The blur
 * is a separable [1,2,1]/4 two-pass filter (radius ≈ 1 px at the default).
 * Amount 0 is an exact no-op (AGENTS 27).
 */
object Sharpener {

    const val MAX_AMOUNT = 0.5f

    fun sharpen(rgb: RgbImage, amount: Float, radiusPx: Float): RgbImage {
        val a = amount.coerceIn(0f, MAX_AMOUNT)
        if (a == 0f) return rgb
        val passes = radiusPx.coerceAtLeast(1f).toInt().coerceIn(1, 3)
        val n = rgb.r.size
        val work = FloatArray(n)
        val blurredA = FloatArray(n)
        val blurredB = if (passes > 1) FloatArray(n) else blurredA
        for (channel in arrayOf(rgb.r, rgb.g, rgb.b)) {
            var source = channel
            var target = blurredA
            repeat(passes) {
                blur3x3Into(source, target, rgb.width, rgb.height, work)
                source = target
                target = if (target === blurredA) blurredB else blurredA
            }
            CpuParallel.forEach(n) { start, end ->
                for (i in start until end) {
                    channel[i] = channel[i] + a * (channel[i] - source[i])
                }
            }
        }
        return rgb
    }
}

/**
 * Separable binomial blur [1,2,1] with edge replication. Shared by denoise and
 * sharpen so their spatial footprint stays identical. Returns a freshly
 * blurred array; [work] is an n-sized scratch buffer the caller owns.
 */
internal fun blur3x3(src: FloatArray, w: Int, h: Int, work: FloatArray): FloatArray {
    val out = FloatArray(w * h)
    blur3x3Into(src, out, w, h, work)
    return out
}

internal fun blur3x3Into(src: FloatArray, out: FloatArray, w: Int, h: Int, work: FloatArray) {
    // Horizontal pass into scratch.
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val row = y * w
            for (x in 0 until w) {
                val left = src[row + if (x > 0) x - 1 else 0]
                val mid = src[row + x]
                val right = src[row + if (x < w - 1) x + 1 else w - 1]
                work[row + x] = (left + 2f * mid + right) * 0.25f
            }
        }
    }
    // Vertical pass into caller-owned output.
    CpuParallel.forEach(h, minItemsPerTask = 128) { startRow, endRow ->
        for (y in startRow until endRow) {
            val upRow = if (y > 0) y - 1 else 0
            val downRow = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                out[y * w + x] = (work[upRow * w + x] + 2f * work[y * w + x] + work[downRow * w + x]) * 0.25f
            }
        }
    }
}

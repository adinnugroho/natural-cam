package com.adin.naturalcam.image.yuv

import com.adin.naturalcam.image.core.CpuParallel
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.YuvImage

/**
 * Stride-correct YUV_420_888 → linear RGB (AGENTS 31 — row/pixel strides are
 * respected per plane; planes are never assumed packed).
 *
 * Assumptions (documented per AGENTS 22): Android camera YUV_420_888 is
 * limited-range BT.601; values are display-referred, so the result is
 * linearized with the sRGB inverse transfer before entering the processing
 * pipeline. Full-range or BT.709 sources would need a capability-driven
 * variant later.
 *
 * The chroma planes are 4:2:0, so each 2x2 block of luma shares one U and one V
 * sample. The loop reads that sample once per block and reuses it for all four
 * pixels instead of re-reading it per pixel (the four reads were identical).
 *
 * Note: the inverse transfer is *not* replaced with a 256-entry table indexed by
 * an input byte. Its argument is not a raw 8-bit sample — it is the clamped
 * BT.601 combination `(Y-16)/219 + 1.402·(V-128)/224` (and its G/B forms), which
 * is scaled and offset, so a byte-indexed table could not reproduce
 * `pow(x, 2.4)` exactly. `Math.pow` stays.
 */
object YuvToRgbConverter {

    fun toRgb(yuv: YuvImage): RgbImage {
        val out = RgbImage(yuv.width, yuv.height)
        val blockRows = (yuv.height + 1) / 2
        CpuParallel.forEach(blockRows, minItemsPerTask = 64) { startBlock, endBlock ->
            for (blockY in startBlock until endBlock) {
                val y0 = blockY * 2
                val y1 = y0 + 1
                val uRow = blockY * yuv.uRowStride
                val vRow = blockY * yuv.vRowStride
                var x = 0
                while (x < yuv.width) {
                    // One chroma read per 2x2 block: columns x and x+1 both fall in it.
                    val uVal = (yuv.uPlane[uRow + (x / 2) * yuv.uPixelStride].toInt() and 0xFF) - 128
                    val vVal = (yuv.vPlane[vRow + (x / 2) * yuv.vPixelStride].toInt() and 0xFF) - 128
                    writePixel(yuv, out, y0, x, uVal, vVal)
                    if (x + 1 < yuv.width) writePixel(yuv, out, y0, x + 1, uVal, vVal)
                    if (y1 < yuv.height) {
                        writePixel(yuv, out, y1, x, uVal, vVal)
                        if (x + 1 < yuv.width) writePixel(yuv, out, y1, x + 1, uVal, vVal)
                    }
                    x += 2
                }
            }
        }
        return out
    }

    private fun writePixel(yuv: YuvImage, out: RgbImage, y: Int, x: Int, uVal: Int, vVal: Int) {
        val yVal = (yuv.yPlane[y * yuv.yRowStride + x * yuv.yPixelStride].toInt() and 0xFF) - 16

        // ITU-R BT.601 limited range, normalized to [0,1] display RGB.
        val yy = yVal / 219f
        var r = yy + 1.402f * (vVal / 224f)
        var g = yy - 0.344136f * (uVal / 224f) - 0.714136f * (vVal / 224f)
        var b = yy + 1.772f * (uVal / 224f)
        r = r.coerceIn(0f, 1f)
        g = g.coerceIn(0f, 1f)
        b = b.coerceIn(0f, 1f)

        val i = y * yuv.width + x
        out.r[i] = inverseSrgbOetf(r)
        out.g[i] = inverseSrgbOetf(g)
        out.b[i] = inverseSrgbOetf(b)
    }

    internal fun inverseSrgbOetf(x: Float): Float =
        if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow24()
}

private fun Float.pow24(): Float = Math.pow(this.toDouble(), 2.4).toFloat()

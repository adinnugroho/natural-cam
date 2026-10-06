package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.processing.LumaDenoiser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smooth luma denoiser (currently off in NATURAL — see LIMITATIONS). Its two
 * invariants matter: it must not touch colour, and it must actually reduce
 * common-mode (luminance) grain.
 */
class LumaDenoiserTest {

    private fun highFrequency(values: FloatArray, width: Int): Float {
        var sum = 0.0
        var count = 0
        for (y in 1 until width - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val mean = (values[i - 1] + values[i + 1] + values[i - width] + values[i + width] + values[i]) / 5f
                val d = (values[i] - mean).toDouble()
                sum += d * d
                count++
            }
        }
        return Math.sqrt(sum / count).toFloat()
    }

    private fun image(size: Int, seedStart: Long): RgbImage {
        val rgb = RgbImage(size, size)
        var seed = seedStart
        for (i in rgb.r.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val grain = ((seed % 2001) / 1000f - 1f) * 0.02f
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val chroma = ((seed % 2001) / 1000f - 1f) * 0.03f
            rgb.r[i] = 0.5f + grain + chroma
            rgb.g[i] = 0.5f + grain
            rgb.b[i] = 0.5f + grain - chroma * 0.5f
        }
        return rgb
    }

    @Test
    fun `colour differences are preserved exactly`() {
        val rgb = image(32, 4242L)
        val rg = FloatArray(rgb.r.size) { rgb.r[it] - rgb.g[it] }
        val bg = FloatArray(rgb.r.size) { rgb.b[it] - rgb.g[it] }
        LumaDenoiser.apply(rgb, 0.7f)
        for (i in rgb.r.indices) {
            // Same offset on every channel, so only float rounding separates these.
            assertEquals(rg[i], rgb.r[i] - rgb.g[i], 1e-6f)
            assertEquals(bg[i], rgb.b[i] - rgb.g[i], 1e-6f)
        }
    }

    @Test
    fun `common mode grain drops`() {
        val size = 64
        val rgb = RgbImage(size, size)
        var seed = 11L
        for (i in rgb.r.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val value = 0.5f + ((seed % 2001) / 1000f - 1f) * 0.02f
            rgb.r[i] = value
            rgb.g[i] = value
            rgb.b[i] = value
        }
        val before = highFrequency(rgb.g, size)
        LumaDenoiser.apply(rgb, 0.5f)
        assertTrue("grain ${highFrequency(rgb.g, size)} not below $before", highFrequency(rgb.g, size) < before * 0.8f)
    }

    @Test
    fun `zero strength is an exact no-op`() {
        val rgb = image(16, 99L)
        val beforeR = rgb.r.copyOf()
        val beforeG = rgb.g.copyOf()
        val beforeB = rgb.b.copyOf()
        LumaDenoiser.apply(rgb, 0f)
        for (i in rgb.r.indices) {
            assertEquals(beforeR[i], rgb.r[i], 0f)
            assertEquals(beforeG[i], rgb.g[i], 0f)
            assertEquals(beforeB[i], rgb.b[i], 0f)
        }
    }
}

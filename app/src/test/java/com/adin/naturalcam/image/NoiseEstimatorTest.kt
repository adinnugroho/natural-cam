package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.GRAIN_CLEAN
import com.adin.naturalcam.image.core.GRAIN_NOISY
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.grainLevel
import com.adin.naturalcam.image.processing.NoiseEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grain estimate that scales the adaptive denoise. */
class NoiseEstimatorTest {

    private fun image(size: Int, noise: Float, seedStart: Long): RgbImage {
        val rgb = RgbImage(size, size)
        var seed = seedStart
        for (i in rgb.r.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            val grain = ((seed % 2001) / 1000f - 1f) * noise
            val value = 0.2f + grain
            rgb.r[i] = value
            rgb.g[i] = value
            rgb.b[i] = value
        }
        return rgb
    }

    @Test
    fun `grain level spans clean to noisy and clamps outside`() {
        assertEquals(0f, grainLevel(GRAIN_CLEAN * 0.5f), 1e-6f)
        assertEquals(0f, grainLevel(0f), 1e-6f)
        assertEquals(1f, grainLevel(GRAIN_NOISY), 1e-6f)
        assertEquals(1f, grainLevel(GRAIN_NOISY * 10f), 1e-6f)
        val middle = grainLevel((GRAIN_CLEAN + GRAIN_NOISY) / 2f)
        assertTrue("midpoint must sit between", middle > 0.4f && middle < 0.6f)
        assertTrue(grainLevel(GRAIN_CLEAN + 1e-4f) < grainLevel(GRAIN_NOISY - 1e-4f))
    }

    @Test
    fun `a flat frame reads clean and a grainy one does not`() {
        val flat = RgbImage(128, 128)
        for (i in flat.r.indices) {
            flat.r[i] = 0.25f
            flat.g[i] = 0.25f
            flat.b[i] = 0.25f
        }
        val noisy = image(128, 0.02f, 31L)
        val flatGrain = NoiseEstimator.shadowGrain(flat)
        val noisyGrain = NoiseEstimator.shadowGrain(noisy)
        assertEquals("a perfectly flat frame has no grain", 0f, flatGrain, 1e-6f)
        assertTrue("grain $noisyGrain must read above clean", noisyGrain > GRAIN_CLEAN)
    }
}

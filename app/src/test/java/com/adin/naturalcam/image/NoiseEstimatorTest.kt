package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.GRAIN_CLEAN
import com.adin.naturalcam.image.core.GRAIN_NOISY
import com.adin.naturalcam.image.core.grainLevel
import com.adin.naturalcam.image.processing.NoiseEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grain estimate that scales the adaptive denoise. */
class NoiseEstimatorTest {

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
        val flat = TestImages.uniform(128, 0.25f)
        val noisy = TestImages.noisyGray(128, noise = 0.02f, seed = 31L, base = 0.2f)
        val flatGrain = NoiseEstimator.shadowGrain(flat)
        val noisyGrain = NoiseEstimator.shadowGrain(noisy)
        assertEquals("a perfectly flat frame has no grain", 0f, flatGrain, 1e-6f)
        assertTrue("grain $noisyGrain must read above clean", noisyGrain > GRAIN_CLEAN)
    }
}

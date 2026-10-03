package com.adin.naturalcam.camera.camerax

import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.adin.naturalcam.domain.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Locks the aspect-ratio control to real stream behavior (AGENTS 35): each
 * domain option must map to the documented CameraX strategy.
 */
class OutputResolutionMappingTest {

    @Test
    fun `frame ratios map to fixed aspect strategies`() {
        val ratio43 = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_4_3)
        assertSame(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, ratio43.aspectRatioStrategy)

        val ratio169 = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_16_9)
        assertSame(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY, ratio169.aspectRatioStrategy)
    }

    @Test
    fun `maximum mode combines highest resolution with selected aspect`() {
        val maximum = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_4_3, highestResolution = true)

        assertSame(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, maximum.aspectRatioStrategy)
        assertSame(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY, maximum.resolutionStrategy)
    }

    @Test
    fun `full maps to highest available resolution without forcing a frame ratio`() {
        val full = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_FULL)
        assertSame(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY, full.resolutionStrategy)
    }

    @Test
    fun `every domain aspect option has a mapping`() {
        for (aspect in AspectRatio.entries) {
            // A missing mapping would throw; presence is the assertion.
            OutputResolutionMapping.resolutionSelector(aspect)
        }
    }
}

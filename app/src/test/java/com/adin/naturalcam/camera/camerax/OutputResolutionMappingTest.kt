package com.adin.naturalcam.camera.camerax

import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.adin.naturalcam.domain.AspectRatio
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Locks the aspect-ratio control to real stream behavior (AGENTS 35): each
 * domain option must map to the documented CameraX strategy.
 */
class OutputResolutionMappingTest {

    @Test
    fun `four by three uses the full sensor resolution`() {
        val ratio43 = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_4_3)

        assertSame(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, ratio43.aspectRatioStrategy)
        assertSame(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY, ratio43.resolutionStrategy)
    }

    @Test
    fun `sixteen by nine keeps its aspect strategy by default`() {
        val ratio169 = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_16_9)

        assertSame(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY, ratio169.aspectRatioStrategy)
    }

    @Test
    fun `maximum mode combines highest resolution with selected aspect`() {
        val maximum = OutputResolutionMapping.resolutionSelector(AspectRatio.RATIO_16_9, highestResolution = true)

        assertSame(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY, maximum.aspectRatioStrategy)
        assertSame(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY, maximum.resolutionStrategy)
    }

}

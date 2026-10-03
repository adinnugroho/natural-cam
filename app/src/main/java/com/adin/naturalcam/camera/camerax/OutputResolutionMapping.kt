package com.adin.naturalcam.camera.camerax

import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.adin.naturalcam.domain.AspectRatio

/**
 * Domain aspect ratio and output-size policy → CameraX stream configuration.
 * Maximum mode keeps the selected aspect ratio where one exists, then asks
 * CameraX for the largest compatible stream (AGENTS 35).
 */
object OutputResolutionMapping {

    fun resolutionSelector(aspect: AspectRatio, highestResolution: Boolean = false): ResolutionSelector {
        if (highestResolution) {
            val builder = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            when (aspect) {
                AspectRatio.RATIO_4_3 -> builder.setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                AspectRatio.RATIO_16_9 -> builder.setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                AspectRatio.RATIO_FULL -> Unit
            }
            return builder.build()
        }
        return when (aspect) {
            AspectRatio.RATIO_4_3 -> base(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            AspectRatio.RATIO_16_9 -> base(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            AspectRatio.RATIO_FULL -> ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
        }
    }

    private fun base(strategy: AspectRatioStrategy): ResolutionSelector =
        ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()
}

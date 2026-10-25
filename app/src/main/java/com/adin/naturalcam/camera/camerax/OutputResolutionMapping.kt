package com.adin.naturalcam.camera.camerax

import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.adin.naturalcam.domain.AspectRatio

/**
 * Domain aspect ratio and output-size policy → CameraX stream configuration.
 * 4:3 is the full-resolution sensor mode; 16:9 keeps its aspect-constrained
 * stream unless the explicit maximum policy is requested (AGENTS 35).
 */
object OutputResolutionMapping {

    fun resolutionSelector(aspect: AspectRatio, highestResolution: Boolean = false): ResolutionSelector {
        if (aspect == AspectRatio.RATIO_4_3 || highestResolution) {
            val builder = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            when (aspect) {
                AspectRatio.RATIO_4_3 ->
                    builder.setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                AspectRatio.RATIO_16_9 ->
                    builder.setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            }
            return builder.build()
        }
        return base(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    }

    private fun base(strategy: AspectRatioStrategy): ResolutionSelector =
        ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()
}

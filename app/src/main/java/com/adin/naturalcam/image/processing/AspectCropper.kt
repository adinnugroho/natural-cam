package com.adin.naturalcam.image.processing

import com.adin.naturalcam.domain.AspectRatio
import kotlin.math.roundToInt

/**
 * Centre-crop to the selected output aspect ratio, applied to the finished pixels in
 * display orientation (after [ImageRotation]).
 *
 * This is pure geometry: the kept pixels are a subset of the same rendering, so it is
 * not a processing change and does not bump a pipeline version — exactly like the
 * rotation it follows. Without it the 16:9 / 4:3 / Full control changed nothing in the
 * RAW develop, because the developed frame is the sensor's full 4:3 area, while the
 * platform's own JPEG path did honour the setting.
 *
 * [AspectRatio.RATIO_FULL] is "the sensor's own frame": it never crops, which is why it
 * equals 4:3 on a 4:3 sensor. The DNG that RAW modes save is never cropped — it stays
 * the full sensor frame, like any RAW.
 */
object AspectCropper {

    /**
     * Crops [argb] to [aspect]. Returns the input holder unchanged when the frame already
     * matches, so 4:3/Full on a 4:3 sensor costs nothing.
     */
    fun crop(argb: IntArray, width: Int, height: Int, aspect: AspectRatio): ImageRotation.RotatedPixels {
        val target = targetRatio(aspect, width, height) ?: return ImageRotation.RotatedPixels(argb, width, height)
        val current = width.toFloat() / height
        val cropWidth: Int
        val cropHeight: Int
        when {
            current > target -> {
                cropWidth = (height * target).roundToInt().coerceIn(1, width)
                cropHeight = height
            }
            current < target -> {
                cropWidth = width
                cropHeight = (width / target).roundToInt().coerceIn(1, height)
            }
            else -> return ImageRotation.RotatedPixels(argb, width, height)
        }
        if (cropWidth == width && cropHeight == height) return ImageRotation.RotatedPixels(argb, width, height)

        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        val out = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            System.arraycopy(argb, (top + y) * width + left, out, y * cropWidth, cropWidth)
        }
        return ImageRotation.RotatedPixels(out, cropWidth, cropHeight)
    }

    /**
     * Target width/height for [aspect] in the frame's own orientation, or null for "no
     * crop". A portrait frame uses the reciprocal of the ratio, so 16:9 stays 16:9 whether
     * the phone is held upright or sideways (the platform's 16:9 JPEG behaves the same way).
     */
    internal fun targetRatio(aspect: AspectRatio, width: Int, height: Int): Float? {
        val landscape = when (aspect) {
            AspectRatio.RATIO_FULL -> return null
            AspectRatio.RATIO_4_3 -> 4f / 3f
            AspectRatio.RATIO_16_9 -> 16f / 9f
        }
        return if (width < height) 1f / landscape else landscape
    }
}

package com.adin.naturalcam.image

import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.image.core.DefaultImagePipeline
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.core.YuvImage
import com.adin.naturalcam.image.processing.AspectCropper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The selected aspect ratio has to reach the delivered pixels. Found on device
 * (2026-10-08): 16:9 and Full both produced a 4:3 image in NATURAL because the RAW
 * develop had no crop stage, while the platform JPEG path did honour the setting.
 */
class AspectCropperTest {

    @Test
    fun `sixteen by nine crops the long side in both orientations`() {
        val landscape = AspectCropper.crop(IntArray(400 * 300), 400, 300, AspectRatio.RATIO_16_9)
        assertEquals(400, landscape.width)
        assertEquals(225, landscape.height) // 400 / (16/9)

        // Portrait uses the reciprocal, so the result matches the platform's 16:9 still
        // (4096x2304 landscape == 2304x4096 portrait): trim the width, keep the height.
        val portrait = AspectCropper.crop(IntArray(300 * 400), 300, 400, AspectRatio.RATIO_16_9)
        assertEquals(225, portrait.width) // 400 * 9/16
        assertEquals(400, portrait.height)
    }

    @Test
    fun `full and already matching frames are returned untouched`() {
        val argb = IntArray(12 * 9)
        assertSame(argb, AspectCropper.crop(argb, 12, 9, AspectRatio.RATIO_FULL).argb)
        assertSame(argb, AspectCropper.crop(argb, 12, 9, AspectRatio.RATIO_4_3).argb)
    }

    @Test
    fun `crop is centred and keeps the middle rows`() {
        // 4x3 (4:3) to 16:9 drops one row: 4 / (16/9) = 2.25 -> 2 rows, centred.
        val argb = IntArray(12) { it }
        val out = AspectCropper.crop(argb, 4, 3, AspectRatio.RATIO_16_9)
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7), out.argb)
    }

    @Test
    fun `pipeline encodes the selected aspect`() {
        // 16x12 is 4:3, so the 16:9 crop is 16x9 with no rotation involved.
        val w = 16
        val h = 12
        val yuv = YuvImage(
            width = w, height = h,
            yPlane = ByteArray(w * h) { (it % 200).toByte() },
            yRowStride = w, yPixelStride = 1,
            uPlane = ByteArray(w * h / 4) { 128.toByte() },
            uRowStride = w / 2, uPixelStride = 1,
            vPlane = ByteArray(w * h / 4) { 128.toByte() },
            vRowStride = w / 2, vPixelStride = 1,
        )
        val encoder = RecordingEncoder()
        DefaultImagePipeline(encoder).processYuv(
            yuv,
            ProcessingConfiguration.forProfile(ProcessingProfile.PURE, aspectRatio = AspectRatio.RATIO_16_9),
            orientationDegrees = 0,
        )
        assertEquals(1, encoder.calls)
        assertEquals(w, encoder.lastWidth)
        assertEquals(9, encoder.lastHeight)
    }
}

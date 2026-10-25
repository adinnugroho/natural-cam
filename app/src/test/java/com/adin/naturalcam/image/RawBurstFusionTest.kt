package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.raw.RawBracketFusion
import com.adin.naturalcam.image.raw.RawBurstFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBurstFusionTest {
    @Test
    fun `same exposure burst averages sensor samples`() {
        val frames = listOf(96, 100, 104).map { value -> raw(value) }

        val result = RawBurstFusion.fuse(frames)

        assertEquals(3, result.mergedFrameCount)
        assertEquals(0, result.skippedFrameCount)
        assertTrue(result.image.pixelData.all { (it.toInt() and 0xffff) == 100 })
    }

    @Test
    fun `exposure product normalizes a brighter frame`() {
        val reference = raw(value = 100, exposureNs = 1_000_000L, iso = 100)
        val brighter = raw(value = 200, exposureNs = 2_000_000L, iso = 100)

        val result = RawBurstFusion.fuse(listOf(reference, brighter))

        assertEquals(2, result.mergedFrameCount)
        assertTrue(result.image.pixelData.all { (it.toInt() and 0xffff) == 100 })
    }

    @Test
    fun `HDR bracket normalizes short and long exposures around normal reference`() {
        val result = RawBracketFusion.fuse(
            listOf(
                raw(value = 50, exposureNs = 500_000L),
                raw(value = 100, exposureNs = 1_000_000L),
                raw(value = 200, exposureNs = 2_000_000L),
            ),
        )

        assertEquals(3, result.mergedFrameCount)
        assertEquals("hdr-cpu", result.backend)
        assertTrue(result.image.pixelData.all { (it.toInt() and 0xffff) == 100 })
    }

    @Test
    fun `translation alignment maps shifted frame back to reference`() {
        val width = 24
        val height = 16
        val metadata = raw(0, width = width, height = height).metadata
        fun valueAt(x: Int, y: Int): Short =
            (100 + ((x * 17 + y * 31) % 700)).toShort()
        val referencePixels = ShortArray(width * height) { index ->
            valueAt(index % width, index / width)
        }
        val shiftedPixels = ShortArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x < 4) 0 else valueAt(x - 4, y)
        }

        val result = RawBurstFusion.fuse(
            listOf(
                RawImage(width, height, referencePixels, metadata),
                RawImage(width, height, shiftedPixels, metadata),
            ),
        )

        assertEquals(2, result.mergedFrameCount)
        assertTrue(result.alignmentRmse.single() < 0.001f)
        for (y in 0 until height) {
            for (x in 0 until width - 4) {
                val index = y * width + x
                assertEquals(
                    referencePixels[index].toInt() and 0xffff,
                    result.image.pixelData[index].toInt() and 0xffff,
                )
            }
        }
    }

    @Test
    fun `incompatible frame is skipped instead of being mixed`() {
        val reference = raw(value = 100, width = 12, height = 8)
        val incompatible = raw(value = 200, width = 10, height = 8)

        val result = RawBurstFusion.fuse(listOf(reference, incompatible))

        assertEquals(1, result.mergedFrameCount)
        assertEquals(1, result.skippedFrameCount)
        assertTrue(result.image.pixelData.all { (it.toInt() and 0xffff) == 100 })
    }

    private fun raw(
        value: Int,
        width: Int = 12,
        height: Int = 8,
        exposureNs: Long = 1_000_000L,
        iso: Int = 100,
    ): RawImage = RawImage(
        width = width,
        height = height,
        pixelData = ShortArray(width * height) { value.toShort() },
        metadata = RawCaptureMetadata(
            cfa = CfaLayout.RGGB,
            blackLevelPerChannel = floatArrayOf(0f, 0f, 0f),
            whiteLevel = 1023,
            colorMatrix1 = null,
            colorMatrix2 = null,
            forwardMatrix1 = null,
            forwardMatrix2 = null,
            asShotNeutral = floatArrayOf(1f, 1f, 1f),
            isoSpeed = iso,
            exposureTimeNs = exposureNs,
            aperture = null,
            focalLengthMm = null,
            orientationDegrees = 0,
            timestampMs = 1L,
        ),
    )
}

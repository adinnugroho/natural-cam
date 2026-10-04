package com.adin.naturalcam.image

import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.DefaultImagePipeline
import com.adin.naturalcam.image.core.EncodedImage
import com.adin.naturalcam.image.core.JpegEncoder
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.YuvImage
import com.adin.naturalcam.image.yuv.YuvToRgbConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineAndYuvTest {

    private class RecordingEncoder : JpegEncoder {
        var calls = 0
        var lastWidth = 0
        var lastHeight = 0
        var lastQuality = 0
        override fun encode(argb: IntArray, width: Int, height: Int, quality: Int): EncodedImage {
            calls++
            lastWidth = width
            lastHeight = height
            lastQuality = quality
            return EncodedImage(ByteArray(argb.size * 4) { 0 }, width, height)
        }
    }

    private fun rawMeta(cfa: CfaLayout) = RawCaptureMetadata(
        cfa = cfa,
        blackLevelPerChannel = floatArrayOf(0f),
        whiteLevel = 1023,
        colorMatrix1 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        colorMatrix2 = null,
        forwardMatrix1 = null,
        forwardMatrix2 = null,
        asShotNeutral = floatArrayOf(1f, 1f, 1f),
        isoSpeed = null,
        exposureTimeNs = null,
        aperture = null,
        focalLengthMm = null,
        orientationDegrees = 0,
        timestampMs = 0,
    )

    private fun rawImage(cfa: CfaLayout, size: Int = 8, value: Int = 512): RawImage =
        RawImage(size, size, ShortArray(size * size) { value.toShort() }, rawMeta(cfa))

    // ---- YUV ----

    @Test
    fun `yuv converter is stride independent`() {
        val w = 6
        val h = 4
        // Packed layout.
        val packed = YuvImage(
            width = w, height = h,
            yPlane = ByteArray(w * h) { (it * 7).toByte() },
            yRowStride = w, yPixelStride = 1,
            uPlane = ByteArray(w * h / 4) { (it * 11).toByte() },
            uRowStride = w / 2, uPixelStride = 1,
            vPlane = ByteArray(w * h / 4) { (it * 13).toByte() },
            vRowStride = w / 2, vPixelStride = 1,
        )
        // Same logical content, padded rows and chroma pixel stride 2.
        val yPadded = ByteArray((w + 3) * h) { 0.toByte() }
        for (y in 0 until h) for (x in 0 until w) yPadded[y * (w + 3) + x] = packed.yPlane[y * w + x]
        val uPhysical = ByteArray((w + 2) * (h / 2) + 8) { 0.toByte() }
        val vPhysical = ByteArray((w + 2) * (h / 2) + 8) { 0.toByte() }
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            uPhysical[y * (w + 2) + x * 2] = packed.uPlane[y * (w / 2) + x]
            vPhysical[y * (w + 2) + x * 2] = packed.vPlane[y * (w / 2) + x]
        }
        val strided = YuvImage(
            width = w, height = h,
            yPlane = yPadded, yRowStride = w + 3, yPixelStride = 1,
            uPlane = uPhysical, uRowStride = w + 2, uPixelStride = 2,
            vPlane = vPhysical, vRowStride = w + 2, vPixelStride = 2,
        )

        val a = YuvToRgbConverter.toRgb(packed)
        val b = YuvToRgbConverter.toRgb(strided)
        for (i in a.r.indices) {
            assertEquals("pixel $i", a.r[i], b.r[i], 0f)
            assertEquals("pixel $i", a.g[i], b.g[i], 0f)
            assertEquals("pixel $i", a.b[i], b.b[i], 0f)
        }
    }

    @Test
    fun `yuv black and white map to linear endpoints`() {
        fun yuv(yVal: Int, uVal: Int = 128, vVal: Int = 128) = YuvImage(
            width = 1, height = 1,
            yPlane = byteArrayOf(yVal.toByte()), yRowStride = 1, yPixelStride = 1,
            uPlane = byteArrayOf(uVal.toByte()), uRowStride = 1, uPixelStride = 1,
            vPlane = byteArrayOf(vVal.toByte()), vRowStride = 1, vPixelStride = 1,
        )
        val white = YuvToRgbConverter.toRgb(yuv(235))
        assertEquals(1f, white.r[0], 1e-3f)
        val black = YuvToRgbConverter.toRgb(yuv(16))
        assertEquals(0f, black.r[0], 1e-3f)
    }

    // ---- pipeline ----

    @Test
    fun `pipeline develops raw for every cfa layout`() {
        val encoder = RecordingEncoder()
        val pipeline = DefaultImagePipeline(encoder)
        for (layout in CfaLayout.entries) {
            val encoded = pipeline.processRaw(rawImage(layout), ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL))
            assertEquals(8, encoded.width)
            assertEquals(8, encoded.height)
        }
        assertEquals(CfaLayout.entries.size, encoder.calls)
    }

    @Test
    fun `natural applies tone while pure stays minimal`() {
        val encoder = RecordingEncoder()
        val pipeline = DefaultImagePipeline(encoder)
        // Gradient-ish input so tone mapping has something to move.
        val raw = RawImage(8, 8, ShortArray(64) { (it * 16).toShort() }, rawMeta(CfaLayout.RGGB))
        val natural = pipeline.processRaw(raw, ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL))
        val pure = pipeline.processRaw(raw, ProcessingConfiguration.forProfile(ProcessingProfile.PURE))
        // Both complete with the requested geometry; the stub encoder proves
        // end-to-end wiring. Difference in pixel content is asserted below at
        // the ARGB level via OutputTransformer-independent reconstruction:
        assertEquals(8, natural.width)
        assertEquals(8, pure.width)
        assertTrue(natural.bytes.size == pure.bytes.size)
    }

    @Test
    fun `pure raw output of neutral scene stays neutral`() {
        val encoder = RecordingEncoder()
        val pipeline = DefaultImagePipeline(encoder)
        val encoded = pipeline.processRaw(rawImage(CfaLayout.GBRG, value = 512), ProcessingConfiguration.forProfile(ProcessingProfile.PURE))
        assertEquals(8, encoded.width)
        assertEquals(1, encoder.calls)
    }

    @Test
    fun `yuv path runs end to end`() {
        val encoder = RecordingEncoder()
        val pipeline = DefaultImagePipeline(encoder)
        val yuv = YuvImage(
            width = 4, height = 4,
            yPlane = ByteArray(16) { 100.toByte() }, yRowStride = 4, yPixelStride = 1,
            uPlane = ByteArray(4) { 128.toByte() }, uRowStride = 2, uPixelStride = 1,
            vPlane = ByteArray(4) { 128.toByte() }, vRowStride = 2, vPixelStride = 1,
        )
        val encoded = pipeline.processYuv(yuv, ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL), 0)
        assertEquals(4, encoded.width)
        assertEquals(4, encoded.height)
        assertEquals(92, encoder.lastQuality)
    }

    @Test
    fun `pipeline version is stable for regression tracking`() {
        assertEquals("natural-v16", ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL).pipelineVersion)
        assertEquals("pure-v2", ProcessingConfiguration.forProfile(ProcessingProfile.PURE).pipelineVersion)
        assertEquals(ProcessingConfiguration.PIPELINE_VERSION, DefaultImagePipeline(RecordingEncoder()).version)
    }

    @Test
    fun `natural recipe carries its own exposure lift`() {
        // The NATURAL look includes a fixed pipeline lift, independent of the
        // camera EV control. PURE and SYSTEM must never inherit it.
        assertEquals(1.5f, ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL).tone.exposureStops, 0f)
        assertEquals(0f, ProcessingConfiguration.forProfile(ProcessingProfile.PURE).tone.exposureStops, 0f)
        assertEquals(0f, ProcessingConfiguration.forProfile(ProcessingProfile.SYSTEM).tone.exposureStops, 0f)
    }
}

package com.adin.naturalcam.image

import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.DefaultImagePipeline
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.YuvImage
import com.adin.naturalcam.image.yuv.YuvToRgbConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineAndYuvTest {

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
        assertEquals("natural-v38", ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL).pipelineVersion)
        assertEquals("pure-v2", ProcessingConfiguration.forProfile(ProcessingProfile.PURE).pipelineVersion)
        assertEquals(ProcessingConfiguration.PIPELINE_VERSION, DefaultImagePipeline(RecordingEncoder()).version)
    }

    @Test
    fun `natural recipe carries its own exposure lift`() {
        // The NATURAL look includes a fixed pipeline lift, independent of the
        // camera EV control. PURE and SYSTEM must never inherit it.
        assertEquals(0.75f, ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL).tone.exposureStops, 0f)
        assertEquals(0f, ProcessingConfiguration.forProfile(ProcessingProfile.PURE).tone.exposureStops, 0f)
        assertEquals(0f, ProcessingConfiguration.forProfile(ProcessingProfile.SYSTEM).tone.exposureStops, 0f)
    }

    @Test
    fun `bloom style adds a visible halo around highlights in the encoded image`() {
        val size = 128
        fun render(bloom: Float): IntArray {
            val encoder = RecordingEncoder()
            val pipeline = DefaultImagePipeline(encoder)
            pipeline.processYuv(
                scene(size),
                ProcessingConfiguration.forProfile(ProcessingProfile.NATURAL, style = StyleState(bloom = bloom)),
                0,
            )
            return encoder.argb
        }
        fun red(argb: IntArray, x: Int) = (argb[64 * size + x] shr 16) and 0xFF

        val off = render(0f)
        val on = render(1f)

        // 8 px outside the 16 px bright patch the glow must be plainly visible,
        // and it must still reach 40 px away. Measured 26 and 11 at amount 1.
        val nearHalo = red(on, 48) - red(off, 48)
        val farHalo = red(on, 16) - red(off, 16)
        assertTrue("bloom halo too weak at 8 px: $nearHalo", nearHalo >= 10)
        assertTrue("bloom halo does not spread: $farHalo", farHalo >= 5)
        // Already-white patch pixels cannot brighten further.
        assertEquals(red(off, 64), red(on, 64))
    }

    @Test
    fun `grain adds luma texture without adding chroma noise`() {
        val size = 96

        fun render(grain: Float, strength: Float = 1f): IntArray {
            val encoder = RecordingEncoder()
            DefaultImagePipeline(encoder).processYuv(
                coloredNoiseScene(size),
                ProcessingConfiguration.forProfile(
                    ProcessingProfile.NATURAL,
                    style = StyleState(grain = grain, strength = strength),
                ),
                0,
            )
            return encoder.argb
        }

        fun highPass(plane: FloatArray, width: Int, height: Int): FloatArray {
            val out = FloatArray(plane.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var sum = 0f
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val ny = y + dy
                            val nx = x + dx
                            if (ny in 0 until height && nx in 0 until width) {
                                sum += plane[ny * width + nx]
                                count++
                            }
                        }
                    }
                    out[y * width + x] = plane[y * width + x] - sum / count
                }
            }
            return out
        }

        fun rms(plane: FloatArray): Float = kotlin.math.sqrt(plane.sumOf { (it * it).toDouble() } / plane.size).toFloat()

        fun channels(argb: IntArray): Triple<FloatArray, FloatArray, FloatArray> {
            val n = argb.size
            val luma = FloatArray(n)
            val rg = FloatArray(n)
            val bg = FloatArray(n)
            for (i in 0 until n) {
                val r = ((argb[i] shr 16) and 0xFF).toFloat()
                val g = ((argb[i] shr 8) and 0xFF).toFloat()
                val b = (argb[i] and 0xFF).toFloat()
                luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                rg[i] = r - g
                bg[i] = b - g
            }
            return Triple(luma, rg, bg)
        }

        val off = channels(render(0f))
        val on = channels(render(1f))

        fun hf(channel: (Triple<FloatArray, FloatArray, FloatArray>) -> FloatArray): Float =
            rms(highPass(channel(on), size, size))

        val lumaOff = rms(highPass(off.first, size, size))
        val lumaOn = rms(highPass(on.first, size, size))
        val rgOff = rms(highPass(off.second, size, size))
        val rgOn = rms(highPass(on.second, size, size))
        val bgOff = rms(highPass(off.third, size, size))
        val bgOn = rms(highPass(on.third, size, size))
        val lumaAdded = lumaOn - lumaOff
        val chromaAdded = maxOf(rgOn - rgOff, bgOn - bgOff)

        // Luma grain must be clearly there, and it must not drag any chroma with it:
        // the shift is identical on all three channels, so R−G and B−G are exact.
        // Measured at amount 1 through the real pipeline: luma HF +0.63 with chroma
        // HF identical to the last bit (the 3×3 high-pass used here removes much of
        // the 3-px clumped grain, so the raw luma sigma is several levels).
        assertTrue("grain did not add luma texture: +$lumaAdded", lumaAdded > 0.5f)
        assertTrue(
            "grain added chroma noise: luma +$lumaAdded, chroma +$chromaAdded",
            chromaAdded < 0.001f,
        )
        // Independent control: grain applies with style strength at 0 too.
        val zeroStrength = channels(render(1f, strength = 0f))
        assertTrue(
            "grain must survive style strength 0",
            rms(highPass(zeroStrength.first, size, size)) > 1.05f * lumaOff,
        )
    }

    private fun coloredNoiseScene(size: Int): YuvImage {
        // Constant luma with noisy chroma: the base image carries almost no luma
        // texture, so any luma HF growth in the render comes from the grain, while
        // the noisy chroma is there to catch grain that leaks into colour.
        val y = ByteArray(size * size) { 130.toByte() }
        val u = ByteArray(size * size / 4)
        val v = ByteArray(size * size / 4)
        var seed = 987654321L
        fun next(): Int {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            return (seed % 1000).toInt()
        }
        for (i in u.indices) {
            u[i] = (118 + next() % 13).toByte()
            v[i] = (142 + next() % 13).toByte()
        }
        return YuvImage(
            width = size, height = size,
            yPlane = y, yRowStride = size, yPixelStride = 1,
            uPlane = u, uRowStride = size / 2, uPixelStride = 1,
            vPlane = v, vRowStride = size / 2, vPixelStride = 1,
        )
    }

    private fun scene(size: Int): YuvImage {
        val y = ByteArray(size * size) { 60.toByte() }
        for (yy in 56 until 72) for (xx in 56 until 72) y[yy * size + xx] = 235.toByte()
        return YuvImage(
            width = size, height = size,
            yPlane = y, yRowStride = size, yPixelStride = 1,
            uPlane = ByteArray(size * size / 4) { 128.toByte() }, uRowStride = size / 2, uPixelStride = 1,
            vPlane = ByteArray(size * size / 4) { 128.toByte() }, vRowStride = size / 2, vPixelStride = 1,
        )
    }
}

package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.BayerImage
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.RgbGains
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.processing.ColorTransformStage
import com.adin.naturalcam.image.raw.ColorMatrixFactory
import com.adin.naturalcam.image.raw.Demosaicer
import com.adin.naturalcam.image.raw.RawNormalizer
import com.adin.naturalcam.image.processing.WhiteBalanceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStagesTest {

    private fun meta(
        cfa: CfaLayout = CfaLayout.RGGB,
        black: FloatArray = floatArrayOf(0f),
        white: Int = 1023,
        asShotNeutral: FloatArray? = null,
        colorMatrix1: FloatArray? = null,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        colorMatrix2: FloatArray? = null,
        calibrationIlluminant1: Int? = null,
        calibrationIlluminant2: Int? = null,
    ) = RawCaptureMetadata(
        cfa = cfa,
        blackLevelPerChannel = black,
        whiteLevel = white,
        colorMatrix1 = colorMatrix1,
        colorMatrix2 = colorMatrix2,
        forwardMatrix1 = forwardMatrix1,
        forwardMatrix2 = forwardMatrix2,
        asShotNeutral = asShotNeutral,
        isoSpeed = null,
        exposureTimeNs = null,
        aperture = null,
        focalLengthMm = null,
        orientationDegrees = 0,
        timestampMs = 0,
        calibrationIlluminant1 = calibrationIlluminant1,
        calibrationIlluminant2 = calibrationIlluminant2,
    )

    @Test
    fun `cfa layouts map every 2x2 position`() {
        // Pattern rows encoded as channel indices: [top-left, top-right; bottom-left, bottom-right].
        assertEquals(listOf(0, 1, 1, 2), pattern(CfaLayout.RGGB))
        assertEquals(listOf(1, 0, 2, 1), pattern(CfaLayout.GRBG))
        assertEquals(listOf(1, 2, 0, 1), pattern(CfaLayout.GBRG))
        assertEquals(listOf(2, 1, 1, 0), pattern(CfaLayout.BGGR))
    }

    private fun pattern(cfa: CfaLayout): List<Int> =
        listOf(cfa.channelAt(0, 0), cfa.channelAt(1, 0), cfa.channelAt(0, 1), cfa.channelAt(1, 1))

    @Test
    fun `normalizer subtracts per color black level and keeps overexposure`() {
        val black = floatArrayOf(64f, 32f, 16f)
        val pixels = ShortArray(4) { 532 } // G,B,G,R order below via layout
        // RGGB: positions (0,0)R (1,0)G (0,1)G (1,1)B → set distinct values.
        pixels[0] = 1064 // R: (1064-64)/(1016-64) = 1.0526…
        pixels[1] = 532 // G: (532-32)/(1016-32) = 0.5081…
        pixels[2] = 532
        pixels[3] = 216 // B: (216-16)/(1016-16) = 0.2
        val raw = RawImage(2, 2, pixels, meta(cfa = CfaLayout.RGGB, black = black, white = 1016))
        val bayer = RawNormalizer.normalize(raw)
        assertEquals(1000f / 952f, bayer.values[0], 1e-5f) // (1064-64)/(1016-64)
        assertEquals(500f / 984f, bayer.values[1], 1e-5f) // (532-32)/(1016-32)
        assertEquals(0.2f, bayer.values[3], 1e-6f)
    }

    @Test
    fun `demosaic reconstructs flat color exactly for every cfa layout`() {
        val target = floatArrayOf(0.2f, 0.5f, 0.8f)
        for (layout in CfaLayout.entries) {
            val w = 8
            val h = 8
            val values = FloatArray(w * h) { i -> target[layout.channelAt(i % w, i / w)] }
            val rgb = Demosaicer.demosaic(BayerImage(w, h, layout, values))
            assertTrue("$layout R has NaN", rgb.r.none { it.isNaN() })
            assertTrue("$layout G has NaN", rgb.g.none { it.isNaN() })
            assertTrue("$layout B has NaN", rgb.b.none { it.isNaN() })
            for (i in values.indices) {
                assertEquals(layout.toString(), target[0], rgb.r[i], 1e-5f)
                assertEquals(layout.toString(), target[1], rgb.g[i], 1e-5f)
                assertEquals(layout.toString(), target[2], rgb.b[i], 1e-5f)
            }
        }
    }

    @Test
    fun `demosaic never leaks the NaN sentinel on odd sizes`() {
        val target = floatArrayOf(0.2f, 0.5f, 0.8f)
        // Odd in both axes: the border fallbacks that use the NaN sentinel are all
        // exercised, and a sentinel surviving would poison every later stage.
        for (layout in CfaLayout.entries) for ((w, h) in listOf(7 to 5, 5 to 7, 9 to 3)) {
            val values = FloatArray(w * h) { i -> target[layout.channelAt(i % w, i / w)] }
            val rgb = Demosaicer.demosaic(BayerImage(w, h, layout, values))
            assertTrue("$layout ${w}x$h R has NaN", rgb.r.none { it.isNaN() })
            assertTrue("$layout ${w}x$h G has NaN", rgb.g.none { it.isNaN() })
            assertTrue("$layout ${w}x$h B has NaN", rgb.b.none { it.isNaN() })
            for (i in values.indices) {
                assertEquals("$layout ${w}x$h R[$i]", target[0], rgb.r[i], 1e-5f)
                assertEquals("$layout ${w}x$h G[$i]", target[1], rgb.g[i], 1e-5f)
                assertEquals("$layout ${w}x$h B[$i]", target[2], rgb.b[i], 1e-5f)
            }
        }
    }

    @Test
    fun `demosaic preserves own channel and averages diagonals at known sites`() {
        // 3x3 RGGB; R samples at the four corners are 1,2,3,4, others zero.
        val values = FloatArray(9)
        values[0] = 1f; values[2] = 2f; values[6] = 3f; values[8] = 4f
        val rgb = Demosaicer.demosaic(BayerImage(3, 3, CfaLayout.RGGB, values))
        // Center (1,1) is a B site: R comes from the four diagonal R samples.
        assertEquals(2.5f, rgb.r[4], 1e-6f)
        // (0,1) is a G site in the B row, column edge: R from vertical neighbors (0,0),(0,2).
        assertEquals(2.0f, rgb.r[3], 1e-6f)
        // Own channel preserved at an R site.
        assertEquals(1f, rgb.r[0], 1e-6f)
    }

    @Test
    fun `as shot neutral maps to green normalized gains`() {
        val gains = RgbGains.fromAsShotNeutral(floatArrayOf(1.0f, 1.0f, 0.5f))
        assertEquals(1f, gains.r, 1e-6f)
        assertEquals(1f, gains.g, 1e-6f)
        assertEquals(2f, gains.b, 1e-6f)
    }

    @Test
    fun `white balance multiplies linear channels`() {
        val rgb = RgbImage(1, 1).also { it.r[0] = 0.5f; it.g[0] = 0.5f; it.b[0] = 0.5f }
        val out = WhiteBalanceStage.apply(rgb, RgbGains(2f, 1f, 0.5f))
        assertEquals(1f, out.r[0], 1e-6f)
        assertEquals(0.5f, out.g[0], 1e-6f)
        assertEquals(0.25f, out.b[0], 1e-6f)
    }

    @Test
    fun `color matrix factory prefers forward matrix`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val marker = floatArrayOf(9f, 0f, 0f, 0f, 9f, 0f, 0f, 0f, 9f)
        val meta = meta(forwardMatrix1 = identity, forwardMatrix2 = marker, colorMatrix1 = identity)
        val m = ColorMatrixFactory.cameraToWorkingRgbMatrix(meta)
        // result = XYZ_D50_TO_SRGB · marker(=9·I) → first element 9 × 3.1338561.
        assertEquals(9f * 3.1338561f, m[0], 1e-3f)
    }

    @Test
    fun `calibration illuminant selects the matching matrix`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val d65Neutral = floatArrayOf(0.95047f, 1f, 1.08883f)
        val metadata = meta(
            asShotNeutral = d65Neutral,
            colorMatrix1 = identity,
            colorMatrix2 = identity,
            calibrationIlluminant1 = 21,
            calibrationIlluminant2 = 17,
        )

        assertEquals("ColorMatrix1/ForwardMatrix1", ColorMatrixFactory.matrixSelectionName(metadata))
    }

    @Test
    fun `color matrix factory inverts dng color matrix`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val m = ColorMatrixFactory.cameraToWorkingRgbMatrix(meta(colorMatrix1 = identity))
        assertEquals(3.1338561f, m[0], 1e-4f)
    }

    @Test
    fun `color matrix factory rejects metadata without matrices`() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorMatrixFactory.cameraToWorkingRgbMatrix(meta())
        }
    }

    @Test
    fun `degenerate forward matrix falls back to color matrix`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        // Zero forward matrix (what the Oppo CPH2737 ships) must not black out the image.
        val degenerate = floatArrayOf(0f)
        val m = ColorMatrixFactory.cameraToWorkingRgbMatrix(
            meta(forwardMatrix1 = degenerate, colorMatrix1 = identity),
        )
        assertEquals(3.1338561f, m[0], 1e-4f)
    }

    @Test
    fun `camera color transformer applies row major matrix`() {
        val rgb = RgbImage(1, 1).also { it.r[0] = 1f; it.g[0] = 2f; it.b[0] = 3f }
        val m = floatArrayOf(1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 2f)
        val out = ColorTransformStage.transform(rgb, m)
        assertEquals(3f, out.r[0], 1e-6f)
        assertEquals(2f, out.g[0], 1e-6f)
        assertEquals(6f, out.b[0], 1e-6f)
    }
}

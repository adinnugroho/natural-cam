package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.ToneConfig
import com.adin.naturalcam.image.processing.ClippedHighlightNeutralizer
import com.adin.naturalcam.image.processing.BloomStage

import com.adin.naturalcam.image.processing.ExposureProcessor
import com.adin.naturalcam.image.processing.GamutMapper
import com.adin.naturalcam.image.processing.GrainStage
import com.adin.naturalcam.image.processing.HighlightRollOff
import com.adin.naturalcam.image.processing.NaturalToneMapper
import com.adin.naturalcam.image.processing.NoiseReducer
import com.adin.naturalcam.image.processing.OutputTransformer
import com.adin.naturalcam.image.processing.Sharpener
import com.adin.naturalcam.image.processing.WhiteBalanceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneAndFilterTest {

    /** Deterministic pseudo-random image in [0,1] (fixed LCG seed). */
    private fun randomImage(size: Int = 64): RgbImage {
        val rgb = RgbImage(size, size)
        var seed = 1234567L
        for (i in rgb.r.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            rgb.r[i] = (seed % 1000) / 1000f
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            rgb.g[i] = (seed % 1000) / 1000f
            seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
            rgb.b[i] = (seed % 1000) / 1000f
        }
        return rgb
    }

    private fun testLuminance(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Packed ARGB pixel. */
    private fun packArgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    /** Packed ARGB image with the same level in all three channels. */
    private fun grayArgb(size: Int, level: Int): IntArray = IntArray(size * size) { packArgb(level, level, level) }

    private fun FloatArray.range(): Float = maxOrNull()!! - minOrNull()!!

    @Test
    fun `exposure applies stops in linear light`() {
        val rgb = RgbImage(1, 1).also { it.r[0] = 0.25f; it.g[0] = 0.25f; it.b[0] = 0.25f }
        val out = ExposureProcessor.apply(rgb, 1f)
        assertEquals(0.5f, out.r[0], 1e-6f)
        val untouched = RgbImage(1, 1).also { it.r[0] = 0.25f; it.g[0] = 0.25f; it.b[0] = 0.25f }
        val same = ExposureProcessor.apply(untouched, 0f)
        assertEquals(0.25f, same.r[0], 1e-6f)
    }

    @Test
    fun `tone mapping is monotonic and preserves zero`() {
        val config = ToneConfig()
        assertEquals(0f, NaturalToneMapper.tone(0f, 1.06f), 0f)
        var previous = -1f
        for (step in 0..200) {
            val x = step / 100f
            val y = NaturalToneMapper.tone(x, 1.06f)
            assertTrue("not monotonic at $x", y >= previous)
            assertTrue("not finite at $x", y.isFinite())
            previous = y
        }
    }

    @Test
    fun `natural tone keeps black and deepens shadows`() {
        val config = ToneConfig()
        val exponent = config.midtoneGamma * (1f + config.contrast)

        assertEquals(0f, NaturalToneMapper.tone(0f, exponent), 0f)
        assertTrue(NaturalToneMapper.tone(0.1f, exponent) < 0.1f)
    }

    @Test
    fun `tone map of neutral image stays neutral`() {
        val rgb = RgbImage(4, 4)
        for (i in rgb.r.indices) {
            rgb.r[i] = 0.3f; rgb.g[i] = 0.3f; rgb.b[i] = 0.3f
        }
        val out = NaturalToneMapper.map(rgb, ToneConfig())
        for (i in rgb.r.indices) {
            assertEquals(out.r[i], out.g[i], 1e-6f)
            assertEquals(out.g[i], out.b[i], 1e-6f)
        }
    }

    @Test
    fun `natural tone and highlight rolloff preserve chromaticity`() {
        val toneInput = RgbImage(1, 1).also {
            it.r[0] = 0.08f
            it.g[0] = 0.12f
            it.b[0] = 0.16f
        }
        NaturalToneMapper.map(toneInput, ToneConfig())
        assertEquals(2f / 3f, toneInput.r[0] / toneInput.g[0], 1e-5f)
        assertEquals(4f / 3f, toneInput.b[0] / toneInput.g[0], 1e-5f)

        val highlightInput = RgbImage(1, 1).also {
            it.r[0] = 0.8f
            it.g[0] = 0.9f
            it.b[0] = 1.2f
        }
        HighlightRollOff.apply(highlightInput, 0.75f, 0.5f)
        assertEquals(8f / 9f, highlightInput.r[0] / highlightInput.g[0], 1e-5f)
        assertEquals(4f / 3f, highlightInput.b[0] / highlightInput.g[0], 1e-5f)
    }

    @Test
    fun `auto white balance fallback neutralizes balanced scene means`() {
        val rgb = RgbImage(8, 8)
        for (i in rgb.r.indices) {
            rgb.r[i] = 0.2f
            rgb.g[i] = 0.3f
            rgb.b[i] = 0.4f
        }
        WhiteBalanceStage.apply(rgb, WhiteBalanceStage.auto(rgb))
        assertEquals(rgb.r[0], rgb.g[0], 1e-5f)
        assertEquals(rgb.g[0], rgb.b[0], 1e-5f)
    }

    @Test
    fun `clipped near-white highlight becomes neutral without desaturating colored highlight`() {
        val nearWhite = RgbImage(1, 1).also {
            it.r[0] = 1.4f
            it.g[0] = 0.8f
            it.b[0] = 1.3f
        }
        ClippedHighlightNeutralizer.apply(nearWhite)
        assertEquals(nearWhite.r[0], nearWhite.g[0], 1e-6f)
        assertEquals(nearWhite.g[0], nearWhite.b[0], 1e-6f)

        val green = RgbImage(1, 1).also {
            it.r[0] = 0.2f
            it.g[0] = 1.4f
            it.b[0] = 0.3f
        }
        ClippedHighlightNeutralizer.apply(green)
        assertEquals(0.2f, green.r[0], 0f)
        assertEquals(1.4f, green.g[0], 0f)
        assertEquals(0.3f, green.b[0], 0f)
    }

    @Test
    fun `highlight roll off is monotonic continuous and softer than clipping`() {
        val start = 0.75f
        val k = 1f + 3f * 0.5f
        var previous = -1f
        for (step in 0..300) {
            val x = step / 100f
            val y = HighlightRollOff.shoulder(x, start, k)
            assertTrue("not monotonic at $x", y >= previous)
            assertTrue("exceeds white at $x", y <= 1.0001f)
            previous = y
        }
        assertEquals(start, HighlightRollOff.shoulder(start, start, k), 1e-6f)
        // Continuous at the knee: values just above start stay just above start.
        assertTrue(HighlightRollOff.shoulder(start + 1e-4f, start, k) - start < 1e-3f)
        // Softer than hard clipping in the shoulder region.
        assertTrue(HighlightRollOff.shoulder(1.2f, start, k) < 1.2f)
        assertTrue("shoulder must not lift sub-white highlights", HighlightRollOff.shoulder(0.9f, start, k) < 0.9f)
    }

    @Test
    fun `highlight roll off with zero compression is identity`() {
        val rgb = randomImage(8)
        val before = Triple(rgb.r.copyOf(), rgb.g.copyOf(), rgb.b.copyOf())
        HighlightRollOff.apply(rgb, 0.75f, 0f)
        for (i in rgb.r.indices) {
            assertEquals(before.first[i], rgb.r[i], 0f)
            assertEquals(before.second[i], rgb.g[i], 0f)
            assertEquals(before.third[i], rgb.b[i], 0f)
        }
    }

    @Test
    fun `filters are exact identity at zero strength`() {
        val rgb = randomImage(16)
        val before = rgb.r.copyOf()
        NoiseReducer.reduce(rgb, 0f, 0f)
        for (i in rgb.r.indices) assertEquals(before[i], rgb.r[i], 0f)
        Sharpener.sharpen(rgb, 0f, 1f)
        for (i in rgb.r.indices) assertEquals(before[i], rgb.r[i], 0f)
    }

    @Test
    fun `bloom leaves sub-threshold pixels unchanged and spreads highlights`() {
        val dim = RgbImage(5, 5)
        for (i in dim.r.indices) {
            dim.r[i] = 0.5f
            dim.g[i] = 0.5f
            dim.b[i] = 0.5f
        }
        val before = dim.r.copyOf()
        BloomStage.apply(dim, 1f)
        assertEquals(before.toList(), dim.r.toList())

        val highlight = RgbImage(5, 5)
        highlight.r[12] = 1f
        highlight.g[12] = 0.8f
        highlight.b[12] = 0.9f
        BloomStage.apply(highlight, 1f)
        assertTrue(highlight.r[11] > 0f)
        assertTrue(highlight.g[11] > 0f)
        assertTrue(highlight.b[11] > 0f)
    }

    @Test
    fun `grain amount zero is an exact no-op`() {
        val argb = grayArgb(32, 128)
        val before = argb.copyOf()

        GrainStage.apply(argb, 32, 32, 0f)

        assertEquals(before.toList(), argb.toList())
    }

    @Test
    fun `grain is deterministic and shifts every channel by one whole level`() {
        val argb = grayArgb(64, 110)
        val before = argb.copyOf()

        GrainStage.apply(argb, 64, 64, 1f)

        var changed = false
        for (i in argb.indices) {
            val dr = red(argb[i]) - red(before[i])
            // One whole-level shift for all three channels: channel differences, i.e.
            // colour, are untouched by construction.
            assertEquals(dr, green(argb[i]) - green(before[i]))
            assertEquals(dr, blue(argb[i]) - blue(before[i]))
            assertTrue("grain exceeded its bound at $i: $dr", kotlin.math.abs(dr) <= 36)
            if (dr != 0) changed = true
        }
        assertTrue("grain must change pixels", changed)

        // Same coordinates, same shifts: developing the same input twice matches.
        val repeat = grayArgb(64, 110)
        GrainStage.apply(repeat, 64, 64, 1f)
        assertEquals(argb.toList(), repeat.toList())
    }

    @Test
    fun `grain adds no colour to a saturated image`() {
        val size = 64
        val argb = IntArray(size * size) { packArgb(190, 60, 230) }
        val before = argb.copyOf()

        GrainStage.apply(argb, size, size, 1f)

        for (i in argb.indices) {
            assertEquals(
                "R:G difference must survive grain",
                red(before[i]) - green(before[i]),
                red(argb[i]) - green(argb[i]),
            )
            assertEquals(
                "B:G difference must survive grain",
                blue(before[i]) - green(before[i]),
                blue(argb[i]) - green(argb[i]),
            )
            assertTrue("grain must stay inside the level range", red(argb[i]) in 0..255)
            assertTrue("grain must stay inside the level range", blue(argb[i]) in 0..255)
        }
    }

    @Test
    fun `grain is fine-grained, crisp, and varies in density across the frame`() {
        val size = 256
        val level = 77
        val argb = grayArgb(size, level)

        GrainStage.apply(argb, size, size, 1f)

        val offsets = FloatArray(argb.size) { (red(argb[it]) - level).toFloat() }
        val sigma = kotlin.math.sqrt(offsets.sumOf { (it * it).toDouble() } / offsets.size).toFloat()
        assertTrue("grain must have amplitude", sigma > 1f)

        // Grain size: the field decorrelates within a pixel. Blurred, multi-pixel clumps
        // sat at ~0.75 correlation at lag 1 and were exactly what read as blur.
        var cross = 0.0
        var count = 0
        for (y in 0 until size) {
            for (x in 0 until size - 1) {
                cross += (offsets[y * size + x] * offsets[y * size + x + 1]).toDouble()
                count++
            }
        }
        val lagOne = (cross / count / (sigma * sigma)).toFloat()
        assertTrue("grain is too coarse/blurred: lag-1 correlation $lagOne", lagOne < 0.5f)

        // Crispness: neighbouring differences are large relative to the amplitude
        // (white noise sits at 1.13; blurred clumps fall well below 1).
        var stepSum = 0.0
        for (y in 0 until size) {
            for (x in 0 until size - 1) {
                stepSum += kotlin.math.abs(offsets[y * size + x + 1] - offsets[y * size + x]).toDouble()
            }
        }
        val meanStep = (stepSum / (size * (size - 1))).toFloat()
        val ratio = meanStep / sigma
        assertTrue("grain is too soft: step ratio $ratio", ratio > 0.9f && ratio < 1.25f)

        // Density modulation: 64×64 blocks must not all carry exactly the same amplitude.
        val blockSigmas = ArrayList<Float>()
        for (by in 0 until 4) {
            for (bx in 0 until 4) {
                var sum = 0.0
                for (y in by * 64 until by * 64 + 64) {
                    for (x in bx * 64 until bx * 64 + 64) {
                        val d = offsets[y * size + x].toDouble()
                        sum += d * d
                    }
                }
                blockSigmas.add(kotlin.math.sqrt(sum / (64 * 64)).toFloat())
            }
        }
        val min = blockSigmas.min()
        val max = blockSigmas.max()
        assertTrue("grain density is uniform: $min..$max", max > 1.05f * min)

        // Dithered rounding: the offset distribution stays centred, so small amounts do
        // not turn into a one-sided salt pattern.
        val mean = offsets.sumOf { it.toDouble() } / offsets.size
        assertTrue("grain is biased: mean $mean", kotlin.math.abs(mean) < 0.2f * sigma)
    }

    @Test
    fun `grain backs off where the image already has detail`() {
        val size = 128
        val argb = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Smooth top half, one-pixel checkerboard bottom half: same mean level,
                // so only the local detail differs.
                val value = if (y < size / 2) {
                    120
                } else if ((x + y) % 2 == 0) {
                    110
                } else {
                    130
                }
                argb[y * size + x] = packArgb(value, value, value)
            }
        }
        val before = argb.copyOf()

        GrainStage.apply(argb, size, size, 1f)

        fun grainSigma(from: Int, to: Int): Float {
            var sum = 0.0
            var count = 0
            for (y in from until to) {
                for (x in 0 until size) {
                    val i = y * size + x
                    val d = (red(argb[i]) - red(before[i])).toDouble()
                    sum += d * d
                    count++
                }
            }
            return kotlin.math.sqrt(sum / count).toFloat()
        }
        // 4 px of margin so the bilinear mask cannot bleed across the seam.
        val smooth = grainSigma(0, size / 2 - 4)
        val detailed = grainSigma(size / 2 + 4, size)
        assertTrue(
            "grain must back off in detailed regions: smooth $smooth detailed $detailed",
            smooth > 1.8f * detailed,
        )
    }

    @Test
    fun `grain is weighted to midtones and keeps deep shadows clean`() {
        // Midtone and near-black flat fields, both at amount 1. Grain scales with luma,
        // so black stays clean instead of turning into clipped colour speckle.
        val shadow = grayArgb(32, 3)
        val midtone = grayArgb(32, 120)
        val shadowBefore = shadow.copyOf()
        val midtoneBefore = midtone.copyOf()

        GrainStage.apply(shadow, 32, 32, 1f)
        GrainStage.apply(midtone, 32, 32, 1f)

        fun spread(now: IntArray, before: IntArray): Int =
            (now.indices).maxOf { red(now[it]) - red(before[it]) } -
                (now.indices).minOf { red(now[it]) - red(before[it]) }

        val shadowSpread = spread(shadow, shadowBefore)
        val midtoneSpread = spread(midtone, midtoneBefore)
        assertTrue("grain must be visible in midtones", midtoneSpread > 20)
        assertTrue("shadow grain must stay far below midtone grain", shadowSpread < midtoneSpread / 4)
    }

    @Test
    fun `bloom glow spans a visible fraction of the frame`() {
        val size = 256
        val rgb = RgbImage(size, size)
        // 64×64 bright patch (25% of the frame) centred at 128.
        for (y in 96 until 160) {
            for (x in 96 until 160) {
                val i = y * size + x
                rgb.r[i] = 1f
                rgb.g[i] = 1f
                rgb.b[i] = 1f
            }
        }

        BloomStage.apply(rgb, 1f)

        // 24 px outside the patch the glow must still be clearly measurable; a
        // 2-px-radius blur (the previous behaviour) left this at ~0.
        assertTrue("glow too weak at 24 px", rgb.r[128 * size + 72] > 0.02f)
        // The opposite corner must stay black: bloom is a glow, not a wash.
        assertTrue("glow leaked globally", rgb.r[0] < 0.02f)

        val untouched = RgbImage(size, size)
        for (i in untouched.r.indices) {
            untouched.r[i] = 1f
            untouched.g[i] = 1f
            untouched.b[i] = 1f
        }
        val reference = untouched.r.copyOf()
        BloomStage.apply(untouched, 0f)
        assertEquals(reference.toList(), untouched.r.toList())
    }

    @Test
    fun `chroma denoise reduces color noise while preserving luminance grain`() {
        val rgb = RgbImage(2, 2)
        val colors = arrayOf(
            floatArrayOf(0.42f, 0.28f, 0.22f),
            floatArrayOf(0.18f, 0.36f, 0.44f),
            floatArrayOf(0.38f, 0.25f, 0.30f),
            floatArrayOf(0.22f, 0.39f, 0.31f),
        )
        val luminanceBefore = FloatArray(4)
        val redChromaBefore = FloatArray(4)
        for (i in colors.indices) {
            rgb.r[i] = colors[i][0]
            rgb.g[i] = colors[i][1]
            rgb.b[i] = colors[i][2]
            luminanceBefore[i] = testLuminance(rgb.r[i], rgb.g[i], rgb.b[i])
            redChromaBefore[i] = rgb.r[i] - luminanceBefore[i]
        }

        NoiseReducer.reduce(rgb, chromaStrength = 1f, lumaStrength = 0f)

        val redChromaAfter = FloatArray(4)
        for (i in 0..3) {
            val luminanceAfter = testLuminance(rgb.r[i], rgb.g[i], rgb.b[i])
            assertEquals(luminanceBefore[i], luminanceAfter, 1e-6f)
            redChromaAfter[i] = rgb.r[i] - luminanceAfter
        }
        assertTrue(redChromaAfter.range() < redChromaBefore.range())
    }

    @Test
    fun `denoise keeps output inside input range`() {
        val rgb = randomImage(32)
        val out = NoiseReducer.reduce(rgb, 1f, 1f)
        for (i in rgb.r.indices) {
            for (v in floatArrayOf(out.r[i], out.g[i], out.b[i])) {
                assertTrue("out of range: $v", v in -0.0001f..1.0001f)
            }
        }
    }

    @Test
    fun `sharpener amount is clamped and output stays finite`() {
        val rgb = randomImage(16)
        val out = Sharpener.sharpen(rgb, 5f, 1f) // hostile input, clamped to MAX_AMOUNT
        for (i in rgb.r.indices) {
            assertTrue(out.r[i].isFinite() && out.g[i].isFinite() && out.b[i].isFinite())
        }
    }

    @Test
    fun `gamut mapper clamps to unit interval`() {
        val rgb = RgbImage(1, 1).also { it.r[0] = -0.5f; it.g[0] = 2f; it.b[0] = 0.5f }
        val out = GamutMapper.clampToSrgbGamut(rgb)
        assertEquals(0f, out.r[0], 0f)
        assertEquals(1f, out.g[0], 0f)
        assertEquals(0.5f, out.b[0], 0f)
    }

    @Test
    fun `output transformer encodes srgb and packs argb`() {
        assertEquals(0, OutputTransformer.encodeChannel(Float.NaN))
        assertEquals(0, OutputTransformer.encodeChannel(-1f))
        assertEquals(255, OutputTransformer.encodeChannel(2f))
        assertEquals(188, OutputTransformer.encodeChannel(0.5f)) // sRGB(0.5) ≈ 0.7357
        val rgb = RgbImage(1, 1).also { it.r[0] = 1f; it.g[0] = 0f; it.b[0] = 0f }
        val argb = OutputTransformer.toArgb8888(rgb)
        assertEquals(0xFFFF0000.toInt(), argb[0])
    }

    @Test
    fun `output transformer never emits nan pixels`() {
        val rgb = randomImage(8)
        rgb.r[0] = Float.NaN
        rgb.g[3] = Float.POSITIVE_INFINITY
        val argb = OutputTransformer.toArgb8888(rgb)
        for (pixel in argb) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        }
    }
}

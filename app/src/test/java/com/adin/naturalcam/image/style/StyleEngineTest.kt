package com.adin.naturalcam.image.style

import com.adin.naturalcam.domain.StylePresets
import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StyleState

import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.core.SATURATION_RANGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleEngineTest {

    private fun uniformImage(size: Int = 256, value: Float = 0.30f): RgbImage =
        RgbImage(size, size).also { rgb ->
            for (i in rgb.r.indices) {
                rgb.r[i] = value
                rgb.g[i] = value
                rgb.b[i] = value
            }
        }

    /** Deterministic pseudo-random image in [0,1] (fixed LCG seed). */
    private fun randomImage(size: Int): RgbImage {
        val rgb = RgbImage(size, size)
        var seed = 987654321L
        for (i in rgb.r.indices) {
            for (channel in arrayOf(rgb.r, rgb.g, rgb.b)) {
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                channel[i] = (seed % 1000) / 1000f
            }
        }
        return rgb
    }

    /** Mean absolute horizontal step: a proxy for high-frequency noise energy. */
    private fun fineEnergy(channel: FloatArray, width: Int): Float {
        var sum = 0f
        for (i in 0 until channel.size - 1) {
            if (i % width == width - 1) continue
            sum += abs(channel[i + 1] - channel[i])
        }
        return sum / channel.size
    }

    private fun colorImage(size: Int = 256): RgbImage = RgbImage(size, size).also { rgb ->
        for (i in rgb.r.indices) {
            val f = i.toFloat() / rgb.r.size
            rgb.r[i] = 0.10f + 0.5f * f
            rgb.g[i] = 0.30f + 0.2f * f
            rgb.b[i] = 0.55f - 0.30f * f
        }
    }

    @Test
    fun `shape is nonlinear with fine response near center`() {
        assertEquals(0.09f, StyleEngine.shape(0.3f), 1e-6f)
        assertEquals(1f, StyleEngine.shape(1f), 1e-6f)
        assertEquals(-0.81f, StyleEngine.shape(-0.9f), 1e-6f)
        assertTrue(abs(StyleEngine.shape(0.05f)) < 0.05f)
    }

    @Test
    fun `center pads add no tone or color change`() {
        // Uniform input isolates the pads: the style-chain denoise is an exact
        // identity on a flat field, so any difference here would be a pad delta.
        val rgb = RgbImage(64, 64).also { image ->
            for (i in image.r.indices) {
                image.r[i] = 0.24f
                image.g[i] = 0.31f
                image.b[i] = 0.37f
            }
        }
        val before = Triple(rgb.r.copyOf(), rgb.g.copyOf(), rgb.b.copyOf())
        StyleEngine.apply(rgb, StyleState())
        assertEquals(before.first.toList(), rgb.r.toList())
        assertEquals(before.second.toList(), rgb.g.toList())
        assertEquals(before.third.toList(), rgb.b.toList())
    }

    @Test
    fun `saturation scales chroma about luma in both directions`() {
        // colorImage is deterministic, so each render starts from the same pixels.
        // Strength 0 keeps the chain's denoise out of the way: saturation is
        // independent of strength, so this isolates the control under test.
        val neutral = colorImage(64)
        val boosted = colorImage(64).also {
            StyleEngine.apply(it, StyleState(saturation = 0.5f, strength = 0f))
        }
        val reduced = colorImage(64).also {
            StyleEngine.apply(it, StyleState(saturation = -0.5f, strength = 0f))
        }

        fun chroma(image: RgbImage, i: Int): Triple<Float, Float, Float> {
            val l = 0.2126f * image.r[i] + 0.7152f * image.g[i] + 0.0722f * image.b[i]
            return Triple(image.r[i] - l, image.g[i] - l, image.b[i] - l)
        }
        fun chromaEnergy(image: RgbImage): Float {
            var sum = 0.0
            for (i in image.r.indices) {
                val (cr, cg, cb) = chroma(image, i)
                sum += (cr * cr + cg * cg + cb * cb).toDouble()
            }
            return kotlin.math.sqrt(sum / image.r.size).toFloat()
        }

        val base = chromaEnergy(neutral)
        val more = chromaEnergy(boosted)
        val less = chromaEnergy(reduced)
        assertTrue("saturation +0.5 must add chroma: $base -> $more", more > base * 1.2f)
        assertTrue("saturation -0.5 must remove chroma: $base -> $less", less < base * 0.85f)

        // One factor on the chroma offset: the colour direction is preserved exactly.
        // Checked on the reducing side, where channels move toward gray and therefore
        // cannot hit the zero floor; near-neutral pixels are skipped because their
        // chroma is at float noise level.
        val scale = 1f - SATURATION_RANGE * 0.5f
        for (i in 0 until neutral.r.size step 97) {
            val (br, bg, _) = chroma(neutral, i)
            val (ar, ag, _) = chroma(reduced, i)
            if (abs(br) > 0.02f) assertEquals(scale, ar / br, 2e-3f)
            if (abs(bg) > 0.02f) assertEquals(scale, ag / bg, 2e-3f)
        }
    }

    @Test
    fun `boosting saturation does not amplify chroma noise`() {
        val size = 64
        // Flat teal with per-pixel chroma noise on R and B, so the local R-G and B-G
        // deviation is exactly the colour noise a saturation boost would magnify.
        fun noisyFlat(): RgbImage {
            val rgb = RgbImage(size, size)
            var seed = 424242L
            for (i in rgb.r.indices) {
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                val noise = ((seed % 1000) / 1000f - 0.5f) * 0.06f
                rgb.r[i] = 0.34f + noise
                rgb.g[i] = 0.30f
                rgb.b[i] = 0.34f + noise
            }
            return rgb
        }

        fun chromaNoise(image: RgbImage): Float {
            // Mean absolute horizontal step of R-G: pure noise, no scene gradient.
            var sum = 0.0
            var count = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width - 1) {
                    val a = image.r[y * size + x] - image.g[y * size + x]
                    val b = image.r[y * size + x + 1] - image.g[y * size + x + 1]
                    sum += abs(a - b).toDouble()
                    count++
                }
            }
            return (sum / count).toFloat()
        }

        fun chromaLevel(image: RgbImage): Float {
            var sum = 0.0
            for (i in image.r.indices) sum += (image.r[i] - image.g[i]).toDouble()
            return (sum / image.r.size).toFloat()
        }

        val natural = noisyFlat()
        StyleEngine.apply(natural, StyleState())
        val boosted = noisyFlat()
        StyleEngine.apply(boosted, StyleState(saturation = 1f))

        val baseNoise = chromaNoise(natural)
        val boostedNoise = chromaNoise(boosted)
        assertTrue(
            "boosted chroma noise grew: $baseNoise -> $boostedNoise",
            boostedNoise <= 1.15f * baseNoise,
        )
        assertTrue(
            "saturation boost must still add colour: ${chromaLevel(natural)} -> ${chromaLevel(boosted)}",
            chromaLevel(boosted) > 1.2f * chromaLevel(natural),
        )
    }

    @Test
    fun `saturation leaves neutral gray neutral`() {
        val gray = uniformImage(value = 0.4f)
        val before = gray.r.copyOf()
        StyleEngine.apply(gray, StyleState(saturation = 1f))
        assertEquals(before.toList(), gray.r.toList())
        val again = uniformImage(value = 0.4f)
        StyleEngine.apply(again, StyleState(saturation = -1f))
        assertEquals(before.toList(), again.r.toList())
    }

    @Test
    fun `saturation is independent of the pad style and of strength`() {
        for (preset in StylePresets.entries) {
            assertEquals(0f, preset.state.saturation, 0f)
        }
        val natural = StylePresets.entries.single { it.id == "natural" }
        assertTrue(natural.matches(StyleState(saturation = 1f, strength = 0.4f)))

        val image = colorImage(64)
        val before = image.r.copyOf()
        StyleEngine.apply(image, StyleState(saturation = 1f, strength = 0f))
        assertTrue("saturation must survive strength 0", image.r.indices.any { image.r[it] != before[it] })
    }

    @Test
    fun `style chain denoises at strength above zero and not at zero`() {
        val noisy = randomImage(64)
        val reference = Triple(noisy.r.copyOf(), noisy.g.copyOf(), noisy.b.copyOf())

        StyleEngine.apply(noisy, StyleState())
        val after = Triple(noisy.r.copyOf(), noisy.g.copyOf(), noisy.b.copyOf())
        assertTrue(
            "style chain must reduce pixel-to-pixel noise",
            fineEnergy(after.first, 64) < 0.85f * fineEnergy(reference.first, 64),
        )

        val untouched = randomImage(64)
        val zeroReference = untouched.r.copyOf()
        StyleEngine.apply(untouched, StyleState(tone = StylePoint(0.8f, -0.8f), strength = 0f))
        assertEquals(zeroReference.toList(), untouched.r.toList())
    }

    @Test
    fun `strength zero equals natural`() {
        val rgb = colorImage()
        val before = Triple(rgb.r.copyOf(), rgb.g.copyOf(), rgb.b.copyOf())
        StyleEngine.apply(rgb, StyleState(tone = StylePoint(0.8f, -0.8f), color = StylePoint(0.7f, 0.7f), strength = 0f))
        assertEquals(before.first.toList(), rgb.r.toList())
        assertEquals(before.second.toList(), rgb.g.toList())
        assertEquals(before.third.toList(), rgb.b.toList())
    }

    @Test
    fun `strength interpolates monotonically toward styled value`() {
        val styled = StyleState(tone = StylePoint(0f, -1f), strength = 1f)
        val half = StyleState(tone = StylePoint(0f, -1f), strength = 0.5f)
        val params = StyleEngine.resolve(styled)
        val paramsHalf = StyleEngine.resolve(half)
        // Half strength moves exactly half the parameter delta.
        assertEquals(params.midtoneLift * 0.5f, paramsHalf.midtoneLift, 1e-6f)
    }

    @Test
    fun `bloom is independent of the pad style and of strength`() {
        // Bloom is not a preset: every named style leaves it untouched...
        for (preset in StylePresets.entries) {
            assertEquals(0f, preset.state.bloom, 0f)
        }
        // ...and a chip stays selected while bloom changes.
        val natural = StylePresets.entries.single { it.id == "natural" }
        assertTrue(natural.matches(StyleState(bloom = 1f, strength = 0.4f)))
        assertTrue(!natural.matches(StyleState(tone = StylePoint(0.5f, 0f))))

        // Bloom still applies when the pad style is switched off entirely.
        val bright = RgbImage(64, 64)
        for (i in bright.r.indices) {
            bright.r[i] = 1f
            bright.g[i] = 1f
            bright.b[i] = 1f
        }
        val reference = bright.r.copyOf()
        StyleEngine.apply(bright, StyleState(strength = 0f))
        assertEquals(reference.toList(), bright.r.toList())

        StyleEngine.apply(bright, StyleState(bloom = 1f, strength = 0f))
        assertTrue("bloom must survive strength 0", bright.r[32] > reference[32])
    }

    @Test
    fun `grain is not part of the style chain`() {
        // Grain is delivered-pixel work, applied by the pipeline after the output
        // transform; the style chain itself must not touch the working image for it.
        for (preset in StylePresets.entries) {
            assertEquals(0f, preset.state.grain, 0f)
        }
        val natural = StylePresets.entries.single { it.id == "natural" }
        assertTrue(natural.matches(StyleState(grain = 1f, strength = 0.4f)))

        val flat = uniformImage(value = 0.30f)
        val reference = flat.r.copyOf()
        StyleEngine.apply(flat, StyleState(grain = 1f, strength = 0f))
        assertEquals(reference.toList(), flat.r.toList())

        val styled = uniformImage(value = 0.30f)
        val unchanged = styled.r.copyOf()
        StyleEngine.apply(styled, StyleState(grain = 1f))
        assertEquals(unchanged.toList(), styled.r.toList())
    }
    @Test
    fun `neutral gray stays neutral under tone and color`() {
        val rgb = uniformImage(value = 0.35f)
        val out = StyleEngine.apply(
            rgb,
            StyleState(tone = StylePoint(0.6f, 0.4f), color = StylePoint(-0.8f, 0.3f), palette = StylePoint(0.8f, 0.8f)),
        )
        // Neutrality = channels stay equal; level may move with tone. (STYLE_PLAN 47)
        for (i in out.r.indices) {
            assertEquals(out.r[i], out.g[i], 1e-4f)
            assertEquals(out.g[i], out.b[i], 1e-4f)
        }
    }

    @Test
    fun `all outputs remain finite and non-negative`() {
        val out = StyleEngine.apply(
            colorImage(),
            StyleState(tone = StylePoint(1f, -1f), color = StylePoint(1f, 1f), palette = StylePoint(1f, -1f)),
        )
        for (i in out.r.indices) {
            assertTrue(out.r[i].isFinite() && out.r[i] >= 0f)
            assertTrue(out.g[i].isFinite() && out.g[i] >= 0f)
            assertTrue(out.b[i].isFinite() && out.b[i] >= 0f)
        }
    }
}

private fun abs(v: Float): Float = kotlin.math.abs(v)

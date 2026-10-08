package com.adin.naturalcam.image

import com.adin.naturalcam.image.core.LensShadingMap
import com.adin.naturalcam.image.core.RgbImage
import com.adin.naturalcam.image.processing.ChromaSmoother
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The shading-scaled chroma smoothing that pays for the luminance vignette
 * correction (natural-v26). Two invariants matter: luma must come through
 * untouched (the stage must not soften the image), and where the correction did
 * nothing the stage must do nothing.
 */
class ChromaSmootherTest {

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun map(gain: Float, size: Int) =
        LensShadingMap(2, 2, size, size, arrayOf(FloatArray(4) { gain }))

    private fun spread(image: RgbImage): Float {
        var sum = 0.0
        for (i in image.r.indices) {
            val chroma = (image.r[i] - image.g[i]).toDouble()
            sum += chroma * chroma
        }
        return sqrt(sum / image.r.size).toFloat()
    }

    @Test
    fun `luma is preserved exactly`() {
        val image = TestImages.checkerboard(8)
        val before = FloatArray(64) { luma(image.r[it], image.g[it], image.b[it]) }
        ChromaSmoother.apply(image, map(4f, 8))
        for (i in 0 until 64) {
            assertTrue(
                "pixel $i luma changed",
                kotlin.math.abs(before[i] - luma(image.r[i], image.g[i], image.b[i])) < 1e-6f,
            )
        }
    }

    @Test
    fun `a shading gain of one is an exact no-op`() {
        val image = TestImages.checkerboard(8)
        val before = image.r.copyOf()
        ChromaSmoother.apply(image, map(1f, 8))
        assertArrayEquals(before, image.r, 0f)
    }

    @Test
    fun `chroma spread collapses where the shading amplified`() {
        val image = TestImages.checkerboard(8)
        val before = spread(image)
        ChromaSmoother.apply(image, map(4f, 8))
        assertTrue("spread ${spread(image)} not below $before", spread(image) < before * 0.5f)
    }

    @Test
    fun `a base strength denoises chroma even where the shading did nothing`() {
        val image = TestImages.checkerboard(8)
        val lumaBefore = FloatArray(64) { luma(image.r[it], image.g[it], image.b[it]) }
        val before = spread(image)
        // A gain-1 map means the shading term is zero everywhere, so only the base acts.
        ChromaSmoother.apply(image, map(1f, 8), baseStrength = 0.7f)
        assertTrue("chroma spread ${spread(image)} not below $before", spread(image) < before * 0.6f)
        for (i in 0 until 64) {
            assertTrue(kotlin.math.abs(lumaBefore[i] - luma(image.r[i], image.g[i], image.b[i])) < 1e-6f)
        }
    }

    @Test
    fun `the edge gate reacts to structure, not to the guide's noise`() {
        // Two flat halves carrying chroma noise: (a) no step, (b) a luma step below the
        // edge threshold, (c) a real edge that also carries a chroma difference. The gate
        // must treat (b) exactly like (a) — its own noise is not structure — and must keep
        // (c)'s chroma instead of washing it across the edge. A threshold taken from the
        // frame's median noise instead cost +9…15% residual midtone chroma HF on device
        // DNGs, because that noise is not uniform: corner shading amplifies it.
        fun render(left: Triple<Float, Float, Float>, right: Triple<Float, Float, Float>): Float {
            val rgb = RgbImage(16, 16)
            var seed = 4242L
            for (i in rgb.r.indices) {
                seed = (seed * 1103515245 + 12345) and 0x7FFFFFFF
                val noise = ((seed % 1000) / 1000f - 0.5f) * 0.08f
                val side = if (i % rgb.width < 8) left else right
                rgb.r[i] = side.first + noise
                rgb.g[i] = side.second
                rgb.b[i] = side.third + noise
            }
            val before = spread(rgb)
            ChromaSmoother.apply(rgb, map(1f, 16), baseStrength = 0.7f)
            return spread(rgb) / before
        }

        val uniform = render(Triple(0.30f, 0.30f, 0.30f), Triple(0.30f, 0.30f, 0.30f))
        val smallStep = render(Triple(0.30f, 0.30f, 0.30f), Triple(0.35f, 0.35f, 0.35f))
        val realEdge = render(Triple(0.30f, 0.24f, 0.24f), Triple(0.80f, 0.86f, 0.86f))

        assertTrue("chroma noise must collapse: $uniform", uniform < 0.6f)
        assertTrue(
            "a 0.05 luma step is noise, not structure: $smallStep against $uniform",
            kotlin.math.abs(smallStep - uniform) < 0.05f,
        )
        assertTrue(
            "a real edge must keep its chroma: $realEdge against $smallStep",
            realEdge > smallStep * 1.5f,
        )
    }

    @Test
    fun `edge-aware chroma keeps separated bright color edges`() {
        val image = RgbImage(16, 8)
        for (i in image.r.indices) {
            val x = i % image.width
            if (x < image.width / 2) {
                image.r[i] = 0.35f
                image.g[i] = 0.20f
                image.b[i] = 0.20f
            } else {
                image.r[i] = 0.70f
                image.g[i] = 0.85f
                image.b[i] = 0.85f
            }
        }

        ChromaSmoother.apply(image, map(1f, image.width), baseStrength = 1f)

        val left = image.r[6] - image.g[6]
        val right = image.r[9] - image.g[9]
        assertTrue("left chroma edge bled away: $left", left > 0.10f)
        assertTrue("right chroma edge bled away: $right", right < -0.10f)
    }

    @Test
    fun `a map for another frame is ignored`() {
        val image = TestImages.checkerboard(8)
        val before = image.r.copyOf()
        ChromaSmoother.apply(image, LensShadingMap(2, 2, 4, 4, arrayOf(FloatArray(4) { 4f })))
        assertArrayEquals(before, image.r, 0f)
    }
}

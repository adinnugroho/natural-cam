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
    fun `a map for another frame is ignored`() {
        val image = TestImages.checkerboard(8)
        val before = image.r.copyOf()
        ChromaSmoother.apply(image, LensShadingMap(2, 2, 4, 4, arrayOf(FloatArray(4) { 4f })))
        assertArrayEquals(before, image.r, 0f)
    }
}

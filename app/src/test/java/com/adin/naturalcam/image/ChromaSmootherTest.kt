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

    /** Chroma checkerboard on a flat luma — the pixel-scale chroma the correction amplifies. */
    private fun checkerboard(size: Int): RgbImage {
        val image = RgbImage(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val swing = if ((x + y) and 1 == 0) 0.2f else -0.2f
            image.r[i] = 0.5f + swing
            image.g[i] = 0.5f
            image.b[i] = 0.5f - swing
        }
        return image
    }

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
        val image = checkerboard(8)
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
        val image = checkerboard(8)
        val before = image.r.copyOf()
        ChromaSmoother.apply(image, map(1f, 8))
        assertArrayEquals(before, image.r, 0f)
    }

    @Test
    fun `chroma spread collapses where the shading amplified`() {
        val image = checkerboard(8)
        val before = spread(image)
        ChromaSmoother.apply(image, map(4f, 8))
        assertTrue("spread ${spread(image)} not below $before", spread(image) < before * 0.5f)
    }

    @Test
    fun `a map for another frame is ignored`() {
        val image = checkerboard(8)
        val before = image.r.copyOf()
        ChromaSmoother.apply(image, LensShadingMap(2, 2, 4, 4, arrayOf(FloatArray(4) { 4f })))
        assertArrayEquals(before, image.r, 0f)
    }
}

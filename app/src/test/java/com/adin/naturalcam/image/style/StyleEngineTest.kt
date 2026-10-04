package com.adin.naturalcam.image.style

import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.image.core.RgbImage
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
    fun `center pads are neutral`() {
        val rgb = colorImage()
        val before = Triple(rgb.r.copyOf(), rgb.g.copyOf(), rgb.b.copyOf())
        StyleEngine.apply(rgb, StyleState())
        assertEquals(before.first.toList(), rgb.r.toList())
        assertEquals(before.second.toList(), rgb.g.toList())
        assertEquals(before.third.toList(), rgb.b.toList())
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

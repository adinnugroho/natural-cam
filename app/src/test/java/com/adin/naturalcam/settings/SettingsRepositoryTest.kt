package com.adin.naturalcam.settings

import com.adin.naturalcam.domain.StylePoint
import com.adin.naturalcam.domain.StyleState
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `old style settings decode with bloom disabled`() {
        val style = decodeStyle("1|0.1|0.2|-0.1|0.3|0.0|-0.4|0.75")

        assertEquals(0f, style.bloom, 0f)
        assertEquals(0f, style.grain, 0f)
        assertEquals(0f, style.saturation, 0f)
        assertEquals(0.75f, style.strength, 0f)
    }

    @Test
    fun `saturation survives style encoding with its sign`() {
        val encoded = encodeStyle(StyleState(saturation = -0.6f, grain = 0.2f, strength = 0.7f))
        val decoded = decodeStyle(encoded)

        assertEquals(-0.6f, decoded.saturation, 0f)
        assertEquals(0.2f, decoded.grain, 0f)
        assertEquals(0.7f, decoded.strength, 0f)
    }

    @Test
    fun `bloom survives style encoding`() {
        val encoded = encodeStyle(StyleState(bloom = 0.65f, strength = 0.8f))

        assertEquals(0.65f, decodeStyle(encoded).bloom, 0f)
        assertEquals(0.8f, decodeStyle(encoded).strength, 0f)
    }

    @Test
    fun `grain survives style encoding`() {
        val encoded = encodeStyle(StyleState(grain = 0.45f, bloom = 0.2f, strength = 0.6f))
        val decoded = decodeStyle(encoded)

        assertEquals(0.45f, decoded.grain, 0f)
        assertEquals(0.2f, decoded.bloom, 0f)
        assertEquals(0.6f, decoded.strength, 0f)
    }

    @Test
    fun `missing and malformed style fall back to defaults`() {
        assertEquals(StyleState(), decodeStyle(null))
        assertEquals(StyleState(), decodeStyle("junk"))
    }

    @Test
    fun `out-of-range bloom and grain are clamped`() {
        val high = decodeStyle("1|0|0|0|0|0|0|1|5|5|5")
        assertEquals(1f, high.bloom, 0f)
        assertEquals(1f, high.grain, 0f)
        assertEquals(1f, high.saturation, 0f)

        val low = decodeStyle("1|0|0|0|0|0|0|1|-3|-3|-9")
        assertEquals(0f, low.bloom, 0f)
        assertEquals(0f, low.grain, 0f)
        assertEquals(-1f, low.saturation, 0f)
    }

    @Test
    fun `full style round trips including tone color and palette`() {
        val style = StyleState(
            tone = StylePoint(0.5f, -0.5f),
            color = StylePoint(0.2f, 0.4f),
            palette = StylePoint(-0.3f, 0.8f),
            strength = 0.9f,
            bloom = 0.3f,
            grain = 0.6f,
            saturation = -0.4f,
        )

        assertEquals(style, decodeStyle(encodeStyle(style)))
    }
}

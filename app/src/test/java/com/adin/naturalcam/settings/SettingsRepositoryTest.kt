package com.adin.naturalcam.settings

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
}

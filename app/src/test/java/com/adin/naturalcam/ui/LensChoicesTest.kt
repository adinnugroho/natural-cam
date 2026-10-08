package com.adin.naturalcam.ui

import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.LensOption
import com.adin.naturalcam.testing.testCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lens picker must offer the wide lens only when the camera really has one behind it
 * (a zoom range starting below 1), and must select it as a *preset* of the current camera
 * rather than pretending it is a separate camera.
 */
class LensChoicesTest {

    private val back = LensOption(CameraId("0"), "1×", LensFacing.BACK, isDefault = true, focalLengthMm = 4.84f)
    private val front = LensOption(CameraId("1"), "Front", LensFacing.FRONT, isDefault = false, focalLengthMm = 3.6f)
    private val lenses = listOf(back, front)

    @Test
    fun `wide preset appears when the camera can zoom out below one`() {
        val choices = lensChoices(lenses, testCapabilities(zoomRatioRange = 0.6f..20f), CameraId("0"), wideAngleActive = false)

        assertEquals(listOf("0.6X", "1X"), choices.map { it.label })
        assertTrue("the camera's own entry is selected at 1x", choices[1].selected)
        assertFalse(choices[0].selected)
        // Same camera: selecting the preset changes framing, it does not switch cameras.
        assertEquals(CameraId("0"), choices[0].cameraId)
        assertEquals(0.6f, choices[0].zoomRatio, 0.0001f)
        assertEquals(1f, choices[1].zoomRatio, 0.0001f)
    }

    @Test
    fun `no wide preset when the zoom range starts at one`() {
        val choices = lensChoices(lenses, testCapabilities(zoomRatioRange = 1f..10f), CameraId("0"), wideAngleActive = false)
        assertEquals(listOf("1X"), choices.map { it.label })
    }

    @Test
    fun `no wide preset when the camera reports no zoom range`() {
        val choices = lensChoices(lenses, testCapabilities(zoomRatioRange = null), CameraId("0"), wideAngleActive = false)
        assertEquals(listOf("1X"), choices.map { it.label })
    }

    @Test
    fun `the preset is selected while the framing is wider than one`() {
        val choices = lensChoices(lenses, testCapabilities(zoomRatioRange = 0.6f..20f), CameraId("0"), wideAngleActive = true)
        assertTrue(choices[0].selected)
        assertFalse("only one entry may read as selected", choices[1].selected)
    }

    @Test
    fun `only the current facing is offered and its own capabilities decide the preset`() {
        // Front camera: no wide lens behind it, so no preset even though the back one has it.
        val choices = lensChoices(lenses, testCapabilities(zoomRatioRange = 1f..4f), CameraId("1"), wideAngleActive = false)
        assertEquals(listOf("FRONT"), choices.map { it.label })
        assertTrue(choices.single().selected)
        assertEquals(1f, choices.single().zoomRatio, 0.0001f)
    }

    @Test
    fun `no wide preset when a wider camera can be selected instead`() {
        // 0.3x (shorter focal) is its own lens here, so the 1x camera must not also offer a
        // digital 0.6x preset: that framing is better served by the real wide camera, which
        // exposes RAW, while the zoom framing falls back to the YUV stream.
        val wide = LensOption(CameraId("3"), "0.3×", LensFacing.BACK, isDefault = false, focalLengthMm = 1.5f)
        val choices = lensChoices(
            listOf(wide, back, front),
            testCapabilities(cameraId = CameraId("0"), zoomRatioRange = 0.6f..20f),
            CameraId("0"),
            wideAngleActive = false,
        )
        assertEquals(listOf("0.3X", "1X"), choices.map { it.label })
        assertTrue("1X is still the selected entry", choices.first { it.label == "1X" }.selected)
    }

    @Test
    fun `a camera entry returns to that camera's own framing`() {
        // While the wide preset is active, picking the camera entry must ask for 1x again.
        val choices = lensChoices(lenses, testCapabilities(zoomRatioRange = 0.6f..20f), CameraId("0"), wideAngleActive = true)
        val cameraEntry = choices.first { it.label == "1X" }
        assertEquals(1f, cameraEntry.zoomRatio, 0.0001f)
        assertEquals(CameraId("0"), cameraEntry.cameraId)
    }
}

package com.adin.naturalcam.resolve

import com.adin.naturalcam.domain.CaptureLimitation
import com.adin.naturalcam.domain.CaptureSource
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.NoiseReductionMode
import com.adin.naturalcam.domain.PipelineType
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.testing.testCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePathResolverTest {

    @Test
    fun `natural prefers raw when available`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.NATURAL, RawMode.FINAL_ONLY)
        assertEquals(CaptureSource.RAW_SENSOR, plan.source)
        assertEquals(PipelineType.RAW_NATURAL, plan.pipeline)
        assertFalse(plan.saveRaw)
    }

    @Test
    fun `natural falls back to yuv when raw missing`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = false), ProcessingProfile.NATURAL, RawMode.FINAL_ONLY)
        assertEquals(CaptureSource.YUV, plan.source)
        assertEquals(PipelineType.YUV_NATURAL, plan.pipeline)
        assertTrue(CaptureLimitation.FALLBACK_TO_YUV in plan.limitations)
    }

    @Test
    fun `natural falls back to jpeg with honest limitations when only jpeg remains`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = false, yuv = false), ProcessingProfile.NATURAL, RawMode.RAW_AND_FINAL)
        assertEquals(CaptureSource.PROCESSED_JPEG, plan.source)
        assertTrue(CaptureLimitation.FALLBACK_TO_JPEG in plan.limitations)
        assertTrue(CaptureLimitation.RAW_NOT_AVAILABLE in plan.limitations)
        assertTrue(CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE in plan.limitations)
    }

    @Test
    fun `pure uses minimal raw development`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.PURE, RawMode.RAW_AND_FINAL)
        assertEquals(PipelineType.RAW_PURE_MINIMAL, plan.pipeline)
        assertTrue(plan.saveRaw)
    }

    @Test
    fun `a wider framing than 1x avoids raw and says why`() {
        // Below 1x the logical camera engages a wider physical camera, and RAW is not exposed
        // for it (SPEC 96), so a RAW plan would deliver the narrower main-sensor frame.
        for (profile in listOf(ProcessingProfile.NATURAL, ProcessingProfile.PURE)) {
            val plan = CapturePathResolver.resolve(
                testCapabilities(raw = true),
                profile,
                RawMode.FINAL_ONLY,
                zoomRatio = 0.6f,
            )
            assertEquals("$profile must not plan RAW below 1x", CaptureSource.YUV, plan.source)
            assertTrue(CaptureLimitation.RAW_NOT_AVAILABLE in plan.limitations)
            assertTrue(CaptureLimitation.FALLBACK_TO_YUV in plan.limitations)
        }
    }

    @Test
    fun `a wider framing keeps raw for a raw-only request`() {
        // The user asked for the RAW file itself, which exists only on the main camera; the
        // plan stays RAW and its framing caveat is already recorded as PREVIEW_MAY_DIFFER.
        val plan = CapturePathResolver.resolve(
            testCapabilities(raw = true),
            ProcessingProfile.NATURAL,
            RawMode.RAW_ONLY,
            zoomRatio = 0.6f,
        )
        assertEquals(CaptureSource.RAW_SENSOR, plan.source)
        assertTrue(plan.saveRaw)
    }

    @Test
    fun `zoom at or above 1x keeps the raw plan unchanged`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.NATURAL, RawMode.FINAL_ONLY, zoomRatio = 1f)
        assertEquals(CaptureSource.RAW_SENSOR, plan.source)
        assertFalse(CaptureLimitation.RAW_NOT_AVAILABLE in plan.limitations)
        val zoomedIn = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.NATURAL, RawMode.FINAL_ONLY, zoomRatio = 3f)
        assertEquals(CaptureSource.RAW_SENSOR, zoomedIn.source)
    }

    @Test
    fun `system uses processed jpeg and records uncontrolled isp`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.SYSTEM, RawMode.FINAL_ONLY)
        assertEquals(CaptureSource.PROCESSED_JPEG, plan.source)
        assertEquals(PipelineType.HARDWARE_PROCESSED, plan.pipeline)
        assertTrue(CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE in plan.limitations)
    }

    @Test
    fun `system with raw and final keeps raw save on raw-capable camera`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.SYSTEM, RawMode.RAW_AND_FINAL)
        assertTrue(plan.saveRaw)
        assertFalse(CaptureLimitation.RAW_NOT_AVAILABLE in plan.limitations)
    }

    @Test
    fun `isp request settles for minimal when off unsupported and says so`() {
        val plan = CapturePathResolver.resolve(
            testCapabilities(raw = true, noiseReductionModes = setOf(NoiseReductionMode.MINIMAL, NoiseReductionMode.HIGH_QUALITY), edgeModes = setOf(EdgeMode.HIGH_QUALITY)),
            ProcessingProfile.PURE,
            RawMode.FINAL_ONLY,
        )
        assertEquals(NoiseReductionMode.MINIMAL, plan.ispConfiguration.noiseReduction)
        assertEquals(EdgeMode.HIGH_QUALITY, plan.ispConfiguration.edgeMode)
        assertTrue(CaptureLimitation.MINIMAL_DENOISE_ONLY in plan.limitations)
        assertTrue(CaptureLimitation.MINIMAL_SHARPENING_ONLY in plan.limitations)
    }

    @Test
    fun `raw-only mode sets save raw`() {
        val plan = CapturePathResolver.resolve(testCapabilities(raw = true), ProcessingProfile.NATURAL, RawMode.RAW_ONLY)
        assertTrue(plan.saveRaw)
    }
}

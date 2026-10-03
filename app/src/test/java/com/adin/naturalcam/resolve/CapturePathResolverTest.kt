package com.adin.naturalcam.resolve

import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CapabilityConfidence
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.CaptureLimitation
import com.adin.naturalcam.domain.CaptureSource
import com.adin.naturalcam.domain.ControlSupport
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.HardwareLevel
import com.adin.naturalcam.domain.ImageSize
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.NoiseReductionMode
import com.adin.naturalcam.domain.PipelineType
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePathResolverTest {

    private fun capabilities(
        raw: Boolean,
        yuv: Boolean = true,
        jpeg: Boolean = true,
        nrModes: Set<NoiseReductionMode> = setOf(NoiseReductionMode.OFF, NoiseReductionMode.MINIMAL),
        edgeModes: Set<EdgeMode> = setOf(EdgeMode.OFF, EdgeMode.FAST),
    ) = CameraCapabilities(
        cameraId = CameraId("0"),
        lensFacing = LensFacing.BACK,
        logicalMultiCamera = false,
        physicalCameraIds = emptySet(),
        hardwareLevel = HardwareLevel.FULL,
        rawSupport = if (raw) ControlSupport(CapabilityConfidence.SUPPORTED) else ControlSupport(CapabilityConfidence.UNAVAILABLE),
        manualSensorSupport = ControlSupport(CapabilityConfidence.SUPPORTED),
        manualPostProcessingSupport = ControlSupport(CapabilityConfidence.SUPPORTED),
        noiseReductionModes = nrModes,
        edgeModes = edgeModes,
        supportedFormats = buildSet {
            if (raw) add(CaptureFormat.RAW_SENSOR)
            if (yuv) add(CaptureFormat.YUV_420_888)
            if (jpeg) add(CaptureFormat.JPEG)
        },
        resolutions = mapOf(CaptureFormat.JPEG to listOf(ImageSize(4000, 3000))),
        isoRange = 100..3200,
        exposureTimeRangeNs = 1_000_000L..1_000_000_000L,
        exposureCompensationEvRange = -2f..2f,
        exposureCompensationStepEv = 1f / 3f,
        minimumFocusDistanceDiopters = 10f,
        focalLengthsMm = listOf(5.4f),
        flashAvailable = true,
        opticalStabilizationSupported = true,
        sensorOrientation = 90,
    )

    @Test
    fun `natural prefers raw when available`() {
        val plan = CapturePathResolver.resolve(capabilities(raw = true), ProcessingProfile.NATURAL, RawMode.FINAL_ONLY)
        assertEquals(CaptureSource.RAW_SENSOR, plan.source)
        assertEquals(PipelineType.RAW_NATURAL, plan.pipeline)
        assertFalse(plan.saveRaw)
    }

    @Test
    fun `natural falls back to yuv when raw missing`() {
        val plan = CapturePathResolver.resolve(capabilities(raw = false), ProcessingProfile.NATURAL, RawMode.FINAL_ONLY)
        assertEquals(CaptureSource.YUV, plan.source)
        assertEquals(PipelineType.YUV_NATURAL, plan.pipeline)
        assertTrue(CaptureLimitation.FALLBACK_TO_YUV in plan.limitations)
    }

    @Test
    fun `natural falls back to jpeg with honest limitations when only jpeg remains`() {
        val plan = CapturePathResolver.resolve(capabilities(raw = false, yuv = false), ProcessingProfile.NATURAL, RawMode.RAW_AND_FINAL)
        assertEquals(CaptureSource.PROCESSED_JPEG, plan.source)
        assertTrue(CaptureLimitation.FALLBACK_TO_JPEG in plan.limitations)
        assertTrue(CaptureLimitation.RAW_NOT_AVAILABLE in plan.limitations)
        assertTrue(CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE in plan.limitations)
    }

    @Test
    fun `pure uses minimal raw development`() {
        val plan = CapturePathResolver.resolve(capabilities(raw = true), ProcessingProfile.PURE, RawMode.RAW_AND_FINAL)
        assertEquals(PipelineType.RAW_PURE_MINIMAL, plan.pipeline)
        assertTrue(plan.saveRaw)
    }

    @Test
    fun `system uses processed jpeg and records uncontrolled isp`() {
        val plan = CapturePathResolver.resolve(capabilities(raw = true), ProcessingProfile.SYSTEM, RawMode.FINAL_ONLY)
        assertEquals(CaptureSource.PROCESSED_JPEG, plan.source)
        assertEquals(PipelineType.HARDWARE_PROCESSED, plan.pipeline)
        assertTrue(CaptureLimitation.ISP_PROCESSING_UNCONTROLLABLE in plan.limitations)
    }

    @Test
    fun `system with raw and final keeps raw save on raw-capable camera`() {
        val plan = CapturePathResolver.resolve(capabilities(raw = true), ProcessingProfile.SYSTEM, RawMode.RAW_AND_FINAL)
        assertTrue(plan.saveRaw)
        assertFalse(CaptureLimitation.RAW_NOT_AVAILABLE in plan.limitations)
    }

    @Test
    fun `isp request settles for minimal when off unsupported and says so`() {
        val plan = CapturePathResolver.resolve(
            capabilities(raw = true, nrModes = setOf(NoiseReductionMode.MINIMAL, NoiseReductionMode.HIGH_QUALITY), edgeModes = setOf(EdgeMode.HIGH_QUALITY)),
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
        val plan = CapturePathResolver.resolve(capabilities(raw = true), ProcessingProfile.NATURAL, RawMode.RAW_ONLY)
        assertTrue(plan.saveRaw)
    }
}

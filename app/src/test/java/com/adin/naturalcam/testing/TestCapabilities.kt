package com.adin.naturalcam.testing

import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CapabilityConfidence
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.ControlSupport
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.HardwareLevel
import com.adin.naturalcam.domain.ImageSize
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.NoiseReductionMode

/**
 * One named-argument builder for the 21-field [CameraCapabilities] test fixture.
 * Defaults model a fully capable FULL-level back camera; pass only the fields a
 * test cares about.
 */
fun testCapabilities(
    cameraId: CameraId = CameraId("0"),
    lensFacing: LensFacing = LensFacing.BACK,
    logicalMultiCamera: Boolean = false,
    physicalCameraIds: Set<CameraId> = emptySet(),
    hardwareLevel: HardwareLevel = HardwareLevel.FULL,
    raw: Boolean = true,
    yuv: Boolean = true,
    jpeg: Boolean = true,
    manualSensorSupported: Boolean = true,
    manualPostProcessingSupported: Boolean = true,
    noiseReductionModes: Set<NoiseReductionMode> = setOf(NoiseReductionMode.OFF, NoiseReductionMode.MINIMAL),
    edgeModes: Set<EdgeMode> = setOf(EdgeMode.OFF, EdgeMode.FAST),
    resolutions: Map<CaptureFormat, List<ImageSize>> = mapOf(CaptureFormat.JPEG to listOf(ImageSize(4000, 3000))),
    isoRange: IntRange = 100..3200,
    exposureTimeRangeNs: LongRange = 1_000_000L..1_000_000_000L,
    exposureCompensationEvRange: ClosedFloatingPointRange<Float> = -2f..2f,
    exposureCompensationStepEv: Float = 1f / 3f,
    minimumFocusDistanceDiopters: Float = 10f,
    focalLengthsMm: List<Float> = listOf(5.4f),
    zoomRatioRange: ClosedFloatingPointRange<Float>? = null,
    flashAvailable: Boolean = true,
    opticalStabilizationSupported: Boolean = true,
    sensorOrientation: Int = 90,
) = CameraCapabilities(
    cameraId = cameraId,
    lensFacing = lensFacing,
    logicalMultiCamera = logicalMultiCamera,
    physicalCameraIds = physicalCameraIds,
    hardwareLevel = hardwareLevel,
    rawSupport = if (raw) ControlSupport(CapabilityConfidence.SUPPORTED) else ControlSupport(CapabilityConfidence.UNAVAILABLE),
    manualSensorSupport = ControlSupport(
        if (manualSensorSupported) CapabilityConfidence.SUPPORTED else CapabilityConfidence.UNAVAILABLE,
    ),
    manualPostProcessingSupport = ControlSupport(
        if (manualPostProcessingSupported) CapabilityConfidence.SUPPORTED else CapabilityConfidence.UNAVAILABLE,
    ),
    noiseReductionModes = noiseReductionModes,
    edgeModes = edgeModes,
    supportedFormats = buildSet {
        if (raw) add(CaptureFormat.RAW_SENSOR)
        if (yuv) add(CaptureFormat.YUV_420_888)
        if (jpeg) add(CaptureFormat.JPEG)
    },
    resolutions = resolutions,
    isoRange = isoRange,
    exposureTimeRangeNs = exposureTimeRangeNs,
    exposureCompensationEvRange = exposureCompensationEvRange,
    exposureCompensationStepEv = exposureCompensationStepEv,
    minimumFocusDistanceDiopters = minimumFocusDistanceDiopters,
    focalLengthsMm = focalLengthsMm,
    zoomRatioRange = zoomRatioRange,
    flashAvailable = flashAvailable,
    opticalStabilizationSupported = opticalStabilizationSupported,
    sensorOrientation = sensorOrientation,
)

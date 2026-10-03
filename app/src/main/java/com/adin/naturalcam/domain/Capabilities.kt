package com.adin.naturalcam.domain

/**
 * Capability truth model (SPEC 12, AGENTS 12): Android may advertise a control
 * that behaves inconsistently on real hardware. SUPPORTED = advertised by
 * metadata; CONFIRMED = verified working on device; UNAVAILABLE = absent;
 * UNKNOWN = not yet determined. Never present fictional controls (PRD 11).
 */
enum class CapabilityConfidence { SUPPORTED, CONFIRMED, UNAVAILABLE, UNKNOWN }

/** Honest per-control support, including "minimum available" style notes (PRD 11). */
data class ControlSupport(
    val confidence: CapabilityConfidence,
    val note: String? = null,
) {
    val usable: Boolean
        get() = confidence == CapabilityConfidence.SUPPORTED || confidence == CapabilityConfidence.CONFIRMED
}

enum class NoiseReductionMode { OFF, MINIMAL, FAST, HIGH_QUALITY, ZERO_SHUTTER_LAG, UNKNOWN }

enum class EdgeMode { OFF, FAST, HIGH_QUALITY, ZERO_SHUTTER_LAG, UNKNOWN }

enum class CaptureFormat { JPEG, YUV_420_888, RAW_SENSOR }

/**
 * Capabilities belong to one selected camera (AGENTS 10) and are discovered
 * from real metadata (AGENTS 9) — never inferred from brand or lens name.
 */
data class CameraCapabilities(
    val cameraId: CameraId,
    val lensFacing: LensFacing,
    val logicalMultiCamera: Boolean,
    val physicalCameraIds: Set<CameraId>,
    val hardwareLevel: HardwareLevel,
    val rawSupport: ControlSupport,
    val manualSensorSupport: ControlSupport,
    val manualPostProcessingSupport: ControlSupport,
    val noiseReductionModes: Set<NoiseReductionMode>,
    val edgeModes: Set<EdgeMode>,
    val supportedFormats: Set<CaptureFormat>,
    val resolutions: Map<CaptureFormat, List<ImageSize>>,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val exposureCompensationEvRange: ClosedFloatingPointRange<Float>?,
    val exposureCompensationStepEv: Float,
    val minimumFocusDistanceDiopters: Float?,
    val focalLengthsMm: List<Float>,
    val flashAvailable: Boolean,
    val opticalStabilizationSupported: Boolean,
    val sensorOrientation: Int,
) {
    val rawUsable: Boolean get() = rawSupport.usable && CaptureFormat.RAW_SENSOR in supportedFormats
    val yuvUsable: Boolean get() = CaptureFormat.YUV_420_888 in supportedFormats
    val jpegUsable: Boolean get() = CaptureFormat.JPEG in supportedFormats
    val exposureCompensationUsable: Boolean
        get() = exposureCompensationEvRange != null && !exposureCompensationEvRange.isEmpty()
}

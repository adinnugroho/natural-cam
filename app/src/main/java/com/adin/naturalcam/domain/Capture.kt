package com.adin.naturalcam.domain

/** Public processing profiles — strictly PURE / NATURAL / SYSTEM (PRD 7). Default NATURAL. */
enum class ProcessingProfile { PURE, NATURAL, SYSTEM }

/** RAW output modes (PRD 12). RAW is optional; normal usage does not require it. */
enum class RawMode { FINAL_ONLY, RAW_AND_FINAL, RAW_ONLY }

sealed interface ExposureMode {
    data object Auto : ExposureMode
    data class Manual(val iso: Int, val exposureTimeNs: Long) : ExposureMode
}

enum class FocusMode { CONTINUOUS, TAP_TO_FOCUS, LOCKED, MANUAL }

enum class FlashMode { OFF, AUTO, ON }

enum class FinalImageFormat { JPEG }

data class OutputSettings(
    val format: FinalImageFormat = FinalImageFormat.JPEG,
    val jpegQuality: Int = 92,
    val locationTagging: Boolean = false,
)

/** Where the pixels we process come from (SPEC 21). */
enum class CaptureSource { RAW_SENSOR, YUV, PROCESSED_JPEG }

/** Which concrete pipeline handles the capture (SPEC 19 — profile is intent, pipeline is tech). */
enum class PipelineType {
    RAW_NATURAL,
    YUV_NATURAL,
    RAW_PURE_MINIMAL,
    YUV_PURE_MINIMAL,
    HARDWARE_PROCESSED,
}

/** Honest limitations recorded per capture (AGENTS 12, 34). */
enum class CaptureLimitation {
    ISP_PROCESSING_UNCONTROLLABLE,
    MINIMAL_SHARPENING_ONLY,
    MINIMAL_DENOISE_ONLY,
    HDR_CONTROL_UNAVAILABLE,
    FALLBACK_TO_YUV,
    FALLBACK_TO_JPEG,
    RAW_NOT_AVAILABLE,
    PREVIEW_MAY_DIFFER,
}

/** Least-aggressive ISP request the resolver settled on (SPEC 23). */
data class IspConfiguration(
    val noiseReduction: NoiseReductionMode,
    val edgeMode: EdgeMode,
    val hdrDisabled: Boolean,
)

/** Concrete capture decision (SPEC 20). Built only by CapturePathResolver. */
data class CapturePlan(
    val source: CaptureSource,
    val pipeline: PipelineType,
    val saveRaw: Boolean,
    val ispConfiguration: IspConfiguration,
    val limitations: Set<CaptureLimitation>,
)

data class SavedPhoto(
    val uri: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

/** Only values actually known from capture metadata are populated (AGENTS 52). */
data class CaptureMetadata(
    val captureId: CaptureId,
    val timestampMs: Long,
    val orientationDegrees: Int,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float?,
    val focalLengthMm: Float?,
    val make: String?,
    val model: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
)

data class PhotoCaptureRequest(
    val captureId: CaptureId,
    val cameraId: CameraId,
    val profile: ProcessingProfile,
    val rawMode: RawMode,
    val exposureMode: ExposureMode,
    val focusMode: FocusMode,
    val flashMode: FlashMode,
    /** Normalized NATURAL white-balance temperature adjustment in [-1, 1]. */
    val temperature: Float = 0f,
    /** Selected creative style; consumed by NATURAL only (STYLE_PLAN 27/28). */
    val style: StyleState = StyleState(),
    val outputSettings: OutputSettings,
)

data class PhotoCaptureResult(
    val finalPhoto: SavedPhoto?,
    val rawPhoto: SavedPhoto?,
    val metadata: CaptureMetadata,
    val processingProfile: ProcessingProfile,
    val pipelineVersion: String,
    val limitations: Set<CaptureLimitation>,
)

/** Explicit capture state machine (SPEC 69). Failures are recoverable (AGENTS 40). */
sealed interface CaptureState {
    data object Idle : CaptureState
    data object Preparing : CaptureState
    data object Capturing : CaptureState
    data class Processing(val progress: Float?) : CaptureState
    data object Saving : CaptureState
    data class Complete(val result: SavedPhoto) : CaptureState
    data class Failed(val error: CameraError) : CaptureState
}

/** Explicit camera state (SPEC 17). Backend errors never leave UI believing camera is ready. */
sealed interface CameraState {
    data object Uninitialized : CameraState
    data object Initializing : CameraState
    data class Ready(val cameraId: CameraId) : CameraState
    data class Switching(val from: CameraId, val to: CameraId) : CameraState
    data class Error(val error: CameraError) : CameraState
}

package com.adin.naturalcam.domain

/**
 * Domain identifiers. Camera IDs are opaque strings — never assume numeric
 * ordering or that "0 = rear" (AGENTS 11).
 */
@JvmInline
value class CameraId(val value: String)

/** Unique identity of one shutter event; links final image, RAW, logs, jobs (SPEC 87). */
@JvmInline
value class CaptureId(val value: String)

enum class LensFacing { BACK, FRONT, EXTERNAL, UNKNOWN }

enum class HardwareLevel { LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN }

data class ImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "ImageSize must be positive: ${width}x$height" }
    }
}

/** Normalized view coordinates in [0,1]; conversion to metering coords is backend work (SPEC 60). */
data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "NormalizedPoint out of range: $x,$y" }
    }
}

/** User-facing lens entry built from real capabilities (SPEC 15). */
data class LensOption(
    val cameraId: CameraId,
    val label: String,
    val lensFacing: LensFacing,
    val isDefault: Boolean,
    /** Primary focal length in mm; used for pinch-zoom lens switching. */
    val focalLengthMm: Float = 1f,
)

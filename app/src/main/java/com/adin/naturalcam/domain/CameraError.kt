package com.adin.naturalcam.domain

/** Typed domain errors (SPEC 104). Unexpected exceptions map here after logging (AGENTS 56). */
sealed interface CameraError {
    val detail: String?

    data class PermissionDenied(override val detail: String? = null) : CameraError
    data class CameraUnavailable(override val detail: String? = null) : CameraError
    data class CameraDisconnected(override val detail: String? = null) : CameraError
    data class SessionConfigurationFailed(override val detail: String? = null) : CameraError
    data class UnsupportedConfiguration(override val detail: String? = null) : CameraError
    data class CaptureFailed(override val detail: String? = null) : CameraError
    data class RawUnavailable(override val detail: String? = null) : CameraError
    data class ProcessingFailed(override val detail: String? = null) : CameraError
    data class EncodingFailed(override val detail: String? = null) : CameraError
    data class StorageFailed(override val detail: String? = null) : CameraError
}

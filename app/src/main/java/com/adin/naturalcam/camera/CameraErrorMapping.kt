package com.adin.naturalcam.camera

import com.adin.naturalcam.domain.CameraError
import java.io.IOException

/**
 * The single exception → [CameraError] mapping (AGENTS 56).
 *
 * Every layer used to carry its own copy with a different catch-all, so one failure
 * surfaced as three different errors depending on where it was caught — the UI even
 * reported I/O and processing failures as "camera unavailable". Known failures keep
 * their type; anything else becomes [fallback], which the caller chooses because only
 * it knows what it was doing.
 *
 * Cancellation is deliberately not mapped: every `catch` that can see a
 * `CancellationException` rethrows it before calling this (AGENTS 56, structured
 * concurrency).
 */
fun Throwable.toCameraError(
    fallback: (String?) -> CameraError = { CameraError.CaptureFailed(it) },
): CameraError = when (this) {
    is CameraException -> error
    is IOException -> CameraError.StorageFailed(message)
    is IllegalArgumentException -> CameraError.SessionConfigurationFailed(message)
    else -> fallback(message ?: javaClass.simpleName)
}

package com.adin.naturalcam.camera.camerax

import androidx.camera.core.CameraInfo
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.LensOption
import java.util.Locale

/**
 * Lens list for the camera picker (SPEC 11). Labels are derived from reported
 * physical focal lengths, because no platform API exposes marketing lens names
 * (LIMITATIONS.md #4).
 *
 * Takes the already-scanned capabilities rather than scanning again: a scan is
 * dozens of blocking Camera2 metadata reads per camera.
 */
internal fun buildLensOptions(scanned: Map<CameraInfo, CameraCapabilities>): List<LensOption> {
    // Main camera heuristic: highest JPEG resolution among back cameras.
    val back = scanned.values.filter { it.lensFacing == LensFacing.BACK }
    val main = back.maxByOrNull { cap ->
        cap.resolutions[CaptureFormat.JPEG]?.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
    }
    val mainFocal = main?.focalLengthsMm?.maxOrNull()

    return scanned.values.map { cap ->
        val focal = cap.focalLengthsMm.maxOrNull()
        val label = when {
            cap.lensFacing == LensFacing.FRONT -> "Front"
            focal != null && mainFocal != null && mainFocal > 0f && cap.cameraId != main?.cameraId -> {
                val ratio = focal / mainFocal
                if (ratio < 1f) String.format(Locale.US, "%.1f×", ratio)
                else String.format(Locale.US, "%.0f×", ratio)
            }
            cap.cameraId == main?.cameraId -> "1×"
            cap.lensFacing == LensFacing.BACK -> "Back"
            else -> "Camera"
        }
        LensOption(
            cameraId = cap.cameraId,
            label = label,
            lensFacing = cap.lensFacing,
            isDefault = cap.cameraId == main?.cameraId ||
                (main == null && cap.cameraId == scanned.values.first().cameraId),
            focalLengthMm = focal ?: 1f,
        )
    }
}

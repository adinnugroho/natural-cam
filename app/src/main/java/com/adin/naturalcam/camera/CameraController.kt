package com.adin.naturalcam.camera

import androidx.camera.view.PreviewView
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CameraState
import com.adin.naturalcam.domain.CaptureId
import com.adin.naturalcam.domain.CaptureMetadata
import com.adin.naturalcam.domain.CapturePlan
import com.adin.naturalcam.domain.CaptureSource
import com.adin.naturalcam.domain.LensOption
import com.adin.naturalcam.domain.NormalizedPoint
import com.adin.naturalcam.domain.PhotoCaptureRequest
import com.adin.naturalcam.image.core.YuvImage
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Raw frames captured from the camera, before any processing or storage.
 * Exactly one of the payload fields is populated per [CapturePlan.source].
 */
class CapturedFrames(
    val captureId: CaptureId,
    val metadata: CaptureMetadata,
    /** Temp DNG file when source is RAW_SENSOR; caller owns deletion (AGENTS 51). */
    val rawDngFile: File?,
    /** Decoded-planes YUV when source is YUV. */
    val yuv: YuvImage?,
    /** Platform-processed JPEG when source is PROCESSED_JPEG (or RAW+JPEG secondary). */
    val jpegBytes: ByteArray?,
    /** Secondary HAL JPEG present alongside RAW (SYSTEM + RAW_AND_FINAL only). */
    val halJpegBytes: ByteArray?,
) {
    val source: CaptureSource
        get() = when {
            rawDngFile != null -> CaptureSource.RAW_SENSOR
            yuv != null -> CaptureSource.YUV
            else -> CaptureSource.PROCESSED_JPEG
        }
}

/**
 * Camera backend abstraction (SPEC 8). UI/feature code never touches
 * CameraDevice, use cases or CaptureRequest.Builder directly (AGENTS 15).
 *
 * [PreviewView] appears here because surface provisioning and metering-point
 * factories are view-level concerns owned by CameraX; session and capture
 * control remain exclusively inside the backend (ADR-001).
 */
interface CameraController {

    val state: StateFlow<CameraState>

    /** Capabilities per enumerated camera (AGENTS 10: per selected camera, from metadata). */
    val capabilities: Map<CameraId, CameraCapabilities>

    val lenses: List<LensOption>

    /** Enumerates cameras and scans capabilities. Returns the lens list. */
    suspend fun initialize(): List<LensOption>

    fun attachPreview(previewView: PreviewView)

    suspend fun selectCamera(cameraId: CameraId)

    suspend fun setAspectRatio(aspectRatio: com.adin.naturalcam.domain.AspectRatio)

    /** Applies the user's exposure compensation in EV units. */
    suspend fun setExposureCompensation(ev: Float)

    /** Applies the requested maximum-resolution policy to preview and capture. */
    suspend fun setHighestResolution(enabled: Boolean)

    /** Smooth zoom within the selected lens's optical/digital range (0 = reset). */
    suspend fun setZoom(zoomRatio: Float)

    /** The zoom the framing is currently composed with; below 1x a wider lens is engaged. */
    val zoomRatio: Float

    /** Tap-to-focus at normalized view coordinates; resolves crop/rotation/mirroring (SPEC 60). */
    suspend fun focusAt(point: NormalizedPoint)

    /** Locks AF/AE at the supplied normalized view coordinate until cancelled. */
    suspend fun lockFocus(point: NormalizedPoint)

    suspend fun unlockFocus()

    /** Captures frames according to [plan]; does not process or save. */
    suspend fun capture(request: PhotoCaptureRequest, plan: CapturePlan): CapturedFrames

    suspend fun close()
}

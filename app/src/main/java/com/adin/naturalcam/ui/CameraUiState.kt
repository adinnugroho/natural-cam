package com.adin.naturalcam.ui

import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CameraState
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.FlashMode
import com.adin.naturalcam.domain.LensOption
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.SavedPhoto
import com.adin.naturalcam.domain.StyleState

/** Immutable explicit UI state (AGENTS 39). */
data class CameraUiState(
    val cameraState: CameraState = CameraState.Uninitialized,
    val captureState: CaptureState = CaptureState.Idle,
    val lenses: List<LensOption> = emptyList(),
    val selectedCameraId: CameraId? = null,
    val capabilities: CameraCapabilities? = null,
    val profile: ProcessingProfile = ProcessingProfile.NATURAL,
    val rawMode: RawMode = RawMode.FINAL_ONLY,
    val flashMode: FlashMode = FlashMode.OFF,
    val exposureCompensationEv: Float = 0f,
    /**
     * True while the framing is wider than 1x, i.e. a wider lens is engaged behind the current
     * camera. Published only when the framing crosses 1x — every pinch frame would otherwise
     * recompose the whole camera screen.
     */
    val wideAngleActive: Boolean = false,
    val temperature: Float = 0f,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val highestResolution: Boolean = false,
    val timerSeconds: Int = 0,
    val gridEnabled: Boolean = false,
    /** Style workspace is open; not persisted across launches. */
    val styleMode: Boolean = false,
    val style: StyleState = StyleState(),
    val lastCapture: SavedPhoto? = null,
    val isShutterEnabled: Boolean = true,
    /** Accepted captures still developing; the shutter stays live while this is below the bound. */
    val jobsInFlight: Int = 0,
    val notice: UiNotice? = null,
)

data class UiNotice(val message: String, val isError: Boolean)

/**
 * Every UI event the camera surface can raise. Implemented by the ViewModel;
 * composables stay declarative (AGENTS 38).
 */
interface CameraActions {
    fun onShutter()
    fun onSelectLens(cameraId: CameraId, zoomRatio: Float = 1f)
    fun onSetFlash(mode: FlashMode)
    fun onSetExposureCompensation(ev: Float)
    fun onSetTemperature(temperature: Float)
    fun onSetStyle(style: StyleState)
    fun onSetRawMode(rawMode: RawMode)
    fun onSelectStylePreset(style: StyleState)
    /** Opens/closes the style workspace (used by the entry button and system back). */
    fun onSetStyleMode(enabled: Boolean)
    fun onSetAspectRatio(aspectRatio: AspectRatio)
    fun onSetHighestResolution(enabled: Boolean)
    /** xFraction/yFraction in view space [0,1]; ViewModel maps to metering points (SPEC 60). */
    fun onTapToFocus(xFraction: Float, yFraction: Float)
    fun onLockFocus(xFraction: Float, yFraction: Float)
    /** Continuous pinch zoom within the current lens (0 = reset to 1×). */
    fun onPinchZoom(zoomRatio: Float)
    fun onOpenGallery()
    fun onNoticeShown()
}

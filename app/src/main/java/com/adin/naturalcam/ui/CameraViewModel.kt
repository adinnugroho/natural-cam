package com.adin.naturalcam.ui

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adin.naturalcam.capture.CaptureCoordinator
import com.adin.naturalcam.camera.CameraController
import com.adin.naturalcam.camera.toCameraError
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraError
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CameraState
import com.adin.naturalcam.domain.CaptureId
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.ExposureMode
import com.adin.naturalcam.domain.FlashMode
import com.adin.naturalcam.domain.FocusMode
import com.adin.naturalcam.domain.NormalizedPoint
import com.adin.naturalcam.domain.OutputSettings
import com.adin.naturalcam.domain.PhotoCaptureRequest
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.StyleState
import com.adin.naturalcam.settings.SettingsRepository
import com.adin.naturalcam.storage.LatestPhotoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID

sealed interface VmEvent {
    data object RequestLocationPermission : VmEvent
    data class OpenGallery(val uri: String?) : VmEvent
}

/**
 * Binds UI state to the camera/capture domain (SPEC 6). Composables stay
 * dumb; camera sessions live in the controller (AGENTS 38).
 */
class CameraViewModel(
    private val controller: CameraController,
    private val coordinator: CaptureCoordinator,
    private val settingsRepository: SettingsRepository,
    private val latestPhotoReader: LatestPhotoReader,
) : ViewModel(), CameraActions {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VmEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VmEvent> = _events.asSharedFlow()

    /**
     * Weak: the ViewModel outlives the camera screen, so a strong reference would keep the
     * disposed PreviewView (and its activity context) alive until the ViewModel dies. Only
     * identity is ever read from it.
     */
    private var attachedPreviewView: WeakReference<PreviewView>? = null
    private var zoomJob: Job? = null
    private var lensSelectionJob: Job? = null
    private var initialized = false
    init {
        viewModelScope.launch {
            coordinator.captureState.collect { captureState ->
                _uiState.update {
                    it.copy(
                        captureState = captureState,
                        lastCapture = (captureState as? CaptureState.Complete)?.result ?: it.lastCapture,
                        notice = (captureState as? CaptureState.Failed)?.let { failed ->
                            UiNotice(message = failed.error.detail ?: failed.error.toString(), isError = true)
                        } ?: it.notice,
                    )
                }
                if (captureState is CaptureState.Complete) coordinator.acknowledge()
            }
        }
        // The shutter follows the queue, not the current phase: while an earlier photo
        // develops, the next shot is still allowed (up to the coordinator's bound).
        viewModelScope.launch {
            coordinator.jobsInFlight.collect { inFlight ->
                _uiState.update {
                    it.copy(
                        jobsInFlight = inFlight,
                        isShutterEnabled = inFlight < coordinator.maxConcurrentJobs,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                geotaggingEnabled = settings.geotagging
                _uiState.update {
                    it.copy(
                        profile = settings.profile,
                        rawMode = settings.rawMode,
                        flashMode = settings.flashMode,
                        aspectRatio = settings.aspectRatio,
                        highestResolution = settings.highestResolution,
                        temperature = settings.temperature,
                        timerSeconds = settings.timerSeconds,
                        gridEnabled = settings.gridEnabled,
                        style = settings.style,
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            latestPhotoReader.read()?.let { latest ->
                _uiState.update { state ->
                    if (state.lastCapture == null) state.copy(lastCapture = latest) else state
                }
            }
        }
    }

    /** Called once CAMERA permission is granted. */
    fun onCameraPermissionGranted() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            _uiState.update { it.copy(cameraState = CameraState.Initializing) }
            try {
                // The scan reads dozens of Camera2 metadata keys per camera; keep it off
                // the main thread (AGENTS 44: no blocking work on main).
                val lenses = withContext(Dispatchers.Default) { controller.initialize() }
                val default = lenses.firstOrNull { it.isDefault } ?: lenses.firstOrNull()
                _uiState.update {
                    it.copy(lenses = lenses, selectedCameraId = default?.cameraId)
                }
                default?.let { selectCameraInternal(it.cameraId) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(cameraState = CameraState.Error(e.toCameraError { CameraError.CameraUnavailable(it) }))
                }
            }
        }
    }

    /** Permission denied: the UI must show the limitation, not a dead preview (PRD 32). */
    fun onCameraPermissionDenied() {
        val error = CameraError.PermissionDenied("camera permission denied")
        _uiState.update {
            it.copy(
                cameraState = CameraState.Error(error),
                isShutterEnabled = false,
                notice = UiNotice("Camera permission denied — enable it in system settings", isError = true),
            )
        }
    }

    fun onPreviewView(previewView: PreviewView) {
        controller.attachPreview(previewView)
        if (attachedPreviewView?.get() === previewView) return

        // Navigation recreates PreviewView while the ViewModel survives. Rebind
        // the existing camera session to the new surface instead of leaving the
        // preview attached to the disposed camera screen.
        attachedPreviewView = WeakReference(previewView)
        val cameraId = _uiState.value.selectedCameraId ?: return
        if (initialized) {
            viewModelScope.launch { selectCameraInternal(cameraId) }
        }
    }

    private suspend fun selectCameraInternal(cameraId: CameraId) {
        try {
            controller.selectCamera(cameraId)
            _uiState.update {
                it.copy(
                    cameraState = CameraState.Ready(cameraId),
                    selectedCameraId = cameraId,
                    capabilities = controller.capabilities[cameraId],
                    exposureCompensationEv = 0f,
                    // A different camera starts at 1x, matching the controller's own reset.
                    wideAngleActive = false,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(cameraState = CameraState.Error(e.toCameraError { CameraError.CameraUnavailable(it) })) }
        }
    }

    // ---- CameraActions ----

    override fun onShutter() {
        val state = _uiState.value
        if (!state.isShutterEnabled) return
        val cameraId = state.selectedCameraId
        if (cameraId == null || state.cameraState is CameraState.Error) {
            // Bounded recovery: one re-initialization attempt per user action (AGENTS 57).
            initialized = false
            onCameraPermissionGranted()
            return
        }
        viewModelScope.launch {
            if (state.timerSeconds > 0) {
                for (remaining in state.timerSeconds downTo 1) {
                    _uiState.update { it.copy(notice = UiNotice("$remaining…", isError = false)) }
                    delay(1000)
                }
                _uiState.update { it.copy(notice = null) }
            }
            val request = PhotoCaptureRequest(
                captureId = CaptureId(UUID.randomUUID().toString()),
                cameraId = cameraId,
                profile = state.profile,
                rawMode = state.rawMode,
                exposureMode = ExposureMode.Auto,
                focusMode = FocusMode.CONTINUOUS,
                flashMode = state.flashMode,
                aspectRatio = state.aspectRatio,
                zoomRatio = controller.zoomRatio,
                temperature = state.temperature,
                style = state.style,
                outputSettings = OutputSettings(locationTagging = geotaggingEnabled),
            )
            coordinator.capture(request)
        }
    }

    override fun onSelectLens(cameraId: CameraId, zoomRatio: Float) {
        // Do not queue repeated physical-camera rebinds from a single pinch.
        if (lensSelectionJob?.isActive == true) return
        lensSelectionJob = viewModelScope.launch {
            // The same camera at a different ratio is the wide-angle preset (or a pinch that
            // crossed a lens boundary): a framing change, not a rebind.
            if (cameraId != _uiState.value.selectedCameraId) selectCameraInternal(cameraId)
            if (_uiState.value.selectedCameraId == cameraId) {
                controller.setZoom(zoomRatio)
                _uiState.update { it.copy(wideAngleActive = zoomRatio < 1f) }
            }
        }
    }

    fun onSelectProfile(profile: ProcessingProfile) {
        viewModelScope.launch { settingsRepository.setProfile(profile) }
    }

    override fun onSetFlash(mode: FlashMode) {
        viewModelScope.launch { settingsRepository.setFlashMode(mode) }
    }

    override fun onSetExposureCompensation(ev: Float) {
        _uiState.update { it.copy(exposureCompensationEv = ev) }
        viewModelScope.launch { controller.setExposureCompensation(ev) }
    }

    override fun onSetTemperature(temperature: Float) {
        val value = temperature.coerceIn(-1f, 1f)
        _uiState.update { it.copy(temperature = value) }
        viewModelScope.launch { settingsRepository.setTemperature(value) }
    }

    override fun onSetStyle(style: StyleState) {
        _uiState.update { it.copy(style = style) }
        viewModelScope.launch { settingsRepository.setStyle(style) }
    }

    override fun onSetStyleMode(enabled: Boolean) {
        _uiState.update { it.copy(styleMode = enabled) }
    }

    override fun onSelectStylePreset(style: StyleState) {
        // A preset only moves the pads: strength, bloom, grain, and saturation are
        // independent controls that outlive the preset choice (STYLE_PLAN 12.3).
        val current = _uiState.value.style
        onSetStyle(
            style.copy(
                strength = current.strength,
                bloom = current.bloom,
                grain = current.grain,
                saturation = current.saturation,
            ),
        )
    }


    override fun onSetAspectRatio(aspectRatio: AspectRatio) {
        viewModelScope.launch {
            settingsRepository.setAspectRatio(aspectRatio)
            controller.setAspectRatio(aspectRatio)
        }
    }

    override fun onSetHighestResolution(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHighestResolution(enabled)
            controller.setHighestResolution(enabled)
        }
    }

    override fun onTapToFocus(xFraction: Float, yFraction: Float) {
        val point = NormalizedPoint(xFraction.coerceIn(0f, 1f), yFraction.coerceIn(0f, 1f))
        viewModelScope.launch {
            controller.unlockFocus()
            controller.focusAt(point)
        }
    }

    override fun onLockFocus(xFraction: Float, yFraction: Float) {
        val point = NormalizedPoint(xFraction.coerceIn(0f, 1f), yFraction.coerceIn(0f, 1f))
        viewModelScope.launch { controller.lockFocus(point) }
    }

    override fun onPinchZoom(zoomRatio: Float) {
        // Only the wide/normal transition is published; the picker and the lens label need to
        // know which side of 1x the framing is on, not the exact ratio per frame.
        val wide = zoomRatio < 1f
        if (wide != _uiState.value.wideAngleActive) {
            _uiState.update { it.copy(wideAngleActive = wide) }
        }
        // CameraX applies this request synchronously; start immediately so
        // pointer events do not wait behind the main-dispatch queue.
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.setZoom(zoomRatio)
        }
    }

    override fun onOpenGallery() {
        _events.tryEmit(VmEvent.OpenGallery(_uiState.value.lastCapture?.uri))
    }

    override fun onNoticeShown() {
        _uiState.update { it.copy(notice = null) }
    }

    // ---- Settings actions (SettingsScreen) ----

    override fun onSetRawMode(rawMode: RawMode) {
        viewModelScope.launch { settingsRepository.setRawMode(rawMode) }
    }

    fun onSetTimerSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setTimerSeconds(seconds) }
    }

    fun onSetGeotagging(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGeotagging(enabled)
            if (enabled) _events.emit(VmEvent.RequestLocationPermission)
        }
    }

    fun onSetGrid(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setGrid(enabled) }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.update {
                it.copy(notice = UiNotice("Location permission denied — geotagging has no effect", isError = true))
            }
        }
    }

    fun allCapabilities(): List<CameraCapabilities> =
        controller.capabilities.values.toList()

    fun currentSettings() = settingsRepository.settings

    // ---- helpers ----

    @Volatile
    private var geotaggingEnabled: Boolean = false

    fun launchGalleryIntent(uri: String? = null): Intent {
        val target = uri ?: _uiState.value.lastCapture?.uri
        return if (target != null) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(target), "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
    }

    /**
     * Terminal teardown (AGENTS 44): the CameraX backend's executor, analysis channel
     * and bound use cases otherwise survive for the process lifetime. The ViewModel's
     * own scope is being torn down right now, so teardown runs on a scope that is not
     * — and CameraX must unbind on the main thread.
     */
    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.Main.immediate).launch { controller.close() }
    }
}

package com.adin.naturalcam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adin.naturalcam.capture.CaptureCoordinator
import com.adin.naturalcam.camera.CameraController
import com.adin.naturalcam.domain.AspectRatio
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
import com.adin.naturalcam.settings.SettingsRepository
import com.adin.naturalcam.storage.LatestPhotoReader
import com.adin.naturalcam.camera.CameraException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.UUID

sealed interface VmEvent {
    data object OpenSettings : VmEvent
    data object OpenDeviceInfo : VmEvent
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
    private val appContext: Context,
    private val latestPhotoReader: LatestPhotoReader,
) : ViewModel(), CameraActions {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VmEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VmEvent> = _events.asSharedFlow()

    private var attachedPreviewView: PreviewView? = null
    private var zoomJob: Job? = null
    private var lensSelectionJob: Job? = null
    private var initialized = false
    init {
        viewModelScope.launch {
            coordinator.captureState.collect { captureState ->
                _uiState.update {
                    it.copy(
                        captureState = captureState,
                        isShutterEnabled = captureState is CaptureState.Idle ||
                            captureState is CaptureState.Complete ||
                            captureState is CaptureState.Failed,
                        lastCapture = (captureState as? CaptureState.Complete)?.result ?: it.lastCapture,
                        notice = (captureState as? CaptureState.Failed)?.let { failed ->
                            UiNotice(message = failed.error.detail ?: failed.error.toString(), isError = true)
                        } ?: it.notice,
                    )
                }
                if (captureState is CaptureState.Complete) coordinator.acknowledge()
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
                        timerSeconds = settings.timerSeconds,
                        gridEnabled = settings.gridEnabled,
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
                val lenses = controller.initialize()
                val default = lenses.firstOrNull { it.isDefault } ?: lenses.firstOrNull()
                _uiState.update {
                    it.copy(lenses = lenses, selectedCameraId = default?.cameraId)
                }
                default?.let { selectCameraInternal(it.cameraId) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(cameraState = CameraState.Error(e.toDomainError()))
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
        if (attachedPreviewView === previewView) return

        // Navigation recreates PreviewView while the ViewModel survives. Rebind
        // the existing camera session to the new surface instead of leaving the
        // preview attached to the disposed camera screen.
        attachedPreviewView = previewView
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
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(cameraState = CameraState.Error(e.toDomainError())) }
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
                outputSettings = OutputSettings(locationTagging = geotaggingEnabled),
            )
            coordinator.capture(request)
        }
    }

    override fun onSelectLens(cameraId: CameraId, zoomRatio: Float) {
        if (cameraId == _uiState.value.selectedCameraId || lensSelectionJob?.isActive == true) return
        // Do not queue repeated physical-camera rebinds from a single pinch.
        lensSelectionJob = viewModelScope.launch {
            selectCameraInternal(cameraId)
            if (_uiState.value.selectedCameraId == cameraId) {
                controller.setZoom(zoomRatio)
            }
        }
    }

    override fun onSelectProfile(profile: ProcessingProfile) {
        viewModelScope.launch { settingsRepository.setProfile(profile) }
    }

    override fun onSetFlash(mode: FlashMode) {
        viewModelScope.launch { settingsRepository.setFlashMode(mode) }
    }

    override fun onSetExposureCompensation(ev: Float) {
        _uiState.update { it.copy(exposureCompensationEv = ev) }
        viewModelScope.launch { controller.setExposureCompensation(ev) }
    }


    override fun onCycleTimer() {
        viewModelScope.launch {
            val next = when (_uiState.value.timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            settingsRepository.setTimerSeconds(next)
        }
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

    override fun onToggleGrid() {
        viewModelScope.launch { settingsRepository.setGrid(!gridEnabled()) }
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
        // CameraX applies this request synchronously; start immediately so
        // pointer events do not wait behind the main-dispatch queue.
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.setZoom(zoomRatio)
        }
    }

    override fun onOpenSettings() {
        _events.tryEmit(VmEvent.OpenSettings)
    }

    override fun onOpenDeviceInfo() {
        _events.tryEmit(VmEvent.OpenDeviceInfo)
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

    fun allCapabilities(): List<com.adin.naturalcam.domain.CameraCapabilities> =
        controller.capabilities.values.toList()

    fun currentSettings() = settingsRepository.settings

    // ---- helpers ----

    private fun gridEnabled(): Boolean = _uiState.value.gridEnabled

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

    private fun Exception.toDomainError(): CameraError = when (this) {
        is CameraException -> error
        else -> CameraError.CameraUnavailable(message ?: javaClass.simpleName)
    }
}

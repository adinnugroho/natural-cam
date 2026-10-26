package com.adin.naturalcam.camera.camerax

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.adin.naturalcam.camera.CameraCapabilityScanner
import com.adin.naturalcam.camera.CameraController
import com.adin.naturalcam.camera.CameraException
import com.adin.naturalcam.camera.CapturedFrames
import com.adin.naturalcam.camera.toCameraError
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraError
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CameraState
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.CaptureMetadata
import com.adin.naturalcam.domain.CapturePlan
import com.adin.naturalcam.domain.CaptureSource
import com.adin.naturalcam.domain.FlashMode
import com.adin.naturalcam.domain.IspConfiguration
import com.adin.naturalcam.domain.LensOption
import com.adin.naturalcam.domain.NormalizedPoint
import com.adin.naturalcam.domain.PhotoCaptureRequest
import com.adin.naturalcam.domain.RawFrameStrategy
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.image.core.YuvImage
import com.adin.naturalcam.storage.FileNaming
import com.adin.naturalcam.storage.StoragePaths
import kotlin.coroutines.cancellation.CancellationException

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX backend (SPEC 9.1) with Camera2 interop for capability metadata
 * (AGENTS 9). CameraX 1.6.2 provides RAW / RAW+JPEG output written through
 * DngCreator, so the raw frames we develop come with complete capture
 * metadata; YUV stills come from the ImageAnalysis stream (see ADR-001 for
 * the resolution ceiling of that path).
 */
class CameraXController(
    private val context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    private val capturesDir: File = StoragePaths.captureCacheDir(context),
) : CameraController {
    private val camera2RawBurst = Camera2RawBurstCapture(context, capturesDir)

    private val mainExecutor = androidx.core.content.ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "yuv-analysis") }
    private val bindMutex = Mutex()

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewView: PreviewView? = null
    private var camera: androidx.camera.core.Camera? = null

    private var captureFormat: Int = ImageCapture.OUTPUT_FORMAT_JPEG
    private var analysisBound = false

    /** True while the analysis stream is the capture source and must be bound at its largest size. */
    private var analysisFullResolution = false
    private var boundAnalysisFullResolution = false
    private var boundIsp: IspConfiguration? = null
    private var wantedIsp: IspConfiguration? = null
    private var wantedAspect: AspectRatio = AspectRatio.RATIO_4_3
    private var wantedHighestResolution: Boolean = false
    private var boundAspect: AspectRatio? = null

    private val _state = MutableStateFlow<CameraState>(CameraState.Uninitialized)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    /**
     * Physical device rotation in 90° steps, from the accelerometer.
     *
     * The app is portrait-locked, so [previewView]'s display rotation is always 0 and
     * says nothing about how the phone is held. Stills need the physical hold: without
     * it a portrait capture is written in sensor orientation (landscape) and reads as
     * rotated in every viewer.
     */
    private var deviceOrientationDegrees: Int? = null
    private val orientationListener = object : android.view.OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceOrientationDegrees = ((orientation + 45) / 90 * 90) % 360
        }
    }

    // Published as immutable snapshots rebuilt by initialize(): the old getters copied
    // the whole map/list on every read, and DeviceInfoScreen reads them per recomposition.
    private var capabilitiesSnapshot: Map<CameraId, CameraCapabilities> = emptyMap()
    override val capabilities: Map<CameraId, CameraCapabilities> get() = capabilitiesSnapshot

    private var lensesSnapshot: List<LensOption> = emptyList()
    override val lenses: List<LensOption> get() = lensesSnapshot

    private var selected: CameraId? = null
    private var requestedExposureEv: Float = 0f
    /** Last zoom the user set; re-applied after a rebind resets the session crop. */
    private var requestedZoom: Float = 1f
    override val zoomRatio: Float get() = requestedZoom

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override suspend fun initialize(): List<LensOption> {
        val provider = awaitProvider()
        this.provider = provider
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        val infos = provider.availableCameraInfos
        if (infos.isEmpty()) {
            _state.value = CameraState.Error(CameraError.CameraUnavailable("no cameras reported"))
            throw CameraException(CameraError.CameraUnavailable("no cameras reported"))
        }
        // One scan per camera: every Camera2 characteristic is a blocking metadata read,
        // and the lens list used to scan each camera a second time. Off the main thread
        // because that read is what makes startup jank (AGENTS 43).
        val scanned = withContext(Dispatchers.Default) {
            infos.associateWith { CameraCapabilityScanner.scan(it) }
        }
        val lenses = buildLensOptions(scanned)
        capabilitiesSnapshot = scanned.mapKeys { (info, _) -> CameraId(Camera2CameraInfo.from(info).cameraId) }
        lensesSnapshot = lenses
        return lenses
    }

    override fun attachPreview(previewView: PreviewView) {
        this.previewView = previewView
    }

    override suspend fun selectCamera(cameraId: CameraId) {
        bindMutex.withLock {
            val from = selected
            // A different camera starts at 1x: the zoom the user set belongs to the lens they
            // were composing with, not to the one they just switched to.
            if (from != null && from != cameraId) requestedZoom = 1f
            _state.value = if (from != null && from != cameraId) {
                CameraState.Switching(from, cameraId)
            } else {
                CameraState.Initializing
            }
            try {
                rebind(cameraId, keepAnalysis = analysisBound)
                selected = cameraId
                _state.value = CameraState.Ready(cameraId)
            } catch (e: Exception) {
                _state.value = CameraState.Error(mapException(e))
                throw CameraException(mapException(e))
            }
        }
    }

    override suspend fun setExposureCompensation(ev: Float) {
        requestedExposureEv = ev
        applyExposureCompensation(ev)
    }

    private fun applyExposureCompensation(ev: Float) {
        val cam = camera ?: return
        val exposure = cam.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) return
        val range = exposure.exposureCompensationRange
        val stepEv = exposure.exposureCompensationStep.toFloat()
        if (stepEv <= 0f) return
        // SPEC 57: EV → camera index via the reported compensation step.
        val index = Math.round(ev / stepEv).toInt().coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    override suspend fun setAspectRatio(aspectRatio: AspectRatio) {
        bindMutex.withLock {
            wantedAspect = aspectRatio
            val current = selected
            if (current != null && boundAspect != aspectRatio) {
                rebind(current, keepAnalysis = analysisBound)
            }
        }
    }

    override suspend fun setHighestResolution(enabled: Boolean) {
        bindMutex.withLock {
            if (wantedHighestResolution == enabled) return
            wantedHighestResolution = enabled
            selected?.let { rebind(it, keepAnalysis = analysisBound) }
        }
    }

    override suspend fun focusAt(point: NormalizedPoint) {
        val view = previewView ?: return
        val cam = camera ?: return
        val meteringPoint = view.meteringPointFactory.createPoint(
            point.x * view.width,
            point.y * view.height,
        )
        val action = androidx.camera.core.FocusMeteringAction.Builder(
            meteringPoint,
            androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE,
        ).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    override suspend fun lockFocus(point: NormalizedPoint) {
        val view = previewView ?: return
        val cam = camera ?: return
        val meteringPoint = view.meteringPointFactory.createPoint(
            point.x * view.width,
            point.y * view.height,
        )
        // CameraX exposes no explicit "lock lens position"; a non-auto-cancelling
        // AF/AE metering action is the closest supported approximation (SPEC 61).
        val action = androidx.camera.core.FocusMeteringAction.Builder(
            meteringPoint,
            androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE,
        ).disableAutoCancel().build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    override suspend fun unlockFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    override suspend fun setZoom(zoomRatio: Float) {
        val cam = camera ?: return
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: return
        val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        val target = if (zoomRatio <= 0f) minZoom else zoomRatio.coerceIn(minZoom, maxZoom)
        // Remembered because a rebind (aspect/resolution/stream-format change) tears the
        // session down and the crop region with it: without this the next frame silently
        // jumps back to 1x and the user's framing is lost (SPEC 66: framing differences
        // must not surprise the user; below 1x this also drops the shot off the ultrawide).
        requestedZoom = target
        cam.cameraControl.setZoomRatio(target)
    }

    override suspend fun capture(request: PhotoCaptureRequest, plan: CapturePlan): CapturedFrames {
        val cameraId = request.cameraId
        if (selected != cameraId) selectCamera(cameraId)
        bindMutex.withLock {
            ensureBoundFor(plan, request)
            imageCapture?.flashMode = when (request.flashMode) {
                FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            }
        }
        val restoreExposureEv = requestedExposureEv
        val captureExposureEv = if (request.profile == ProcessingProfile.NATURAL) {
            restoreExposureEv + NATURAL_CAPTURE_BIAS_EV
        } else {
            restoreExposureEv
        }
        if (captureExposureEv != restoreExposureEv) {
            applyExposureCompensation(captureExposureEv)
            Log.d(
                TAG,
                "capture ${request.captureId.value} naturalExposureBiasEv=$NATURAL_CAPTURE_BIAS_EV " +
                    "requestedEv=$restoreExposureEv appliedEv=$captureExposureEv",
            )
        }
        return try {
            // The bound stream decides the capture call, not the plan's final source: RAW and
            // RAW_JPEG both go through captureRaw, the latter with two output options so the
            // dual takePicture gets its DNG and its companion JPEG. Routing SYSTEM + JPG+RAW by
            // plan source alone made it fail on device with "Simultaneous capture RAW and JPEG
            // needs two output file options" (found during the 2026-10-08 device pass).
            val rawStream = captureFormat == ImageCapture.OUTPUT_FORMAT_RAW ||
                captureFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG
            when {
                rawStream -> captureRaw(request, plan)
                plan.source == CaptureSource.YUV -> captureYuv(request)
                else -> captureJpeg(request, plan)
            }
        } finally {
            if (captureExposureEv != restoreExposureEv) {
                applyExposureCompensation(restoreExposureEv)
            }
        }
    }

    /**
     * Terminal teardown, called when the owning ViewModel is cleared. Before this the
     * analyzer thread, its channel and any buffered frame survived the whole process
     * (AGENTS 44): a buffered [ImageProxy] holds a HAL buffer open.
     */
    override suspend fun close() {
        orientationListener.disable()
        provider?.unbindAll()
        provider = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        previewView = null
        camera = null
        selected = null
        boundIsp = null
        boundAspect = null
        analysisBound = false
        boundAnalysisFullResolution = false
        analysisChannel.close()
        analysisChannel.tryReceive().getOrNull()?.close()
        analysisExecutor.shutdown()
        _state.value = CameraState.Uninitialized
    }

    // ---- binding ----

    private suspend fun ensureBoundFor(plan: CapturePlan, request: PhotoCaptureRequest) {
        // NATURAL/PURE still need a temporary RAW stream for JPG-only captures;
        // saveRaw controls persistence, not whether the app develops the image.
        val wantedFormat = wantedOutputFormat(plan, request)
        val wantedAnalysis = plan.source == CaptureSource.YUV
        // The analysis stream exists only to serve a YUV still, so when it *is* the capture
        // source it is bound at the largest size the camera offers. Left to CameraX's default
        // analysis strategy the still came out 640x480 (0.3 MP, measured on the Oppo) against
        // 1600x1200 when asked for the largest — and a wide-angle framing has no RAW to fall
        // back to, so this stream is the whole image.
        analysisFullResolution = wantedAnalysis
        // SYSTEM keeps the device's own processing defaults; NATURAL/PURE ask the
        // ISP for the least processing the hardware reports (SPEC 23).
        wantedIsp = if (request.profile == ProcessingProfile.SYSTEM) {
            null
        } else {
            plan.ispConfiguration
        }
        if (wantedFormat != captureFormat ||
            wantedAnalysis != analysisBound ||
            wantedIsp != boundIsp ||
            analysisFullResolution != boundAnalysisFullResolution
        ) {
            captureFormat = wantedFormat
            analysisBound = wantedAnalysis
            rebind(selected ?: request.cameraId, keepAnalysis = wantedAnalysis)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyIspOptions(builder: ImageCapture.Builder, isp: IspConfiguration) {
        val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
        IspOptionMapping.noiseReductionToCamera2(isp.noiseReduction)?.let { nr ->
            extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, nr)
        }
        IspOptionMapping.edgeToCamera2(isp.edgeMode)?.let { edge ->
            extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, edge)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun rebind(cameraId: CameraId, keepAnalysis: Boolean) {
        val provider = this.provider ?: throw CameraException(CameraError.CameraUnavailable("not initialized"))
        val view = previewView ?: throw CameraException(CameraError.SessionConfigurationFailed("no preview surface"))

        val preview = Preview.Builder()
            .setResolutionSelector(OutputResolutionMapping.resolutionSelector(wantedAspect, wantedHighestResolution))
            .build()
            .also { it.setSurfaceProvider(view.surfaceProvider) }

        val selector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId.value } }
            .build()

        fun buildCapture(useIsp: Boolean): ImageCapture {
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setOutputFormat(captureFormat)
                .setResolutionSelector(OutputResolutionMapping.resolutionSelector(wantedAspect, wantedHighestResolution))
            val isp = wantedIsp
            if (useIsp && isp != null) applyIspOptions(builder, isp)
            return builder.build()
        }

        fun buildAnalysis(): ImageAnalysis {
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Without a target rotation the analysis frames carry no display relationship, and
                // a YUV still came out in sensor orientation (sideways) — ImageProxy.imageInfo
                // .rotationDegrees is derived from this, and the app runs portrait-locked, so the
                // display rotation at bind time is the one that matters.
                .setTargetRotation(previewView?.display?.rotation ?: android.view.Surface.ROTATION_0)
                .setResolutionSelector(
                    OutputResolutionMapping.resolutionSelector(
                        wantedAspect,
                        wantedHighestResolution || analysisFullResolution,
                    ),
                )
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> onAnalysisFrame(proxy) }
            return analysis
        }

        // CameraX cannot keep two different camera selectors bound to the same
        // lifecycle reliably. Tear down once, then bind exactly one complete
        // use-case set.
        provider.unbindAll()
        var lastError: Exception? = null
        for (attempt in 0..2) {
            val useAnalysis = keepAnalysis && attempt == 0
            val useIsp = attempt <= 1
            val capture = buildCapture(useIsp)
            val useCases = mutableListOf<androidx.camera.core.UseCase>(preview, capture)
            val analysis = if (useAnalysis) buildAnalysis() else null
            if (analysis != null) useCases += analysis
            try {
                camera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                this.preview = preview
                this.imageCapture = capture
                this.imageAnalysis = analysis
                analysisBound = useAnalysis
                boundAnalysisFullResolution = useAnalysis && analysisFullResolution
                boundIsp = if (useIsp) wantedIsp else null
                boundAspect = wantedAspect
                // A new session starts at 1x; re-apply what the user had (see setZoom).
                if (requestedZoom != 1f) camera?.cameraControl?.setZoomRatio(requestedZoom)
                return
            } catch (e: Exception) {
                lastError = e
                provider.unbindAll()
            }
        }
        throw CameraException(mapException(lastError ?: IllegalStateException("bind failed")))
    }

    // ---- capture paths ----

    /**
     * SYSTEM's final image is the platform JPEG, so RAW_AND_FINAL needs the dual
     * RAW+JPEG stream; RAW_ONLY never wants the JPEG. Single source of truth for
     * save routing; [wantedOutputFormat] derives the stream it needs.
     */
    private fun wantsHalJpeg(request: PhotoCaptureRequest): Boolean =
        request.rawMode != RawMode.RAW_ONLY &&
            (request.profile == ProcessingProfile.SYSTEM ||
                request.profile == ProcessingProfile.NATURAL)

    /**
     * Stream format for a plan; the only place this decision is made.
     *
     * NATURAL uses a temporary HAL JPEG as the immediate preview while the RAW result
     * develops. It is never the saved NATURAL final. RAW-only output remains RAW-only.
     */
    private fun wantedOutputFormat(plan: CapturePlan, request: PhotoCaptureRequest): Int = when {
        plan.source == CaptureSource.PROCESSED_JPEG && !plan.saveRaw ->
            ImageCapture.OUTPUT_FORMAT_JPEG
        plan.source == CaptureSource.RAW_SENSOR && wantsHalJpeg(request) ->
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        plan.source == CaptureSource.RAW_SENSOR -> ImageCapture.OUTPUT_FORMAT_RAW
        wantsHalJpeg(request) -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        plan.saveRaw -> ImageCapture.OUTPUT_FORMAT_RAW
        else -> ImageCapture.OUTPUT_FORMAT_JPEG
    }

    private suspend fun captureRaw(request: PhotoCaptureRequest, plan: CapturePlan): CapturedFrames {
        val requestedFrames = plan.rawFrameCount.coerceIn(1, 3)
        return try {
            if (requestedFrames > 1) {
                // Camera2 first: returns in-memory raw images, skipping the 10.8s DNG
                // disk round-trip. CameraX rebinds preview after capture.
                try {
                    captureRawCamera2(request, plan)
                } catch (e: CancellationException) {
                    throw e
                } catch (camera2Error: Exception) {
                    Log.w(
                        TAG,
                        "capture ${request.captureId.value} Camera2 RAW burst failed; falling back to CameraX",
                        camera2Error,
                    )
                    captureRawFrames(request, requestedFrames, plan.rawFrameStrategy)
                }
            } else {
                captureRawFrames(request, requestedFrames, plan.rawFrameStrategy)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestedFrames == 1) throw e
            Log.w(
                TAG,
                "capture ${request.captureId.value} RAW burst failed; retrying one frame",
                e,
            )
            captureRawFrames(request, 1, RawFrameStrategy.SINGLE).let { fallback ->
                CapturedFrames(
                    captureId = fallback.captureId,
                    metadata = fallback.metadata,
                    rawDngFiles = fallback.rawDngFiles,
                    yuv = fallback.yuv,
                    jpegBytes = fallback.jpegBytes,
                    halJpegBytes = fallback.halJpegBytes,
                    limitations = setOf(com.adin.naturalcam.domain.CaptureLimitation.MULTI_FRAME_FALLBACK),
                )
            }
        }
    }

    private suspend fun captureRawCamera2(
        request: PhotoCaptureRequest,
        plan: CapturePlan,
    ): CapturedFrames {
        val provider = this.provider
            ?: throw CameraException(CameraError.CameraUnavailable("camera provider missing"))
        val cameraId = selected ?: request.cameraId
        val capabilities = capabilitiesSnapshot[cameraId]
            ?: throw CameraException(CameraError.CameraUnavailable("unknown camera ${cameraId.value}"))
        val rawSize = capabilities.resolutions[com.adin.naturalcam.domain.CaptureFormat.RAW_SENSOR]
            ?.maxByOrNull { it.width * it.height }
            ?: throw CameraException(CameraError.CaptureFailed("RAW size unavailable"))
        val jpegSize = capabilities.resolutions[com.adin.naturalcam.domain.CaptureFormat.JPEG]
            ?.maxByOrNull { it.width * it.height }
            ?: throw CameraException(CameraError.CaptureFailed("JPEG size unavailable"))
        val keepAnalysis = analysisBound
        provider.unbindAll()
        preview = null
        imageCapture = null
        imageAnalysis = null
        camera = null
        try {
            val baseExposureEv = requestedExposureEv +
                if (request.profile == ProcessingProfile.NATURAL) NATURAL_CAPTURE_BIAS_EV else 0f
            val offsets = if (plan.rawFrameStrategy == RawFrameStrategy.HDR_BRACKET) {
                HDR_BRACKET_OFFSETS_EV
            } else {
                floatArrayOf(0f, 0f, 0f)
            }
            val result = camera2RawBurst.capture(
                cameraId = cameraId.value,
                captureId = request.captureId.value,
                frameCount = plan.rawFrameCount.coerceIn(1, 3),
                rawSize = android.util.Size(rawSize.width, rawSize.height),
                jpegSize = android.util.Size(jpegSize.width, jpegSize.height),
                exposureCompensationEv = baseExposureEv,
                bracketOffsetsEv = offsets,
                rotation = captureRotationDegrees(),
            )
            return CapturedFrames(
                captureId = request.captureId,
                metadata = baselineMetadata(request, captureRotationDegrees()),
                rawDngFiles = result.dngFiles,
                yuv = null,
                jpegBytes = null,
                halJpegBytes = result.previewJpeg,
                rawImages = result.rawImages,
            )
        } finally {
            bindMutex.withLock {
                captureFormat = wantedOutputFormat(plan, request)
                rebind(cameraId, keepAnalysis = keepAnalysis)
            }
        }
    }

    private suspend fun captureRawFrames(
        request: PhotoCaptureRequest,
        frameCount: Int,
        strategy: RawFrameStrategy,
    ): CapturedFrames {
        val dngFiles = mutableListOf<File>()
        val wantsJpeg = wantsHalJpeg(request)
        val rotation = captureRotationDegrees()
        val baseExposureEv = requestedExposureEv +
            if (request.profile == ProcessingProfile.NATURAL) NATURAL_CAPTURE_BIAS_EV else 0f
        val jpegFrameIndex = if (strategy == RawFrameStrategy.HDR_BRACKET && frameCount > 1) 1 else 0
        var previewJpegBytes: ByteArray? = null
        try {
            repeat(frameCount) { index ->
                if (strategy == RawFrameStrategy.HDR_BRACKET) {
                    applyExposureCompensation(
                        baseExposureEv + HDR_BRACKET_OFFSETS_EV[index.coerceIn(0, HDR_BRACKET_OFFSETS_EV.lastIndex)],
                    )
                }
                val suffix = if (frameCount == 1) "" else "-$index"
                val dngFile = File(
                    capturesDir,
                    FileNaming.tempName("${request.captureId.value}$suffix", ".dng"),
                )
                dngFiles += dngFile
                val frameJpeg = if (wantsJpeg) ByteArrayOutputStream() else null
                captureRawFile(request, dngFile, frameJpeg)
                if (index == jpegFrameIndex) previewJpegBytes = frameJpeg?.toByteArray()
            }
            return CapturedFrames(
                captureId = request.captureId,
                metadata = baselineMetadata(request, rotation),
                rawDngFiles = dngFiles,
                yuv = null,
                jpegBytes = null,
                halJpegBytes = previewJpegBytes,
            )
        } catch (e: CancellationException) {
            dngFiles.forEach(File::delete)
            throw e
        } catch (e: Exception) {
            dngFiles.forEach(File::delete)
            throw e
        } finally {
            if (strategy == RawFrameStrategy.HDR_BRACKET) {
                applyExposureCompensation(baseExposureEv)
            }
        }
    }

    private suspend fun captureRawFile(
        request: PhotoCaptureRequest,
        dngFile: File,
        jpegStream: ByteArrayOutputStream?,
    ) {
        val capture = imageCapture
            ?: throw CameraException(CameraError.CameraUnavailable("capture use case missing"))
        val rawOptions = ImageCapture.OutputFileOptions.Builder(dngFile).build()
        val jpegOptions = jpegStream?.let { ImageCapture.OutputFileOptions.Builder(it).build() }
        Log.d(TAG, "capture ${request.captureId.value} raw takePicture fmt=$captureFormat -> ${dngFile.name}")

        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { dngFile.delete() }
            val savedFlags = java.util.concurrent.atomic.AtomicInteger(0)
            val expected = if (jpegOptions != null) 2 else 1
            val callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val results = savedFlags.incrementAndGet()
                    val ready = dngFile.length() > 0 && (jpegStream == null || jpegStream.size() > 0)
                    if ((ready || results >= expected) && cont.isActive) {
                        cont.resume(Unit)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture ${request.captureId.value} raw error: $exception")
                    dngFile.delete()
                    if (cont.isActive) {
                        cont.resumeWithException(CameraException(mapCaptureException(exception)))
                    }
                }
            }
            try {
                if (jpegOptions != null) {
                    capture.takePicture(rawOptions, jpegOptions, mainExecutor, callback)
                } else {
                    capture.takePicture(rawOptions, mainExecutor, callback)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(CameraException(mapException(e)))
            }
        }
    }

    private val TAG = "NaturalCam"

    private suspend fun captureJpeg(request: PhotoCaptureRequest, plan: CapturePlan): CapturedFrames {
        val stream = ByteArrayOutputStream()
        val options = ImageCapture.OutputFileOptions.Builder(stream).build()
        val rotation = captureRotationDegrees()
        val capture = imageCapture
            ?: throw CameraException(CameraError.CameraUnavailable("capture use case missing"))
        Log.d(TAG, "capture ${request.captureId.value} jpeg takePicture fmt=$captureFormat")
        suspendCancellableCoroutine { cont ->
            val callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "capture ${request.captureId.value} jpeg saved bytes=${stream.size()}")
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture ${request.captureId.value} jpeg error: $exception")
                    if (cont.isActive) cont.resumeWithException(CameraException(mapCaptureException(exception)))
                }
            }
            try {
                capture.takePicture(options, mainExecutor, callback)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(CameraException(mapException(e)))
            }
        }
        return CapturedFrames(
            captureId = request.captureId,
            metadata = baselineMetadata(request, rotation),
            rawDngFiles = emptyList(),
            yuv = null,
            jpegBytes = stream.toByteArray(),
            halJpegBytes = null,
        )
    }

    /**
     * Newest frame wins without leaking the one it replaces. A CONFLATED channel drops
     * the buffered element silently, and an unclosed [ImageProxy] is never returned to
     * the HAL — which stalls the analysis stream for the rest of the session (AGENTS 44).
     * The analyzer runs on its own single thread, so this drain/send pair cannot race
     * itself.
     */
    private val analysisChannel = Channel<ImageProxy>(capacity = Channel.CONFLATED)

    private fun onAnalysisFrame(proxy: ImageProxy) {
        analysisChannel.tryReceive().getOrNull()?.close()
        if (analysisChannel.trySend(proxy).isFailure) proxy.close()
    }

    private suspend fun captureYuv(request: PhotoCaptureRequest): CapturedFrames {
        // Drop whatever was buffered before the shutter press: that is a preview frame,
        // not the still the user asked for.
        analysisChannel.tryReceive().getOrNull()?.close()
        val proxy = analysisChannel.receive()
        // Same correction the RAW/JPEG stills use: CameraX's own ImageInfo.rotationDegrees is
        // derived from the *display* target rotation, which the portrait lock pins at 0, so it
        // ignores how the phone is actually held.
        val rotation = captureRotationDegrees()
        try {
            val yuv = proxy.toYuvImage()
            return CapturedFrames(
                captureId = request.captureId,
                metadata = baselineMetadata(request, rotation),
                rawDngFiles = emptyList(),
                yuv = yuv,
                jpegBytes = null,
                halJpegBytes = null,
            )
        } finally {
            proxy.close()
        }
    }

    // ---- helpers ----

    private fun baselineMetadata(request: PhotoCaptureRequest, rotation: Int) = CaptureMetadata(
        captureId = request.captureId,
        timestampMs = System.currentTimeMillis(),
        orientationDegrees = rotation,
        iso = null,
        exposureTimeNs = null,
        aperture = null,
        focalLengthMm = null,
        make = android.os.Build.MANUFACTURER,
        model = android.os.Build.MODEL,
        gpsLatitude = null,
        gpsLongitude = null,
    )

    private fun currentRotationDegrees(): Int {
        val rotation = previewView?.display?.rotation ?: android.view.Surface.ROTATION_0
        return when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Clockwise rotation that makes a captured frame upright for the current hold.
     *
     * Same rule the Camera2 sample uses for JPEG orientation: the sensor is mounted at
     * [CameraCapabilities.sensorOrientation] (90° on the rear camera here), and a phone
     * held upright needs exactly that much correction. Front cameras mirror the device
     * term. Falls back to the display rotation only when no sensor is known, and to it
     * when the accelerometer has not reported yet.
     */
    private fun captureRotationDegrees(): Int {
        val capabilities = selected?.let { capabilitiesSnapshot[it] }
        val sensor = capabilities?.sensorOrientation ?: return currentRotationDegrees()
        val device = deviceOrientationDegrees ?: return sensor % 360
        val signed = if (capabilities.lensFacing == LensFacing.FRONT) -device else device
        return ((sensor + signed) % 360 + 360) % 360
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(CameraException(mapException(e)))
                    }
                },
                mainExecutor,
            )
        }

    private fun ImageProxy.toYuvImage(): YuvImage {
        val planes = planes
        val y = planes[0].buffer.copyAll()
        val u = planes[1].buffer.copyAll()
        val v = planes[2].buffer.copyAll()
        return YuvImage(
            width = width,
            height = height,
            yPlane = y,
            yRowStride = planes[0].rowStride,
            yPixelStride = planes[0].pixelStride,
            uPlane = u,
            uRowStride = planes[1].rowStride,
            uPixelStride = planes[1].pixelStride,
            vPlane = v,
            vRowStride = planes[2].rowStride,
            vPixelStride = planes[2].pixelStride,
        )
    }

    private fun java.nio.ByteBuffer.copyAll(): ByteArray {
        rewind()
        val out = ByteArray(remaining())
        get(out)
        return out
    }

    private fun mapCaptureException(e: ImageCaptureException): CameraError = when (e.imageCaptureError) {
        ImageCapture.ERROR_CAMERA_CLOSED -> CameraError.CameraDisconnected(e.message)
        ImageCapture.ERROR_FILE_IO -> CameraError.StorageFailed(e.message)
        ImageCapture.ERROR_INVALID_CAMERA -> CameraError.CameraUnavailable(e.message)
        else -> CameraError.CaptureFailed(e.message)
    }

    private fun mapException(e: Exception): CameraError = e.toCameraError()
}

/** Capture-side NATURAL bias selected to protect highlights after device validation. */
private const val NATURAL_CAPTURE_BIAS_EV = -0.3f
/** Short / normal / long bracket offsets; final rendering remains globally restrained. */
private val HDR_BRACKET_OFFSETS_EV = floatArrayOf(-2f, 0f, 2f)

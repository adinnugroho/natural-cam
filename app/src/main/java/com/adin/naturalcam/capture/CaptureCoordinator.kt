package com.adin.naturalcam.capture

import android.os.SystemClock
import android.util.Log

import com.adin.naturalcam.BuildConfig
import com.adin.naturalcam.camera.CameraController
import com.adin.naturalcam.camera.CameraException
import com.adin.naturalcam.camera.CapturedFrames
import com.adin.naturalcam.camera.toCameraError
import com.adin.naturalcam.domain.CameraError
import com.adin.naturalcam.domain.CaptureMetadata
import com.adin.naturalcam.domain.CapturePlan
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.PhotoCaptureRequest
import com.adin.naturalcam.domain.PhotoCaptureResult
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.SavedPhoto
import com.adin.naturalcam.image.core.EncodedImage
import com.adin.naturalcam.image.core.ImagePipeline
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.core.ProcessingTimings
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.RgbGains
import com.adin.naturalcam.image.raw.ColorMatrixFactory
import com.adin.naturalcam.image.raw.DngReader
import com.adin.naturalcam.location.LocationProvider
import com.adin.naturalcam.resolve.CapturePathResolver
import com.adin.naturalcam.storage.FileNaming
import com.adin.naturalcam.storage.MediaStoreWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the capture state machine (SPEC 68–69): IDLE → PREPARING → CAPTURING →
 * PROCESSING → SAVING → COMPLETE → IDLE, with failures landing in a
 * recoverable state (AGENTS 40). Processing is bounded; when the queue is
 * full new captures are refused before acknowledgement instead of silently
 * dropped (AGENTS 42).
 *
 * That queue is what keeps the shutter usable while a previous photo develops: a
 * 12 MP RAW takes seconds to develop, and a point-and-shoot camera cannot stop
 * accepting shots for that long. [jobsInFlight] tells the UI how many jobs are
 * live, so it can keep the shutter enabled up to [maxConcurrentJobs] and then
 * refuse honestly. Captures may overlap (the camera serialises its own requests,
 * and a capture is ~1 s); *develops* do not — two 12 MP frames in flight at once
 * is a 600 MB heap problem, not a throughput win (AGENTS 45), so they queue on
 * [developGate] and land in the gallery in capture order.
 */
class CaptureCoordinator(
    private val controller: CameraController,
    private val pipeline: ImagePipeline,
    private val mediaStore: MediaStoreWriter,
    private val locationProvider: LocationProvider? = null,
    /**
     * Develop is DNG decode and pixel math — CPU work, not blocking I/O; the media
     * store writer confines its own I/O (AGENTS 43).
     */
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val maxConcurrentJobs: Int = 2,
) {
    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    /** Accepted, unfinished jobs. The UI enables the shutter while this is below [maxConcurrentJobs]. */
    private val _jobsInFlight = MutableStateFlow(0)
    val jobsInFlight: StateFlow<Int> = _jobsInFlight.asStateFlow()

    private val gate = Semaphore(maxConcurrentJobs)

    /** One develop at a time; see the class note. */
    private val developGate = Mutex()

    /** Runs one shutter event end-to-end. Returns null when the state holds the error. */
    suspend fun capture(request: PhotoCaptureRequest): PhotoCaptureResult? {
        if (!gate.tryAcquire()) {
            _captureState.value = CaptureState.Failed(
                CameraError.CaptureFailed(
                    "processing queue full (${_jobsInFlight.value} of $maxConcurrentJobs); " +
                        "capture refused before acknowledgement",
                ),
            )
            return null
        }
        _jobsInFlight.update { it + 1 }
        try {
            _captureState.value = CaptureState.Preparing
            val capabilities = controller.capabilities[request.cameraId]
            if (capabilities == null) {
                return fail(request, CameraError.CameraUnavailable("unknown camera ${request.cameraId.value}"))
            }
            val plan = CapturePathResolver.resolve(capabilities, request.profile, request.rawMode, request.zoomRatio)
            // SPEC 109 debug facts: identity, plan, profile, pipeline — never pixels or GPS (AGENTS 55).
            Log.d(
                TAG,
                "capture ${request.captureId.value} camera=${request.cameraId.value} profile=${request.profile} " +
                    "rawMode=${request.rawMode} zoom=${request.zoomRatio} aspect=${request.aspectRatio} " +
                    "plan=${plan.source}/${plan.pipeline} saveRaw=${plan.saveRaw} " +
                    "limitations=${plan.limitations}",
            )

            _captureState.value = CaptureState.Capturing
            val captureStart = SystemClock.elapsedRealtime()
            val frames = try {
                // Bounded waits (AGENTS 40): a wedged HAL must not disable the shutter forever.
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { controller.capture(request, plan) }
                    ?: return fail(request, CameraError.CaptureFailed("capture timed out after ${CAPTURE_TIMEOUT_MS / 1000}s"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(request, e.toCaptureError())
            }
            val captureMs = SystemClock.elapsedRealtime() - captureStart

            _captureState.value = CaptureState.Processing(null)
            val processStart = SystemClock.elapsedRealtime()
            val result = try {
                // Bounds cooperative waits (MediaStore I/O) and the wait for a free develop
                // slot. Note: pure CPU work in processAndSave has no suspension points and
                // cannot be preempted — its bound is performance (see LIMITATIONS.md).
                withTimeoutOrNull(PROCESSING_TIMEOUT_MS) {
                    withContext(processingDispatcher) {
                        developGate.withLock { processAndSave(request, plan, frames) }
                    }
                } ?: return fail(request, CameraError.ProcessingFailed("processing timed out after ${PROCESSING_TIMEOUT_MS / 1000}s"))
            } catch (e: CancellationException) {
                // Cancelling the job is not a failed capture, and it must not be
                // swallowed into a Failed state (AGENTS 40/56).
                throw e
            } catch (e: Exception) {
                return fail(request, e.toCaptureError())
            } finally {
                // Single owner for the temp RAW: the processing timeout, a cancellation
                // while waiting for the develop gate, and the refused-capture path never
                // enter processRawCapture, so nothing else would delete it (AGENTS 51).
                frames.rawDngFile?.delete()
            }
            val processMs = SystemClock.elapsedRealtime() - processStart
            Log.d(
                TAG,
                "capture ${request.captureId.value} done captureMs=$captureMs processSaveMs=$processMs " +
                    "pipeline=${result.pipelineVersion}",
            )

            _captureState.value = CaptureState.Complete(result.finalPhoto ?: result.rawPhoto!!)
            return result
        } finally {
            _jobsInFlight.update { it - 1 }
            gate.release()
        }
    }

    /** Clears Complete/Failed back to Idle so the next shutter starts clean. */
    fun acknowledge() {
        if (_captureState.value !is CaptureState.Processing) {
            _captureState.value = CaptureState.Idle
        }
    }

    private suspend fun processAndSave(
        request: PhotoCaptureRequest,
        plan: CapturePlan,
        frames: CapturedFrames,
    ): PhotoCaptureResult {
        val metadata = withLocation(frames.metadata, request.outputSettings.locationTagging)
        val config = ProcessingConfiguration.forProfile(
            request.profile,
            request.outputSettings.jpegQuality,
            request.temperature,
            request.style,
            request.aspectRatio,
        )
        val limitations = plan.limitations
        // Developed JPEGs are physically rotated and HAL JPEGs arrive rotated by
        // the platform, so EXIF orientation is normal for every final JPEG (SPEC 89).
        val jpegMetadata = metadata.copy(orientationDegrees = 0)
        // SPEC 109 debug facts: one line of per-stage durations per capture.
        val timings = if (BuildConfig.DEBUG) ProcessingTimings() else null

        val result = when {
            frames.rawDngFile != null -> processRawCapture(request, plan, frames, metadata, config, timings)
            frames.yuv != null -> {
                if (BuildConfig.DEBUG && request.profile == ProcessingProfile.NATURAL) {
                    Log.d(
                        TAG,
                        "natural diagnostics capture=${request.captureId.value} camera=${request.cameraId.value} " +
                            "source=YUV_420_888 pipeline=${config.pipelineVersion} wbSource=ISP_AWB " +
                            "inputColor=display-referred-YUV workingColor=linear-sRGB outputColor=sRGB " +
                            "tone=natural-v3",
                    )
                }
                val processStart = SystemClock.elapsedRealtime()
                val encoded = pipeline.processYuv(frames.yuv, config, metadata.orientationDegrees, timings)
                timings?.record("pipeline", SystemClock.elapsedRealtime() - processStart)
                val photo = saveFinalJpeg(encoded.bytes, metadata.timestampMs, jpegMetadata, request, timings)
                PhotoCaptureResult(photo, null, metadata, request.profile, config.pipelineVersion, limitations)
            }
            else -> {
                // Platform-processed fallback (or SYSTEM output): pass the bytes through
                // with only honest metadata written (AGENTS 32).
                val bytes = frames.halJpegBytes ?: frames.jpegBytes
                    ?: throw CameraException(CameraError.ProcessingFailed("no captured payload"))
                val photo = saveFinalJpeg(bytes, metadata.timestampMs, jpegMetadata, request)
                PhotoCaptureResult(photo, null, metadata, request.profile, config.pipelineVersion, limitations)
            }
        }

        if (timings != null) {
            Log.d(
                TAG,
                "develop timings capture=${request.captureId.value} profile=${request.profile} " +
                    "plan=${plan.source}/${plan.pipeline} ${timings.summary()}",
            )
        }
        return result
    }

    private suspend fun processRawCapture(
        request: PhotoCaptureRequest,
        plan: CapturePlan,
        frames: CapturedFrames,
        metadata: CaptureMetadata,
        config: ProcessingConfiguration,
        timings: ProcessingTimings? = null,
    ): PhotoCaptureResult {
        val dngFile = frames.rawDngFile!!
        _captureState.value = CaptureState.Saving

        // One capture clock for both names so the RAW/JPEG pair shares its base
        // identifier (SPEC 86); EXIF keeps the DNG's own timestamp.
        val nameTimestamp = metadata.timestampMs

        // RAW survives even if later processing fails (SPEC 105): save first.
        val rawPhoto = if (plan.saveRaw) {
            mediaStore.saveDng(dngFile.readBytes(), FileNaming.dngName(nameTimestamp), metadata)
                ?: throw CameraException(CameraError.StorageFailed("MediaStore rejected the DNG write"))
        } else {
            null
        }

        if (request.rawMode == RawMode.RAW_ONLY) {
            // The DNG itself carries the authoritative capture metadata; no decode needed.
            return PhotoCaptureResult(
                null, rawPhoto, metadata, request.profile, config.pipelineVersion, plan.limitations,
            )
        }

        // Local decode so the debug timing wraps the same call the release path makes.
        fun decodeDng(): RawImage = try {
            DngReader.read(dngFile)
        } catch (e: DngReader.DngFormatException) {
            throw CameraException(CameraError.ProcessingFailed("DNG decode failed: ${e.message}"))
        }
        val decodeStart = SystemClock.elapsedRealtime()
        val rawImage = decodeDng()
        timings?.record("dngRead", SystemClock.elapsedRealtime() - decodeStart)

        // RAW carries the authoritative capture metadata (AGENTS 52).
        val rawMeta = rawImage.metadata
        val fullMetadata = metadata.copy(
            timestampMs = rawMeta.timestampMs,
            orientationDegrees = rawMeta.orientationDegrees,
            iso = rawMeta.isoSpeed,
            exposureTimeNs = rawMeta.exposureTimeNs,
            aperture = rawMeta.aperture,
            focalLengthMm = rawMeta.focalLengthMm,
        )
        if (BuildConfig.DEBUG && request.profile == ProcessingProfile.NATURAL) {
            val gains = rawMeta.asShotNeutral?.let { neutral ->
                runCatching { RgbGains.fromAsShotNeutral(neutral) }.getOrNull()
            }
            Log.d(
                TAG,
                "natural diagnostics capture=${request.captureId.value} camera=${request.cameraId.value} " +
                    "source=RAW_SENSOR pipeline=${config.pipelineVersion} " +
                    "wbSource=${if (rawMeta.asShotNeutral != null) "AsShotNeutral" else "fallback"} " +
                    "wbGains=${gains?.let { "${it.r},${it.g},${it.b}" } ?: "unknown"} " +
                    "inputColor=linear-sensor workingColor=linear-sRGB outputColor=sRGB " +
                    "black=${rawMeta.blackLevelPerChannel.joinToString(",")} white=${rawMeta.whiteLevel} " +
                    "exposureNs=${rawMeta.exposureTimeNs} iso=${rawMeta.isoSpeed} " +
                    "matrix=${ColorMatrixFactory.matrixSelectionName(rawMeta)} tone=${config.pipelineVersion}",
            )
        }

        // SYSTEM profile: the platform JPEG (from the dual capture) is the final
        // image; the RAW is a companion. NATURAL/PURE: develop from the RAW.
        if (request.profile == ProcessingProfile.SYSTEM) {
            val halJpeg = frames.halJpegBytes
                ?: throw CameraException(CameraError.ProcessingFailed("SYSTEM capture missing processed JPEG"))
            val photo = saveFinalJpeg(
                halJpeg, nameTimestamp, fullMetadata.copy(orientationDegrees = 0), request,
            )
            return PhotoCaptureResult(photo, rawPhoto, fullMetadata, request.profile, config.pipelineVersion, plan.limitations)
        }

        val processStart = SystemClock.elapsedRealtime()
        val encoded = pipeline.processRaw(rawImage, config, timings)
        timings?.record("pipeline", SystemClock.elapsedRealtime() - processStart)
        val photo = saveFinalJpeg(
            encoded.bytes, nameTimestamp, fullMetadata.copy(orientationDegrees = 0), request, timings,
        )
        return PhotoCaptureResult(photo, rawPhoto, fullMetadata, request.profile, config.pipelineVersion, plan.limitations)
    }

    /**
     * The one place a final JPEG reaches MediaStore. [metadata] must already be
     * orientation-normalized: developed JPEGs are physically rotated and HAL JPEGs
     * arrive rotated, so EXIF orientation is normal for every final image (SPEC 89).
     */
    private suspend fun saveFinalJpeg(
        bytes: ByteArray,
        timestampMs: Long,
        metadata: CaptureMetadata,
        request: PhotoCaptureRequest,
        timings: ProcessingTimings? = null,
    ): SavedPhoto {
        _captureState.value = CaptureState.Saving
        val saveStart = SystemClock.elapsedRealtime()
        val saved = mediaStore.saveJpeg(
            bytes = bytes,
            fileName = FileNaming.jpegName(timestampMs),
            metadata = metadata,
            locationTagging = request.outputSettings.locationTagging,
        )
        timings?.record("save", SystemClock.elapsedRealtime() - saveStart)
        return saved ?: throw CameraException(CameraError.StorageFailed("MediaStore rejected the JPEG write"))
    }

    private fun withLocation(
        metadata: CaptureMetadata,
        locationTagging: Boolean,
    ): CaptureMetadata {
        if (!locationTagging) return metadata
        val location = locationProvider?.lastKnownLocation() ?: return metadata
        return metadata.copy(gpsLatitude = location.first, gpsLongitude = location.second)
    }

    private fun fail(request: PhotoCaptureRequest, error: CameraError): PhotoCaptureResult? {
        Log.e(TAG, "capture ${request.captureId.value} failed: $error")
        _captureState.value = CaptureState.Failed(error)
        return null
    }

    /**
     * Develop-path failures are processing failures unless the exception already
     * carries a typed domain error; the shared mapper supplies the known
     * [CameraException]/IO/session cases (AGENTS 56).
     */
    private fun Exception.toCaptureError(): CameraError =
        toCameraError { CameraError.ProcessingFailed(it) }
}

private const val TAG = "NaturalCam"

/** Bounded waits so a wedged backend can never disable the shutter permanently (AGENTS 40). */
private const val CAPTURE_TIMEOUT_MS = 30_000L
private const val PROCESSING_TIMEOUT_MS = 180_000L

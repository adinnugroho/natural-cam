package com.adin.naturalcam.capture

import android.os.SystemClock
import android.util.Log

import com.adin.naturalcam.BuildConfig
import com.adin.naturalcam.camera.CameraController
import com.adin.naturalcam.camera.CameraException
import com.adin.naturalcam.camera.CapturedFrames
import com.adin.naturalcam.domain.CameraError
import com.adin.naturalcam.domain.CapturePlan
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.PhotoCaptureRequest
import com.adin.naturalcam.domain.PhotoCaptureResult
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.SavedPhoto
import com.adin.naturalcam.image.core.ImagePipeline
import com.adin.naturalcam.image.core.ProcessingConfiguration
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Owns the capture state machine (SPEC 68–69): IDLE → PREPARING → CAPTURING →
 * PROCESSING → SAVING → COMPLETE → IDLE, with failures landing in a
 * recoverable state (AGENTS 40). Processing is bounded; when the queue is
 * full new captures are refused before acknowledgement instead of silently
 * dropped (AGENTS 42).
 */
class CaptureCoordinator(
    private val controller: CameraController,
    private val pipeline: ImagePipeline,
    private val mediaStore: MediaStoreWriter,
    private val locationProvider: LocationProvider? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxConcurrentJobs: Int = 2,
) {
    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val gate = Semaphore(maxConcurrentJobs)

    /** Runs one shutter event end-to-end. Returns null when the state holds the error. */
    suspend fun capture(request: PhotoCaptureRequest): PhotoCaptureResult? {
        if (!gate.tryAcquire()) {
            _captureState.value = CaptureState.Failed(
                CameraError.CaptureFailed("processing queue full; capture refused before acknowledgement"),
            )
            return null
        }
        try {
            _captureState.value = CaptureState.Preparing
            val capabilities = controller.capabilities[request.cameraId]
            if (capabilities == null) {
                return fail(request, CameraError.CameraUnavailable("unknown camera ${request.cameraId.value}"))
            }
            val plan = CapturePathResolver.resolve(capabilities, request.profile, request.rawMode)
            // SPEC 109 debug facts: identity, plan, profile, pipeline — never pixels or GPS (AGENTS 55).
            Log.d(
                TAG,
                "capture ${request.captureId.value} camera=${request.cameraId.value} profile=${request.profile} " +
                    "rawMode=${request.rawMode} plan=${plan.source}/${plan.pipeline} saveRaw=${plan.saveRaw} " +
                    "limitations=${plan.limitations}",
            )

            _captureState.value = CaptureState.Capturing
            val captureStart = SystemClock.elapsedRealtime()
            val frames = try {
                // Bounded waits (AGENTS 40): a wedged HAL must not disable the shutter forever.
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { controller.capture(request, plan) }
                    ?: return fail(request, CameraError.CaptureFailed("capture timed out after ${CAPTURE_TIMEOUT_MS / 1000}s"))
            } catch (e: Exception) {
                return fail(request, e.toCameraError())
            }
            val captureMs = SystemClock.elapsedRealtime() - captureStart

            _captureState.value = CaptureState.Processing(null)
            val processStart = SystemClock.elapsedRealtime()
            val result = try {
                // Bounds cooperative waits (MediaStore I/O). Note: pure CPU work in
                // processAndSave has no suspension points and cannot be preempted —
                // its bound is performance (see LIMITATIONS.md), not this timeout.
                withTimeoutOrNull(PROCESSING_TIMEOUT_MS) {
                    withContext(ioDispatcher) { processAndSave(request, plan, frames) }
                } ?: return fail(request, CameraError.ProcessingFailed("processing timed out after ${PROCESSING_TIMEOUT_MS / 1000}s"))
            } catch (e: Exception) {
                return fail(request, e.toCameraError())
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
        val config = ProcessingConfiguration.forProfile(request.profile, request.outputSettings.jpegQuality, request.temperature, request.style)
        val limitations = plan.limitations
        // Developed JPEGs are physically rotated and HAL JPEGs arrive rotated by
        // the platform, so EXIF orientation is normal for every final JPEG (SPEC 89).
        val jpegMetadata = metadata.copy(orientationDegrees = 0)

        return when {
            frames.rawDngFile != null -> processRawCapture(request, plan, frames, metadata, config)
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
                val encoded = pipeline.processYuv(frames.yuv, config, metadata.orientationDegrees)
                _captureState.value = CaptureState.Saving
                val photo = mediaStore.saveJpeg(
                    bytes = encoded.bytes,
                    fileName = FileNaming.jpegName(metadata.timestampMs),
                    metadata = jpegMetadata,
                    locationTagging = request.outputSettings.locationTagging,
                ) ?: throw CameraStorageException()
                PhotoCaptureResult(photo, null, metadata, request.profile, config.pipelineVersion, limitations)
            }
            else -> {
                // Platform-processed fallback (or SYSTEM output): pass the bytes through
                // with only honest metadata written (AGENTS 32).
                val bytes = frames.halJpegBytes ?: frames.jpegBytes
                    ?: throw CameraProcessingException("no captured payload")
                _captureState.value = CaptureState.Saving
                val photo = mediaStore.saveJpeg(
                    bytes = bytes,
                    fileName = FileNaming.jpegName(metadata.timestampMs),
                    metadata = jpegMetadata,
                    locationTagging = request.outputSettings.locationTagging,
                ) ?: throw CameraStorageException()
                PhotoCaptureResult(photo, null, metadata, request.profile, config.pipelineVersion, limitations)
            }
        }
    }

    private suspend fun processRawCapture(
        request: PhotoCaptureRequest,
        plan: CapturePlan,
        frames: CapturedFrames,
        metadata: com.adin.naturalcam.domain.CaptureMetadata,
        config: ProcessingConfiguration,
    ): PhotoCaptureResult {
        val dngFile = frames.rawDngFile!!
        try {
            _captureState.value = CaptureState.Saving

            // One capture clock for both names so the RAW/JPEG pair shares its base
            // identifier (SPEC 86); EXIF keeps the DNG's own timestamp.
            val nameTimestamp = metadata.timestampMs

            // RAW survives even if later processing fails (SPEC 105): save first.
            val rawPhoto = if (plan.saveRaw) {
                mediaStore.saveDng(dngFile.readBytes(), FileNaming.dngName(nameTimestamp), metadata)
                    ?: throw CameraStorageException()
            } else {
                null
            }

            if (request.rawMode == RawMode.RAW_ONLY) {
                // The DNG itself carries the authoritative capture metadata; no decode needed.
                return PhotoCaptureResult(
                    null, rawPhoto, metadata, request.profile, config.pipelineVersion, plan.limitations,
                )
            }

            val rawImage = try {
                DngReader.read(dngFile)
            } catch (e: DngReader.DngFormatException) {
                throw CameraProcessingException("DNG decode failed: ${e.message}")
            }

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
                    ?: throw CameraProcessingException("SYSTEM capture missing processed JPEG")
                val photo = mediaStore.saveJpeg(
                    halJpeg, FileNaming.jpegName(nameTimestamp),
                    fullMetadata.copy(orientationDegrees = 0),
                    request.outputSettings.locationTagging,
                ) ?: throw CameraStorageException()
                return PhotoCaptureResult(photo, rawPhoto, fullMetadata, request.profile, config.pipelineVersion, plan.limitations)
            }

            val encoded = pipeline.processRaw(rawImage, config)
            _captureState.value = CaptureState.Saving
            val photo = mediaStore.saveJpeg(
                encoded.bytes, FileNaming.jpegName(nameTimestamp),
                fullMetadata.copy(orientationDegrees = 0),
                request.outputSettings.locationTagging,
            ) ?: throw CameraStorageException()
            return PhotoCaptureResult(photo, rawPhoto, fullMetadata, request.profile, config.pipelineVersion, plan.limitations)
        } finally {
            // The cached DNG is temporary in every mode (AGENTS 51).
            dngFile.delete()
        }
    }

    private fun withLocation(
        metadata: com.adin.naturalcam.domain.CaptureMetadata,
        locationTagging: Boolean,
    ): com.adin.naturalcam.domain.CaptureMetadata {
        if (!locationTagging) return metadata
        val location = locationProvider?.lastKnownLocation() ?: return metadata
        return metadata.copy(gpsLatitude = location.first, gpsLongitude = location.second)
    }

    private fun fail(request: PhotoCaptureRequest, error: CameraError): PhotoCaptureResult? {
        Log.e(TAG, "capture ${request.captureId.value} failed: $error")
        _captureState.value = CaptureState.Failed(error)
        return null
    }

    private fun Exception.toCameraError(): CameraError = when (this) {
        is CameraException -> error
        is CameraProcessingException -> CameraError.ProcessingFailed(message)
        is CameraStorageException -> CameraError.StorageFailed(message)
        is DngReader.DngFormatException -> CameraError.ProcessingFailed(message)
        is IOException -> CameraError.StorageFailed(message)
        else -> CameraError.ProcessingFailed(message ?: javaClass.simpleName)
    }
}

private class CameraProcessingException(message: String) : Exception(message)
private class CameraStorageException : Exception("MediaStore write failed")

private const val TAG = "NaturalCam"

/** Bounded waits so a wedged backend can never disable the shutter permanently (AGENTS 40). */
private const val CAPTURE_TIMEOUT_MS = 30_000L
private const val PROCESSING_TIMEOUT_MS = 180_000L

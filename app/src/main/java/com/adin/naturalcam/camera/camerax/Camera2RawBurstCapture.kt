package com.adin.naturalcam.camera.camerax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.params.ColorSpaceTransform
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Rational
import android.util.Size
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.RawCaptureMetadata
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.storage.FileNaming
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Direct Camera2 RAW+JPEG burst capture used when CameraX would force DNG disk round-trips. */
internal class Camera2RawBurstCapture(
    private val context: Context,
    private val capturesDir: File,
) {
    data class Result(
        val dngFiles: List<File>,
        val rawImages: List<RawImage>,
        val previewJpeg: ByteArray?,
    )

    suspend fun capture(
        cameraId: String,
        captureId: String,
        frameCount: Int,
        rawSize: Size,
        jpegSize: Size,
        exposureCompensationEv: Float,
        bracketOffsetsEv: FloatArray,
        rotation: Int,
    ): Result = suspendCancellableCoroutine { continuation ->
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("camera2-raw-burst").apply { start() }
        val handler = Handler(thread.looper)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val rawReader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            frameCount,
        )
        val jpegReader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            frameCount,
        )
        val dngFiles = (0 until frameCount).map { index ->
            File(capturesDir, FileNaming.tempName("$captureId-$index", ".dng"))
        }
        val rawImages = arrayOfNulls<RawImage>(frameCount)
        val pending = HashMap<Long, PendingFrame>()
        var session: CameraCaptureSession? = null
        var device: CameraDevice? = null
        var completed = 0
        var previewJpeg: ByteArray? = null
        val finished = AtomicBoolean(false)

        fun closeResources() {
            session?.close()
            device?.close()
            rawReader.close()
            jpegReader.close()
            thread.quitSafely()
            pending.values.forEach { entry ->
                entry.raw?.close()
                entry.jpeg?.close()
            }
            pending.clear()
        }

        fun fail(error: Throwable) {
            if (!finished.compareAndSet(false, true)) return
            closeResources()
            dngFiles.forEach(File::delete)
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        fun completeIfReady(timestamp: Long) {
            val entry = pending[timestamp] ?: return
            val result = entry.result ?: return
            val raw = entry.raw ?: return
            val jpeg = entry.jpeg ?: return
            val index = (result.request.tag as? Int)?.coerceIn(0, frameCount - 1) ?: completed
            try {
                rawImages[index] = rawImage(raw, result, characteristics, rotation)
                capturesDir.mkdirs()
                FileOutputStream(dngFiles[index]).use { output ->
                    DngCreator(characteristics, result).writeImage(output, raw)
                }
                if (index == frameCount / 2) previewJpeg = jpegBytes(jpeg)
                raw.close()
                jpeg.close()
                pending.remove(timestamp)
                completed++
                if (completed == frameCount && finished.compareAndSet(false, true)) {
                    closeResources()
                    if (continuation.isActive) {
                        continuation.resume(
                            Result(dngFiles, rawImages.map { requireNotNull(it) }, previewJpeg),
                        )
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }

        fun onRaw(image: Image) {
            synchronized(pending) {
                pending.getOrPut(image.timestamp) { PendingFrame() }.raw = image
                completeIfReady(image.timestamp)
            }
        }

        fun onJpeg(image: Image) {
            synchronized(pending) {
                pending.getOrPut(image.timestamp) { PendingFrame() }.jpeg = image
                completeIfReady(image.timestamp)
            }
        }

        fun onResult(result: TotalCaptureResult) {
            val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                ?: return
            synchronized(pending) {
                pending.getOrPut(timestamp) { PendingFrame() }.result = result
                completeIfReady(timestamp)
            }
        }

        rawReader.setOnImageAvailableListener({ reader ->
            runCatching { reader.acquireNextImage() }.getOrNull()?.let(::onRaw)
        }, handler)
        jpegReader.setOnImageAvailableListener({ reader ->
            runCatching { reader.acquireNextImage() }.getOrNull()?.let(::onJpeg)
        }, handler)

        continuation.invokeOnCancellation { fail(java.util.concurrent.CancellationException("capture cancelled")) }
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "camera permission denied"
        }
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    device = opened
                    opened.createCaptureSession(
                        listOf(rawReader.surface, jpegReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                val requests = (0 until frameCount).map { index ->
                                    val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(rawReader.surface)
                                        addTarget(jpegReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(
                                            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                            compensationIndex(
                                                characteristics,
                                                exposureCompensationEv + bracketOffsetsEv[index.coerceIn(0, bracketOffsetsEv.lastIndex)],
                                            ),
                                        )
                                        setTag(index)
                                    }
                                    request.build()
                                }
                                configured.captureBurst(
                                    requests,
                                    object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult,
                                        ) = onResult(result)

                                        override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: CaptureFailure,
                                        ) = fail(IllegalStateException("RAW burst frame failed: ${failure.reason}"))
                                    },
                                    handler,
                                )
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) =
                                fail(IllegalStateException("Camera2 RAW burst session configuration failed"))
                        },
                        handler,
                    )
                }

                override fun onDisconnected(disconnected: CameraDevice) =
                    fail(IllegalStateException("Camera2 disconnected during RAW burst"))

                override fun onError(failed: CameraDevice, error: Int) =
                    fail(IllegalStateException("Camera2 RAW burst open failed: $error"))
            }, handler)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun rawImage(
        image: Image,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        rotation: Int,
    ): RawImage {
        val width = image.width
        val height = image.height
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val pixels = ShortArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * plane.rowStride
            for (x in 0 until width) {
                pixels[y * width + x] = buffer
                    .getShort(rowOffset + x * plane.pixelStride)
            }
        }
        val cfa = when (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            0 -> CfaLayout.RGGB
            1 -> CfaLayout.GRBG
            2 -> CfaLayout.GBRG
            3 -> CfaLayout.BGGR
            else -> CfaLayout.RGGB
        }
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = FloatArray(3)
        val blackCount = IntArray(3)
        for (y in 0..1) for (x in 0..1) {
            val channel = cfa.channelAt(x, y)
            black[channel] += blackPattern?.getOffsetForIndex(x, y)?.toFloat() ?: 0f
            blackCount[channel]++
        }
        for (channel in black.indices) {
            black[channel] /= blackCount[channel].coerceAtLeast(1)
        }
        val metadata = RawCaptureMetadata(
            cfa = cfa,
            blackLevelPerChannel = black,
            whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 65535,
            colorMatrix1 = transform(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
            colorMatrix2 = transform(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
            forwardMatrix1 = transform(characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
            forwardMatrix2 = transform(characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
            asShotNeutral = null,
            isoSpeed = result.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            aperture = result.get(CaptureResult.LENS_APERTURE),
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            orientationDegrees = rotation,
            timestampMs = System.currentTimeMillis(),
        )
        return RawImage(width, height, pixels, metadata)
    }

    private fun transform(value: ColorSpaceTransform?): FloatArray? {
        if (value == null) return null
        val elements = Array(9) { Rational(0, 1) }
        value.copyElements(elements, 0)
        return FloatArray(9) { index -> elements[index].toFloat() }
    }

    private fun compensationIndex(characteristics: CameraCharacteristics, ev: Float): Int {
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (step <= 0f || range == null) return 0
        return kotlin.math.round(ev / step).toInt().coerceIn(range.lower, range.upper)
    }

    private fun jpegBytes(image: Image): ByteArray {
        val buffer = image.planes.first().buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private class PendingFrame(
        var result: TotalCaptureResult? = null,
        var raw: Image? = null,
        var jpeg: Image? = null,
    )
}

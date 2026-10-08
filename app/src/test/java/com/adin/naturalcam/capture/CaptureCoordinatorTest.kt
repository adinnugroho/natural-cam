package com.adin.naturalcam.capture

import androidx.camera.view.PreviewView
import com.adin.naturalcam.camera.CameraController
import com.adin.naturalcam.camera.CameraException
import com.adin.naturalcam.camera.CapturedFrames
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraError
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CameraState
import com.adin.naturalcam.domain.CapabilityConfidence
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.CaptureId
import com.adin.naturalcam.domain.CaptureMetadata
import com.adin.naturalcam.domain.CapturePlan
import com.adin.naturalcam.domain.CaptureState
import com.adin.naturalcam.domain.ControlSupport
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.HardwareLevel
import com.adin.naturalcam.domain.ImageSize
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.LensOption
import com.adin.naturalcam.domain.NoiseReductionMode
import com.adin.naturalcam.domain.NormalizedPoint
import com.adin.naturalcam.domain.PhotoCaptureRequest
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.domain.SavedPhoto
import com.adin.naturalcam.image.core.CfaLayout
import com.adin.naturalcam.image.core.EncodedImage
import com.adin.naturalcam.image.core.ImagePipeline
import com.adin.naturalcam.image.core.ProcessingConfiguration
import com.adin.naturalcam.image.core.ProcessingTimings
import com.adin.naturalcam.image.core.RawImage
import com.adin.naturalcam.image.core.YuvImage
import com.adin.naturalcam.image.raw.TestDngFactory
import com.adin.naturalcam.storage.MediaStoreWriter
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Capture state machine, backpressure and RAW-retention behavior (SPEC 68–69,
 * 105; AGENTS 40, 42). Runs on the JVM with fake backend/pipeline/storage.
 */
class CaptureCoordinatorTest {

    // ---- fakes ----

    private inner class FakeController(
        caps: Map<CameraId, CameraCapabilities>,
    ) : CameraController {
        override val state: StateFlow<CameraState> = MutableStateFlow(CameraState.Ready(caps.keys.first()))
        override val capabilities: Map<CameraId, CameraCapabilities> = caps
        override val lenses: List<LensOption> = emptyList()
        override val zoomRatio: Float = 1f

        var frames: CapturedFrames? = null

        /** Per-capture payload, for tests that run two captures at once. */
        val framesById = mutableMapOf<CaptureId, CapturedFrames>()
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var captureCount = 0

        override suspend fun initialize(): List<LensOption> = emptyList()
        override fun attachPreview(previewView: PreviewView) {}
        override suspend fun selectCamera(cameraId: CameraId) {}
        override suspend fun setExposureCompensation(ev: Float) {}
        override suspend fun setAspectRatio(aspectRatio: com.adin.naturalcam.domain.AspectRatio) {}
        override suspend fun setHighestResolution(enabled: Boolean) {}
        override suspend fun setZoom(zoomRatio: Float) {}
        override suspend fun focusAt(point: NormalizedPoint) {}
        override suspend fun lockFocus(point: NormalizedPoint) {}
        override suspend fun unlockFocus() {}
        override suspend fun capture(request: PhotoCaptureRequest, plan: CapturePlan): CapturedFrames {
            gate?.await()
            captureCount++
            error?.let { throw it }
            return framesById[request.captureId] ?: frames ?: yuvFrames(request.captureId)
        }

        override suspend fun close() {}
    }

    private class FakePipeline : ImagePipeline {
        override val version: String = "test-v1"
        var failWith: Exception? = null
        var processedRaw = 0
        var processedYuv = 0

        override fun processRaw(
            raw: RawImage,
            config: ProcessingConfiguration,
            timings: ProcessingTimings?,
        ): EncodedImage {
            failWith?.let { throw it }
            processedRaw++
            timings?.record("demosaic", 1)
            return EncodedImage(ByteArray(4) { 1 }, raw.width, raw.height)
        }

        override fun processYuv(
            yuv: YuvImage,
            config: ProcessingConfiguration,
            orientationDegrees: Int,
            timings: ProcessingTimings?,
        ): EncodedImage {
            failWith?.let { throw it }
            processedYuv++
            return EncodedImage(ByteArray(4) { 2 }, yuv.width, yuv.height)
        }
    }

    private class FakeMediaStore : MediaStoreWriter {
        val savedJpegs = mutableListOf<String>()
        val savedDngs = mutableListOf<String>()
        var fail = false

        /** Suspends the first save so a test can hold the develop gate open. */
        var saveGate: CompletableDeferred<Unit>? = null

        override suspend fun saveJpeg(
            bytes: ByteArray,
            fileName: String,
            metadata: CaptureMetadata,
            locationTagging: Boolean,
        ): SavedPhoto? {
            saveGate?.await()
            if (fail) return null
            savedJpegs += fileName
            return SavedPhoto("content://test/$fileName", fileName, 4, 4, "image/jpeg")
        }

        override suspend fun saveDng(bytes: ByteArray, fileName: String, metadata: CaptureMetadata): SavedPhoto? {
            saveGate?.await()
            if (fail) return null
            savedDngs += fileName
            return SavedPhoto("content://test/$fileName", fileName, 4, 4, "image/x-adobe-dng")
        }

        override suspend fun delete(uri: Uri) {}
    }

    private fun yuvFrames(captureId: CaptureId) = CapturedFrames(
        captureId = captureId,
        metadata = baseline(captureId),
        rawDngFile = null,
        yuv = YuvImage(
            width = 4, height = 4,
            yPlane = ByteArray(16), yRowStride = 4, yPixelStride = 1,
            uPlane = ByteArray(4), uRowStride = 2, uPixelStride = 1,
            vPlane = ByteArray(4), vRowStride = 2, vPixelStride = 1,
        ),
        jpegBytes = null,
        halJpegBytes = null,
    )

    private fun rawFrames(captureId: CaptureId, file: File) = CapturedFrames(
        captureId = captureId,
        metadata = baseline(captureId),
        rawDngFile = file,
        yuv = null,
        jpegBytes = null,
        halJpegBytes = null,
    )

    private fun baseline(captureId: CaptureId) = CaptureMetadata(
        captureId = captureId,
        timestampMs = 1_700_000_000_000,
        orientationDegrees = 0,
        iso = null,
        exposureTimeNs = null,
        aperture = null,
        focalLengthMm = null,
        make = null,
        model = null,
        gpsLatitude = null,
        gpsLongitude = null,
    )

    private fun capabilities(raw: Boolean, yuv: Boolean = true) = CameraCapabilities(
        cameraId = CameraId("0"),
        lensFacing = LensFacing.BACK,
        logicalMultiCamera = false,
        physicalCameraIds = emptySet(),
        hardwareLevel = HardwareLevel.FULL,
        rawSupport = if (raw) ControlSupport(CapabilityConfidence.SUPPORTED) else ControlSupport(CapabilityConfidence.UNAVAILABLE),
        manualSensorSupport = ControlSupport(CapabilityConfidence.SUPPORTED),
        manualPostProcessingSupport = ControlSupport(CapabilityConfidence.SUPPORTED),
        noiseReductionModes = setOf(NoiseReductionMode.OFF),
        edgeModes = setOf(EdgeMode.OFF),
        supportedFormats = buildSet {
            if (raw) add(CaptureFormat.RAW_SENSOR)
            if (yuv) add(CaptureFormat.YUV_420_888)
            add(CaptureFormat.JPEG)
        },
        resolutions = mapOf(CaptureFormat.YUV_420_888 to listOf(ImageSize(4, 4))),
        isoRange = 100..3200,
        exposureTimeRangeNs = 1_000_000L..1_000_000_000L,
        exposureCompensationEvRange = -2f..2f,
        exposureCompensationStepEv = 1f / 3f,
        minimumFocusDistanceDiopters = 10f,
        focalLengthsMm = listOf(5.4f),
        zoomRatioRange = 1f..10f,
        flashAvailable = true,
        opticalStabilizationSupported = false,
        sensorOrientation = 90,
    )

    private fun request(
        rawMode: RawMode = RawMode.FINAL_ONLY,
        profile: ProcessingProfile = ProcessingProfile.NATURAL,
        captureId: String = "test-capture",
    ) =
        PhotoCaptureRequest(
            captureId = CaptureId(captureId),
            cameraId = CameraId("0"),
            profile = profile,
            rawMode = rawMode,
            exposureMode = com.adin.naturalcam.domain.ExposureMode.Auto,
            focusMode = com.adin.naturalcam.domain.FocusMode.CONTINUOUS,
            flashMode = com.adin.naturalcam.domain.FlashMode.OFF,
            outputSettings = com.adin.naturalcam.domain.OutputSettings(),
        )

    // ---- tests ----

    @Test
    fun `yuv capture reaches complete and saves jpeg`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = false)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = coordinator.capture(request())
        assertNotNull(result)
        assertEquals(1, pipeline.processedYuv)
        assertEquals(1, store.savedJpegs.size)
        assertTrue(coordinator.captureState.value is CaptureState.Complete)
        coordinator.acknowledge()
        assertTrue(coordinator.captureState.value is CaptureState.Idle)
    }

    @Test
    fun `capture failure is recoverable`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = false)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        controller.error = CameraException(CameraError.CaptureFailed("boom"))
        assertNull(coordinator.capture(request()))
        assertTrue(coordinator.captureState.value is CaptureState.Failed)

        // Second capture must work after the failure (AGENTS 40).
        controller.error = null
        assertNotNull(coordinator.capture(request()))
        assertTrue(coordinator.captureState.value is CaptureState.Complete)
    }

    @Test
    fun `full queue refuses capture before acknowledgement`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = false)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler), maxConcurrentJobs = 1)

        controller.gate = CompletableDeferred()
        val first = launch { coordinator.capture(request()) }
        testScheduler.runCurrent()

        assertNull(coordinator.capture(request()))
        val failed = coordinator.captureState.value as CaptureState.Failed
        assertTrue(failed.error.detail!!.contains("queue full"))
        assertFalse("refused capture must not reach the camera", controller.captureCount > 1)

        controller.gate?.complete(Unit)
        first.join()
        assertTrue(coordinator.captureState.value is CaptureState.Complete)
    }

    @Test
    fun `shutter accepts a second capture while the first is in flight`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = false)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        // Default bound: one developing plus one accepted.
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        controller.gate = CompletableDeferred()
        val first = launch { coordinator.capture(request()) }
        testScheduler.runCurrent()
        assertEquals(1, coordinator.jobsInFlight.value)

        // A point-and-shoot camera must not lock the shutter for the seconds a develop takes:
        // the second job is accepted while the first is still in flight (AGENTS 42 bounds it).
        val second = launch { coordinator.capture(request()) }
        testScheduler.runCurrent()
        assertEquals(2, coordinator.jobsInFlight.value)

        controller.gate?.complete(Unit)
        first.join()
        second.join()
        assertEquals("both accepted captures reached the camera", 2, controller.captureCount)
        assertEquals("every accepted job releases its slot", 0, coordinator.jobsInFlight.value)
        assertEquals("both frames were developed and saved", 2, store.savedJpegs.size)
    }

    @Test
    fun `raw only saves raw without decoding pixels`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = true)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        // Deliberately not a real DNG: RAW_ONLY must never decode it.
        val junkDng = File.createTempFile("junk", ".dng").apply { writeBytes(ByteArray(8)) }
        controller.frames = rawFrames(CaptureId("test-capture"), junkDng)

        val result = coordinator.capture(request(rawMode = RawMode.RAW_ONLY))
        assertNotNull(result)
        assertNull(result!!.finalPhoto)
        assertNotNull(result.rawPhoto)
        assertEquals(0, pipeline.processedRaw)
        assertEquals(1, store.savedDngs.size)
        assertFalse("temp DNG must be cleaned up (AGENTS 51)", junkDng.exists())
    }

    @Test
    fun `raw is retained when processing fails afterwards`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = true)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        val dng = TestDngFactory.buildDng(CfaLayout.RGGB)
        controller.frames = rawFrames(CaptureId("test-capture"), dng)
        pipeline.failWith = RuntimeException("tone mapper exploded")

        assertNull(coordinator.capture(request(rawMode = RawMode.RAW_AND_FINAL)))
        assertTrue(coordinator.captureState.value is CaptureState.Failed)
        assertEquals("RAW must be saved before processing (SPEC 105)", 1, store.savedDngs.size)
        assertEquals(0, store.savedJpegs.size)
        assertFalse(dng.exists())
    }

    @Test
    fun `system with raw mode saves both raw and hal jpeg`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = true)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        val dng = TestDngFactory.buildDng(CfaLayout.RGGB)
        controller.frames = rawFrames(CaptureId("test-capture"), dng).let {
            CapturedFrames(it.captureId, it.metadata, it.rawDngFile, null, null, ByteArray(4) { 3 })
        }

        val result = coordinator.capture(request(rawMode = RawMode.RAW_AND_FINAL, profile = ProcessingProfile.SYSTEM))
        assertNotNull(result)
        assertEquals("HAL JPEG is the SYSTEM final", 1, store.savedJpegs.size)
        assertEquals("RAW companion must not be dropped", 1, store.savedDngs.size)
        assertEquals(0, pipeline.processedRaw)
    }

    @Test
    fun `natural raw capture develops jpeg from raw`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = true)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        val dng = TestDngFactory.buildDng(CfaLayout.RGGB)
        controller.frames = rawFrames(CaptureId("test-capture"), dng)

        val result = coordinator.capture(request(rawMode = RawMode.FINAL_ONLY))
        assertNotNull(result)
        assertEquals(1, pipeline.processedRaw)
        assertEquals(1, store.savedJpegs.size)
        assertEquals("temp DNG is not kept in FINAL_ONLY mode", 0, store.savedDngs.size)
    }

    @Test
    fun `cancelling a capture queued behind the develop gate cleans its temp raw`() = runTest {
        val controller = FakeController(mapOf(CameraId("0") to capabilities(raw = true)))
        val pipeline = FakePipeline()
        val store = FakeMediaStore()
        val coordinator = CaptureCoordinator(controller, pipeline, store, processingDispatcher = UnconfinedTestDispatcher(testScheduler))

        val firstDng = TestDngFactory.buildDng(CfaLayout.RGGB)
        val secondDng = TestDngFactory.buildDng(CfaLayout.RGGB)
        controller.framesById[CaptureId("first")] = rawFrames(CaptureId("first"), firstDng)
        controller.framesById[CaptureId("second")] = rawFrames(CaptureId("second"), secondDng)

        // The first develop holds the gate while it is inside its RAW save.
        store.saveGate = CompletableDeferred()
        val first = launch { coordinator.capture(request(RawMode.RAW_AND_FINAL, captureId = "first")) }
        testScheduler.runCurrent()
        val second = launch { coordinator.capture(request(RawMode.RAW_AND_FINAL, captureId = "second")) }
        testScheduler.runCurrent()
        assertEquals("both captures were accepted", 2, coordinator.jobsInFlight.value)
        assertEquals("only the first develop reached storage", 0, store.savedDngs.size)

        // Cancel while it waits for the gate: that path never enters processRawCapture, so
        // the temp RAW must be cleaned here, and a cancelled job is not a failed capture.
        second.cancelAndJoin()

        assertTrue(
            "cancellation must not surface as a capture failure",
            coordinator.captureState.value !is CaptureState.Failed,
        )
        assertFalse("queued capture must clean its temp RAW (AGENTS 51)", secondDng.exists())
        assertEquals("the cancelled job released its slot", 1, coordinator.jobsInFlight.value)

        store.saveGate?.complete(Unit)
        first.join()
        assertFalse("a completed develop cleans its temp RAW too", firstDng.exists())
        assertEquals(0, coordinator.jobsInFlight.value)
    }
}

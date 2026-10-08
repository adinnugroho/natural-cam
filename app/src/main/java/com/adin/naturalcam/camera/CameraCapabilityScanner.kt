package com.adin.naturalcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.graphics.ImageFormat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import com.adin.naturalcam.camera.camerax.IspOptionMapping
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CameraId
import com.adin.naturalcam.domain.CapabilityConfidence
import com.adin.naturalcam.domain.CaptureFormat
import com.adin.naturalcam.domain.ControlSupport
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.HardwareLevel
import com.adin.naturalcam.domain.ImageSize
import com.adin.naturalcam.domain.LensFacing
import com.adin.naturalcam.domain.NoiseReductionMode

/**
 * Per-camera capability discovery from real Camera2 metadata (AGENTS 9, 10).
 * Everything is SUPPORTED (advertised) or UNAVAILABLE/UNKNOWN at scan time;
 * CONFIRMED is only recorded after verified device behavior (SPEC 12).
 */
@androidx.annotation.OptIn(
    androidx.camera.camera2.interop.ExperimentalCamera2Interop::class,
    androidx.camera.core.ExperimentalLensFacing::class,
)
object CameraCapabilityScanner {

    fun scan(cameraInfo: CameraInfo): CameraCapabilities {
        val chars = Camera2CameraInfo.from(cameraInfo)
        val cameraId = CameraId(chars.cameraId)
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val rawAdvertised = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
        val yuvSizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

        val physicalIds = cameraInfo.physicalCameraInfos
            .map { CameraId(Camera2CameraInfo.from(it).cameraId) }
            .toSet()
        val rawSupport = when {
            rawAdvertised && rawSizes.isNotEmpty() -> ControlSupport(
                CapabilityConfidence.SUPPORTED,
                note = "RAW capture reported by device metadata",
            )
            rawAdvertised -> ControlSupport(CapabilityConfidence.UNKNOWN, note = "RAW capability reported but no RAW stream sizes")
            else -> ControlSupport(CapabilityConfidence.UNAVAILABLE)
        }

        return CameraCapabilities(
            cameraId = cameraId,
            lensFacing = cameraInfo.lensFacing.toDomain(),
            logicalMultiCamera = physicalIds.isNotEmpty(),
            physicalCameraIds = physicalIds,
            hardwareLevel = chars.hardwareLevel(),
            rawSupport = rawSupport,
            manualSensorSupport = if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                ControlSupport(CapabilityConfidence.SUPPORTED)
            } else {
                ControlSupport(CapabilityConfidence.UNAVAILABLE)
            },
            manualPostProcessingSupport = if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
                ControlSupport(CapabilityConfidence.SUPPORTED)
            } else {
                ControlSupport(CapabilityConfidence.UNAVAILABLE)
            },
            noiseReductionModes = chars.noiseReductionModes(),
            edgeModes = chars.edgeModes(),
            supportedFormats = buildSet {
                if (jpegSizes.isNotEmpty()) add(CaptureFormat.JPEG)
                if (yuvSizes.isNotEmpty()) add(CaptureFormat.YUV_420_888)
                if (rawAdvertised && rawSizes.isNotEmpty()) add(CaptureFormat.RAW_SENSOR)
            },
            resolutions = mapOf(
                CaptureFormat.JPEG to jpegSizes.map { ImageSize(it.width, it.height) },
                CaptureFormat.YUV_420_888 to yuvSizes.map { ImageSize(it.width, it.height) },
                CaptureFormat.RAW_SENSOR to rawSizes.map { ImageSize(it.width, it.height) },
            ).filterValues { it.isNotEmpty() },
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
            exposureTimeRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
            exposureCompensationEvRange = evRange(chars),
            exposureCompensationStepEv = evStep(chars),
            minimumFocusDistanceDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0f },
            focalLengthsMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            zoomRatioRange = zoomRatioRange(chars),
            flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            opticalStabilizationSupported = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
        )
    }

    /** Typed access to one static characteristic (CameraX 1.6.2 interop API). */
    private fun <T> Camera2CameraInfo.get(key: CameraCharacteristics.Key<T>): T? =
        getCameraCharacteristic(key)

    private fun evRange(chars: Camera2CameraInfo): ClosedFloatingPointRange<Float>? {
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
        val step = evStep(chars)
        return range.lower * step..range.upper * step
    }

    private fun evStep(chars: Camera2CameraInfo): Float =
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f / 3f

    /** `android.control.zoomRatioRange`; null below API 30, which has no such key. */
    private fun zoomRatioRange(chars: Camera2CameraInfo): ClosedFloatingPointRange<Float>? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return null
        return range.lower..range.upper
    }

    private fun Camera2CameraInfo.hardwareLevel(): HardwareLevel =
        when (get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
            else -> HardwareLevel.UNKNOWN
        }

    private fun Camera2CameraInfo.noiseReductionModes(): Set<NoiseReductionMode> =
        (get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf())
            .mapTo(mutableSetOf(), IspOptionMapping::noiseReductionFromCamera2)

    private fun Camera2CameraInfo.edgeModes(): Set<EdgeMode> =
        (get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf())
            .mapTo(mutableSetOf(), IspOptionMapping::edgeFromCamera2)

    private fun Int.toDomain(): LensFacing = when (this) {
        CameraSelector.LENS_FACING_BACK -> LensFacing.BACK
        CameraSelector.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraSelector.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
        else -> LensFacing.UNKNOWN
    }
}

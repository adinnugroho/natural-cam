package com.adin.naturalcam.camera.camerax

import android.hardware.camera2.CameraMetadata
import com.adin.naturalcam.domain.EdgeMode
import com.adin.naturalcam.domain.NoiseReductionMode

/**
 * The one mapping between the domain ISP modes and the Camera2 metadata constants.
 *
 * The capability scanner and the capture-request builder each carried a hand-written
 * copy of these tables, in opposite directions, which had to be kept in step by hand
 * (SPEC 23 — PURE asks the ISP for the least processing the hardware reports).
 */
internal object IspOptionMapping {

    fun noiseReductionToCamera2(mode: NoiseReductionMode): Int? = when (mode) {
        NoiseReductionMode.OFF -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
        NoiseReductionMode.MINIMAL -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        NoiseReductionMode.FAST -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        NoiseReductionMode.HIGH_QUALITY -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        NoiseReductionMode.ZERO_SHUTTER_LAG -> CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
        NoiseReductionMode.UNKNOWN -> null
    }

    fun noiseReductionFromCamera2(value: Int): NoiseReductionMode = when (value) {
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> NoiseReductionMode.OFF
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> NoiseReductionMode.MINIMAL
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> NoiseReductionMode.FAST
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> NoiseReductionMode.HIGH_QUALITY
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> NoiseReductionMode.ZERO_SHUTTER_LAG
        else -> NoiseReductionMode.UNKNOWN
    }

    fun edgeToCamera2(mode: EdgeMode): Int? = when (mode) {
        EdgeMode.OFF -> CameraMetadata.EDGE_MODE_OFF
        EdgeMode.FAST -> CameraMetadata.EDGE_MODE_FAST
        EdgeMode.HIGH_QUALITY -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
        EdgeMode.ZERO_SHUTTER_LAG -> CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG
        EdgeMode.UNKNOWN -> null
    }

    fun edgeFromCamera2(value: Int): EdgeMode = when (value) {
        CameraMetadata.EDGE_MODE_OFF -> EdgeMode.OFF
        CameraMetadata.EDGE_MODE_FAST -> EdgeMode.FAST
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> EdgeMode.HIGH_QUALITY
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> EdgeMode.ZERO_SHUTTER_LAG
        else -> EdgeMode.UNKNOWN
    }
}

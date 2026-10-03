package com.adin.naturalcam.image.core

/** Sensor CFA layouts (AGENTS 19 — never hardcode RGGB). Channel indices: 0=R, 1=G, 2=B. */
enum class CfaLayout {
    RGGB, GRBG, GBRG, BGGR;

    /** Channel sampled at sensor position (x, y). */
    fun channelAt(x: Int, y: Int): Int {
        val row = if (y and 1 == 0) 0 else 1
        val col = if (x and 1 == 0) 0 else 1
        return when (this) {
            RGGB -> if (row == 0) if (col == 0) 0 else 1 else if (col == 0) 1 else 2
            GRBG -> if (row == 0) if (col == 0) 1 else 0 else if (col == 0) 2 else 1
            GBRG -> if (row == 0) if (col == 0) 1 else 2 else if (col == 0) 0 else 1
            BGGR -> if (row == 0) if (col == 0) 2 else 1 else if (col == 0) 1 else 0
        }
    }
}

/**
 * Metadata required to develop RAW correctly (AGENTS 18). All fields come from
 * capture/static metadata; missing values stay null — never invented (AGENTS 52).
 */
data class RawCaptureMetadata(
    val cfa: CfaLayout,
    val blackLevelPerChannel: FloatArray,
    val whiteLevel: Int,
    /** DNG ColorMatrixN: row-major 3x3, maps XYZ to camera native space. */
    val colorMatrix1: FloatArray?,
    val colorMatrix2: FloatArray?,
    /** DNG ForwardMatrixN: row-major 3x3, maps white-balanced camera space to XYZ D50. */
    val forwardMatrix1: FloatArray?,
    val forwardMatrix2: FloatArray?,
    /** DNG AsShotNeutral: per-channel neutral multipliers (R, G, B order of CFA channels). */
    val asShotNeutral: FloatArray?,
    val isoSpeed: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float?,
    val focalLengthMm: Float?,
    val orientationDegrees: Int,
    val timestampMs: Long,
    /** DNG CalibrationIlluminantN codes paired with the color/forward matrices. */
    val calibrationIlluminant1: Int? = null,
    val calibrationIlluminant2: Int? = null,
) {
    init {
        require(whiteLevel > 0) { "whiteLevel must be positive" }
        require(blackLevelPerChannel.isNotEmpty() && blackLevelPerChannel.size <= 4)
    }

    override fun equals(other: Any?): Boolean = this === other ||
        other is RawCaptureMetadata && cfa == other.cfa && whiteLevel == other.whiteLevel &&
        blackLevelPerChannel.contentEquals(other.blackLevelPerChannel) &&
        colorMatrix1.contentEqualsNullable(other.colorMatrix1) &&
        colorMatrix2.contentEqualsNullable(other.colorMatrix2) &&
        forwardMatrix1.contentEqualsNullable(other.forwardMatrix1) &&
        forwardMatrix2.contentEqualsNullable(other.forwardMatrix2) &&
        asShotNeutral.contentEqualsNullable(other.asShotNeutral) &&
        isoSpeed == other.isoSpeed && exposureTimeNs == other.exposureTimeNs &&
        aperture == other.aperture && focalLengthMm == other.focalLengthMm &&
        orientationDegrees == other.orientationDegrees && timestampMs == other.timestampMs &&
        calibrationIlluminant1 == other.calibrationIlluminant1 &&
        calibrationIlluminant2 == other.calibrationIlluminant2

    override fun hashCode(): Int = cfa.hashCode() * 31 + whiteLevel
}

internal fun FloatArray?.contentEqualsNullable(other: FloatArray?): Boolean =
    if (this == null) other == null else other != null && contentEquals(other)

/** Full-resolution RAW mosaic. Precision preserved past 8-bit (AGENTS 20). */
class RawImage(
    val width: Int,
    val height: Int,
    val pixelData: ShortArray,
    val metadata: RawCaptureMetadata,
) {
    init {
        require(width > 0 && height > 0)
        require(pixelData.size == width * height) {
            "pixelData size ${pixelData.size} != ${width * height}"
        }
    }
}

/** Black/white-level normalized Bayer mosaic, values in [0, 1] (may exceed 1 pre-clip). */
class BayerImage(
    val width: Int,
    val height: Int,
    val cfa: CfaLayout,
    val values: FloatArray,
) {
    init {
        require(values.size == width * height)
    }

    private val cfaPattern = intArrayOf(
        cfa.channelAt(0, 0),
        cfa.channelAt(1, 0),
        cfa.channelAt(0, 1),
        cfa.channelAt(1, 1),
    )

    fun channelAt(x: Int, y: Int): Int = cfaPattern[((y and 1) shl 1) + (x and 1)]

    /** [c00, c01, c10, c11] channels of the 2x2 CFA tile. */
    fun channelPattern(): IntArray = cfaPattern.copyOf()
}

/**
 * Linear-light RGB image, scene-referred. Working representation for all
 * light-based operations (AGENTS 22).
 */
class RgbImage(val width: Int, val height: Int) {
    val r: FloatArray = FloatArray(width * height)
    val g: FloatArray = FloatArray(width * height)
    val b: FloatArray = FloatArray(width * height)

    init {
        require(width > 0 && height > 0)
    }
}

/**
 * YUV_420_888 planes with real strides (AGENTS 31 — never assume packed planes
 * or equal strides).
 */
class YuvImage(
    val width: Int,
    val height: Int,
    val yPlane: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uPlane: ByteArray,
    val uRowStride: Int,
    val uPixelStride: Int,
    val vPlane: ByteArray,
    val vRowStride: Int,
    val vPixelStride: Int,
) {
    init {
        require(width > 0 && height > 0)
        require(yRowStride >= width && yPixelStride > 0)
        require(uRowStride > 0 && uPixelStride > 0 && vRowStride > 0 && vPixelStride > 0)
    }
}

/** Per-channel white-balance gains applied in linear light. */
data class RgbGains(val r: Float, val g: Float, val b: Float) {
    init {
        require(r > 0f && g > 0f && b > 0f) { "gains must be positive" }
    }

    companion object {
        /** AsShotNeutral → gains: gain = 1/neutral, normalized so G = 1. */
        fun fromAsShotNeutral(neutral: FloatArray): RgbGains {
            require(neutral.size == 3) { "AsShotNeutral must have 3 channels" }
            require(neutral.all { it > 0f }) { "AsShotNeutral must be positive" }
            return RgbGains(
                r = neutral[1] / neutral[0],
                g = 1f,
                b = neutral[1] / neutral[2],
            )
        }
    }
}

package com.adin.naturalcam.storage

import androidx.exifinterface.media.ExifInterface
import com.adin.naturalcam.domain.CaptureMetadata
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes only metadata actually known from capture (AGENTS 52); unknown values are
 * omitted, never invented. GPS tags are written only when the user enabled
 * geotagging (AGENTS 53).
 */
object ExifMetadataWriter {

    /** EXIF-applied bytes plus the dimensions from the same parse (avoids re-reading multi-MB buffers). */
    data class Result(val bytes: ByteArray, val width: Int, val height: Int)

    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS is the tag the contract names.
    fun apply(bytes: ByteArray, metadata: CaptureMetadata, locationTagging: Boolean): Result {
        // ExifInterface.saveAttributes() only writes to a file path or seekable FD —
        // a stream-only instance throws IOException — so bytes round-trip through a
        // temp file that is always deleted (AGENTS 51).
        val file = File.createTempFile("exif", ".jpg")
        try {
            file.writeBytes(bytes)
            val exif = ExifInterface(file.absolutePath)

            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(metadata.timestampMs)),
            )
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationValue(metadata.orientationDegrees))
            metadata.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
            metadata.exposureTimeNs?.takeIf { it > 0 }?.let { ns ->
                val seconds = ns / 1_000_000_000.0
                // Sub-second as "1/x", longer as seconds; the tag accepts both forms.
                exif.setAttribute(
                    ExifInterface.TAG_EXPOSURE_TIME,
                    if (seconds < 1.0) "1/${(1.0 / seconds).roundToInt()}" else seconds.toString(),
                )
            }
            metadata.aperture?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
            metadata.focalLengthMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
            metadata.make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
            metadata.model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }

            val lat = metadata.gpsLatitude
            val lon = metadata.gpsLongitude
            if (locationTagging && lat != null && lon != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(lon))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
            }

            exif.saveAttributes()
            return Result(
                bytes = file.readBytes(),
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
            )
        } finally {
            file.delete()
        }
    }

    private fun orientationValue(degrees: Int): String = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }.toString()

    /** Degrees/minutes/seconds rational triplet, e.g. "52/1,31/1,48000/1000". */
    private fun toDms(value: Double): String {
        val magnitude = abs(value)
        val degrees = magnitude.toInt()
        val minutesFull = (magnitude - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val secondsMillis = ((minutesFull - minutes) * 60.0 * 1000.0).roundToInt()
        return "$degrees/1,$minutes/1,$secondsMillis/1000"
    }
}

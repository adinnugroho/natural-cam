package com.adin.naturalcam.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Capture file naming (SPEC 86). RAW and final share one base name via the same timestamp. */
object FileNaming {

    /**
     * `yyyyMMdd_HHMMSS_SSS` in local time (SPEC 86). The pattern is `HHmmss_SSS`
     * because in SimpleDateFormat `MM` is month and `SS` is fraction-of-second.
     * A fresh formatter per call keeps the local time zone current.
     */
    fun baseName(timestampMs: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMs))

    fun jpegName(timestampMs: Long): String = "${baseName(timestampMs)}.jpg"

    fun dngName(timestampMs: Long): String = "${baseName(timestampMs)}.dng"

    /** Name for a temp artifact in cacheDir/captures (AGENTS 51). */
    fun tempName(captureId: String, suffix: String): String = captureId + suffix
}

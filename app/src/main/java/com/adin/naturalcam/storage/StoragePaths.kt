package com.adin.naturalcam.storage

import android.content.Context
import java.io.File

/**
 * Every on-disk location and MIME type the app uses (AGENTS 50, 51). The album path
 * was written out in four files; a path change had to be made in four places.
 */
object StoragePaths {

    /** Gallery-visible album (AGENTS 50). */
    const val RELATIVE_PATH = "Pictures/NaturalCamera"

    /** MediaStore `LIKE` term for the album (the column value ends with a `/`). */
    const val RELATIVE_PATH_LIKE = "$RELATIVE_PATH%"

    const val JPEG_MIME = "image/jpeg"
    const val DNG_MIME = "image/x-adobe-dng"

    /**
     * Temporary capture artifacts (the RAW we develop from). Owned and deleted by the
     * capture path; [TempFileCleaner] reclaims whatever a kill left behind (AGENTS 51).
     */
    fun captureCacheDir(context: Context): File = File(context.cacheDir, "captures")
}

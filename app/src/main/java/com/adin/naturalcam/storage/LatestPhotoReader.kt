package com.adin.naturalcam.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.adin.naturalcam.domain.SavedPhoto

/**
 * Reads the newest app-owned photo so camera UI survives process/activity recreation.
 * Takes the resolver rather than a Context: this object is held by the ViewModel, and
 * a Context field there is a leak the linter (rightly) flags.
 */
class LatestPhotoReader(private val contentResolver: ContentResolver) {

    fun read(): SavedPhoto? = readMime(StoragePaths.JPEG_MIME) ?: readMime(StoragePaths.DNG_MIME)

    private fun readMime(mimeType: String): SavedPhoto? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val selection = "${MediaStore.Images.Media.MIME_TYPE} = ? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(mimeType, StoragePaths.RELATIVE_PATH_LIKE)
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
            "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            sort,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            return SavedPhoto(
                uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
                fileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)),
                width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
                height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
                mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)),
            )
        }
        return null
    }
}

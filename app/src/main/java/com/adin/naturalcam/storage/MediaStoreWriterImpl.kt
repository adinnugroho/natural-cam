package com.adin.naturalcam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.adin.naturalcam.domain.CaptureMetadata
import com.adin.naturalcam.domain.SavedPhoto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * MediaStore-backed saves (AGENTS 50). All user-visible output goes to
 * Pictures/NaturalCamera/ so it lands in the standard gallery.
 */
class MediaStoreWriterImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaStoreWriter {

    override suspend fun saveJpeg(
        bytes: ByteArray,
        fileName: String,
        metadata: CaptureMetadata,
        locationTagging: Boolean,
    ): SavedPhoto? = withContext(ioDispatcher) {
        var uri: Uri? = null
        try {
            uri = insert(fileName, StoragePaths.JPEG_MIME, metadata.timestampMs) ?: return@withContext null
            val exif = ExifMetadataWriter.apply(bytes, metadata, locationTagging)
            write(uri, exif.bytes)
            clearPending(uri)
            savedPhoto(uri, fileName, StoragePaths.JPEG_MIME, exif.width, exif.height)
        } catch (e: Exception) {
            uri?.let { delete(it) }
            if (e is CancellationException) throw e
            Log.w(TAG, "saveJpeg failed for $fileName", e)
            null
        }
    }

    override suspend fun saveDng(
        bytes: ByteArray,
        fileName: String,
        metadata: CaptureMetadata,
    ): SavedPhoto? = withContext(ioDispatcher) {
        // DNG is an image and gallery-visible, so it goes to MediaStore.Images with a
        // DNG mime type (not Downloads/Files). Its EXIF already comes from the RAW
        // capture; nothing is added here (AGENTS 52).
        var uri: Uri? = null
        try {
            uri = insert(fileName, StoragePaths.DNG_MIME, metadata.timestampMs) ?: return@withContext null
            write(uri, bytes)
            clearPending(uri)
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            savedPhoto(
                uri,
                fileName,
                StoragePaths.DNG_MIME,
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
            )
        } catch (e: Exception) {
            uri?.let { delete(it) }
            if (e is CancellationException) throw e
            Log.w(TAG, "saveDng failed for $fileName", e)
            null
        }
    }

    override suspend fun delete(uri: Uri) {
        withContext(ioDispatcher) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "delete failed for $uri", e) // best-effort cleanup only
            }
        }
    }

    private fun insert(fileName: String, mime: String, timestampMs: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, StoragePaths.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, timestampMs)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("openOutputStream returned null for $uri")
        stream.use { it.write(bytes) }
    }

    private fun clearPending(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Width/height already read from EXIF; no second parse and no pixel decoding on the save path. */
    private fun savedPhoto(uri: Uri, fileName: String, mime: String, width: Int, height: Int): SavedPhoto =
        SavedPhoto(uri = uri.toString(), fileName = fileName, width = width, height = height, mimeType = mime)

    private companion object {
        const val TAG = "MediaStoreWriter"
    }
}

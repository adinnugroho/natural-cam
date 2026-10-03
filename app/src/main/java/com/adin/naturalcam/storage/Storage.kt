package com.adin.naturalcam.storage

import android.net.Uri
import com.adin.naturalcam.domain.CaptureMetadata
import com.adin.naturalcam.domain.SavedPhoto

/**
 * Storage boundary. User-visible photos go to MediaStore (AGENTS 50) under
 * Pictures/NaturalCamera/. No proprietary gallery.
 */
interface MediaStoreWriter {
    /**
     * Saves final JPEG bytes with metadata applied. Returns null only when the
     * platform rejects the write; caller maps to CameraError.StorageFailed.
     */
    suspend fun saveJpeg(
        bytes: ByteArray,
        fileName: String,
        metadata: CaptureMetadata,
        locationTagging: Boolean,
    ): SavedPhoto?

    /** Saves a DNG file. fileName shares the capture's base identifier (SPEC 86). */
    suspend fun saveDng(
        bytes: ByteArray,
        fileName: String,
        metadata: CaptureMetadata,
    ): SavedPhoto?

    /** Deletes a partially written item (failure cleanup). */
    suspend fun delete(uri: Uri)
}

/** Startup/after-job cleanup of stale temp artifacts (AGENTS 51). */
interface TempFileCleaner {
    suspend fun cleanStale(maxAgeMs: Long = 24 * 60 * 60 * 1000L)
}

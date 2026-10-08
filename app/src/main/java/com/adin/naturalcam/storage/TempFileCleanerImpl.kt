package com.adin.naturalcam.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Startup/after-job cleanup of stale capture temps (AGENTS 51). Never throws. */
class TempFileCleanerImpl(private val context: Context) : TempFileCleaner {

    override suspend fun cleanStale(maxAgeMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                val dir = StoragePaths.captureCacheDir(context)
                if (!dir.isDirectory) dir.mkdirs()
                val cutoff = System.currentTimeMillis() - maxAgeMs
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) file.delete()
                }
            } catch (e: Exception) {
                // Cleanup is best-effort (AGENTS 51) but must not be invisible: a silent
                // catch here hides a filesystem that keeps growing (AGENTS 56).
                Log.w("NaturalCam", "stale capture cleanup failed", e)
            }
        }
    }
}

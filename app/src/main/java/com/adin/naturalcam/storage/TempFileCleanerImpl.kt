package com.adin.naturalcam.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Startup/after-job cleanup of stale capture temps (AGENTS 51). Never throws. */
class TempFileCleanerImpl(private val context: Context) : TempFileCleaner {

    override suspend fun cleanStale(maxAgeMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "captures")
                if (!dir.isDirectory) dir.mkdirs()
                val cutoff = System.currentTimeMillis() - maxAgeMs
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) file.delete()
                }
            } catch (e: Exception) {
                // Cleanup is best-effort only (AGENTS 51).
            }
        }
    }
}

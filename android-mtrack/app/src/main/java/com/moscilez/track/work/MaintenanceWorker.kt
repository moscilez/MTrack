package com.moscilez.track.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.moscilez.track.container
import java.io.File

/**
 * Daily local housekeeping: removes stale temporary export files and abandoned QR import
 * caches (older than 7 days). Never touches the network.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        File(applicationContext.cacheDir, "export").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        applicationContext.container.transferRepository.qrCache.purgeIfOlderThan(cutoff)
        return Result.success()
    }

    companion object {
        const val NAME = "mtrack-maintenance"
    }
}

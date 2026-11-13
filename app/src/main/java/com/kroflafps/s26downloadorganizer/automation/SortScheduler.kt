package com.kroflafps.s26downloadorganizer.automation

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SortScheduler {
    private const val PERIODIC_WORK_NAME = "s26-auto-sort-periodic"
    private const val SOON_WORK_NAME = "s26-auto-sort-soon"

    fun sync(context: Context, enabled: Boolean, hasStoragePermission: Boolean) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (enabled && hasStoragePermission) {
            val periodic = PeriodicWorkRequestBuilder<SortWorker>(15, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(SOON_WORK_NAME)
        }
    }

    fun enqueueSoon(context: Context, delaySeconds: Long = 15L) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val request = OneTimeWorkRequestBuilder<SortWorker>()
            .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SOON_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

package com.kroflafps.s26downloadorganizer.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kroflafps.s26downloadorganizer.OrganizerApplication
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.storage.StorageAccessAdapter

class SortWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OrganizerApplication ?: return Result.success()
        if (!app.preferences.isAutoSortEnabled()) return Result.success()
        if (StorageAccessAdapter().check() != StorageAccess.GRANTED) return Result.success()

        app.recovery.reconcile()
        val summary = app.autoSortEngine.runOnce(forceImmediate = false)
        if (summary.settlingCount > 0) {
            SortScheduler.enqueueSoon(applicationContext, delaySeconds = 15L)
        }
        return Result.success()
    }
}

package com.kroflafps.s26downloadorganizer

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import com.kroflafps.s26downloadorganizer.automation.AutoSortEngine
import com.kroflafps.s26downloadorganizer.automation.DownloadReadinessTracker
import com.kroflafps.s26downloadorganizer.automation.DownloadsWatcher
import com.kroflafps.s26downloadorganizer.automation.OrganizerPreferences
import com.kroflafps.s26downloadorganizer.automation.SortNotifier
import com.kroflafps.s26downloadorganizer.automation.SortScheduler
import com.kroflafps.s26downloadorganizer.classification.FileClassifier
import com.kroflafps.s26downloadorganizer.classification.androidApkDetector
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.history.JournalDatabase
import com.kroflafps.s26downloadorganizer.history.RoomCandidateStore
import com.kroflafps.s26downloadorganizer.history.RoomOperationJournal
import com.kroflafps.s26downloadorganizer.operations.OperationCoordinator
import com.kroflafps.s26downloadorganizer.operations.OperationRecovery
import com.kroflafps.s26downloadorganizer.storage.AndroidDownloadsRepository
import com.kroflafps.s26downloadorganizer.storage.AndroidOperationFiles
import com.kroflafps.s26downloadorganizer.storage.StorageAccessAdapter

class OrganizerApplication : Application() {
    private val operationMutex = kotlinx.coroutines.sync.Mutex()
    private val database by lazy { JournalDatabase.open(this) }
    val preferences by lazy { OrganizerPreferences(this) }
    val storageAccess by lazy { StorageAccessAdapter() }
    val downloadsRepository by lazy { AndroidDownloadsRepository(storageAccess) }
    val journal by lazy { RoomOperationJournal(database.journal()) }
    val candidateStore by lazy { RoomCandidateStore(database.journal()) }
    val readinessTracker by lazy { DownloadReadinessTracker(candidateStore) }
    val operationFiles by lazy { AndroidOperationFiles() }
    val coordinator by lazy { OperationCoordinator(operationFiles, journal, operationMutex = operationMutex) }
    val recovery by lazy {
        OperationRecovery(operationFiles, journal, { storageAccess.check() == StorageAccess.GRANTED }, operationMutex = operationMutex)
    }
    val notifier by lazy { SortNotifier(this) }
    val autoSortEngine by lazy {
        val apkDetector = androidApkDetector(this)
        AutoSortEngine(
            repository = downloadsRepository,
            readiness = readinessTracker,
            coordinator = coordinator,
            classifyFile = { path -> FileClassifier.classify(path, apkDetector) },
            onMovedPaths = { sourcePath, destPath ->
                MediaScannerConnection.scanFile(this, arrayOf(sourcePath, destPath), null, null)
            },
            onSummaryNotification = { summary -> notifier.notifySorted(summary) },
        )
    }
    @Suppress("DEPRECATION")
    val downloadsWatcher by lazy {
        DownloadsWatcher(
            context = this,
            downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )
    }

    override fun onCreate() {
        super.onCreate()
        syncBackgroundAutomation()
    }

    fun syncBackgroundAutomation() {
        val enabled = preferences.isAutoSortEnabled()
        val granted = storageAccess.check() == StorageAccess.GRANTED
        SortScheduler.sync(this, enabled, granted)
        if (enabled && granted) {
            downloadsWatcher.start()
        } else {
            downloadsWatcher.stop()
        }
    }
}

package com.kroflafps.s26downloadorganizer

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kroflafps.s26downloadorganizer.automation.SortScheduler
import com.kroflafps.s26downloadorganizer.classification.FileClassifier
import com.kroflafps.s26downloadorganizer.classification.androidApkDetector
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.ui.DashboardScreen
import com.kroflafps.s26downloadorganizer.ui.InventoryViewModel
import com.kroflafps.s26downloadorganizer.ui.OperationsViewModel
import com.kroflafps.s26downloadorganizer.ui.theme.S26DownloadOrganizerTheme
import java.nio.file.Paths

class MainActivity : ComponentActivity() {
    private var askedNotificationPermission = false
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Best-effort notification support; sorting still works if denied. */ }

    private val inventory: InventoryViewModel by lazy {
        val app = application as OrganizerApplication
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(InventoryViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return InventoryViewModel(app.downloadsRepository) as T
            }
        })[InventoryViewModel::class.java]
    }

    private val operations: OperationsViewModel by lazy {
        val app = application as OrganizerApplication
        val apkDetector = androidApkDetector(app)
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(OperationsViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return OperationsViewModel(
                    history = app.journal.history,
                    unresolved = app.journal.unresolvedCount,
                    coordinator = app.coordinator,
                    recovery = app.recovery,
                    target = { category -> app.operationFiles.roots.categoryDirectory(category).toString() },
                    onFilesChanged = inventory::refresh,
                    autoSortEnabled = app.preferences.autoSortEnabled,
                    onToggleAutoSort = { enabled ->
                        app.preferences.setAutoSortEnabled(enabled)
                        app.syncBackgroundAutomation()
                        if (enabled) maybeAskNotificationPermission()
                    },
                    onRunAutoSort = { app.autoSortEngine.runOnce(forceImmediate = true) },
                    classifyFile = { entry -> FileClassifier.classify(Paths.get(entry.id), apkDetector) },
                    onMovedPaths = { sourcePath, destPath ->
                        MediaScannerConnection.scanFile(app, arrayOf(sourcePath, destPath), null, null)
                    },
                ) as T
            }
        })[OperationsViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by inventory.state.collectAsStateWithLifecycle()
            val operationState by operations.state.collectAsStateWithLifecycle()
            S26DownloadOrganizerTheme {
                DashboardScreen(
                    state = state,
                    operations = operationState,
                    onToggleAutoSort = operations::setAutoSort,
                    onSortNow = operations::sortNow,
                    onReviewMove = operations::review,
                    onReviewUndo = operations::reviewUndo,
                    onRecovery = operations::recover,
                    onDismissOperation = operations::dismiss,
                    onConfirmOperation = operations::confirm,
                    onRefresh = inventory::refresh,
                    onOpenSettings = {
                        val app = application as OrganizerApplication
                        if (!app.storageAccess.openSettings(this)) inventory.settingsUnavailable()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as OrganizerApplication
        app.syncBackgroundAutomation()
        if (app.preferences.isAutoSortEnabled() && app.storageAccess.check() == StorageAccess.GRANTED) {
            maybeAskNotificationPermission()
            SortScheduler.enqueueSoon(app, delaySeconds = 2L)
        }
        inventory.onResume()
        operations.onResume()
    }

    override fun onPause() {
        inventory.onPause()
        operations.onPause()
        super.onPause()
    }

    private fun maybeAskNotificationPermission() {
        if (askedNotificationPermission || Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotificationPermission = true
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

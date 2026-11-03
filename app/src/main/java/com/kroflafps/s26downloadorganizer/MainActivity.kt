package com.kroflafps.s26downloadorganizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.kroflafps.s26downloadorganizer.storage.AndroidDownloadsRepository
import com.kroflafps.s26downloadorganizer.storage.StorageAccessAdapter
import com.kroflafps.s26downloadorganizer.ui.DashboardScreen
import com.kroflafps.s26downloadorganizer.ui.OperationsViewModel
import com.kroflafps.s26downloadorganizer.ui.InventoryViewModel
import com.kroflafps.s26downloadorganizer.ui.theme.S26DownloadOrganizerTheme

open class MainActivity : ComponentActivity() {
    private val storageAccess = StorageAccessAdapter()
    private val inventory: InventoryViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(InventoryViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return InventoryViewModel(AndroidDownloadsRepository(storageAccess)) as T
            }
        })[InventoryViewModel::class.java]
    }

    private val operations: OperationsViewModel by lazy {
        val app = application as OrganizerApplication
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(OperationsViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return OperationsViewModel(app.journal.history, app.journal.unresolvedCount,
                    app.coordinator, app.recovery, { app.operationFiles.roots.other.toString() }, inventory::refresh) as T
            }
        })[OperationsViewModel::class.java]
    }

    @androidx.compose.runtime.Composable
    protected open fun ExtraDiagnostics() { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by inventory.state.collectAsStateWithLifecycle()
            val operationState by operations.state.collectAsStateWithLifecycle()
            S26DownloadOrganizerTheme {
                DashboardScreen(
                    state = state,
                    extraPanel = { ExtraDiagnostics() },
                    operations = operationState,
                    onReviewMove = operations::review,
                    onReviewUndo = operations::reviewUndo,
                    onRecovery = operations::recover,
                    onDismissOperation = operations::dismiss,
                    onConfirmOperation = operations::confirm,
                    onRefresh = inventory::refresh,
                    onOpenSettings = {
                        if (!storageAccess.openSettings(this)) inventory.settingsUnavailable()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inventory.onResume()
        operations.onResume()
    }

    override fun onPause() {
        inventory.onPause()
        operations.onPause()
        super.onPause()
    }
}

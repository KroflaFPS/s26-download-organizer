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
import com.kroflafps.s26downloadorganizer.ui.InventoryViewModel
import com.kroflafps.s26downloadorganizer.ui.theme.S26DownloadOrganizerTheme

class MainActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by inventory.state.collectAsStateWithLifecycle()
            S26DownloadOrganizerTheme {
                DashboardScreen(
                    state = state,
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
    }

    override fun onPause() {
        inventory.onPause()
        super.onPause()
    }
}

package com.kroflafps.s26downloadorganizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kroflafps.s26downloadorganizer.ui.DashboardScreen
import com.kroflafps.s26downloadorganizer.ui.theme.S26DownloadOrganizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            S26DownloadOrganizerTheme {
                DashboardScreen()
            }
        }
    }
}

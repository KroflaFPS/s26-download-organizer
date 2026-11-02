package com.kroflafps.s26downloadorganizer.storage

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri
import com.kroflafps.s26downloadorganizer.domain.StorageAccess

class StorageAccessAdapter {
    fun check(): StorageAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return StorageAccess.UNSUPPORTED
        return try {
            if (Environment.isExternalStorageManager()) StorageAccess.GRANTED else StorageAccess.REQUIRED
        } catch (_: SecurityException) {
            StorageAccess.REQUIRED
        }
    }

    /** Invoked only by a user's tap. Opening Settings never implies permission was granted. */
    fun openSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val packageUri = "package:${activity.packageName}".toUri()
        return openStorageAccessSettings { target ->
            val intent = when (target) {
                StorageSettingsTarget.APP_ALL_FILES -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                StorageSettingsTarget.GENERAL_ALL_FILES -> Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                StorageSettingsTarget.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            }
            try {
                activity.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

/** The same ordered fallback behavior can be checked without Android Settings in JVM tests. */
internal enum class StorageSettingsTarget { APP_ALL_FILES, GENERAL_ALL_FILES, APP_DETAILS }

internal fun openStorageAccessSettings(open: (StorageSettingsTarget) -> Boolean): Boolean =
    StorageSettingsTarget.entries.any(open)

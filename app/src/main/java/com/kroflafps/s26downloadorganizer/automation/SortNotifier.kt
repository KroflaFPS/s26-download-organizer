package com.kroflafps.s26downloadorganizer.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kroflafps.s26downloadorganizer.MainActivity
import com.kroflafps.s26downloadorganizer.R

class SortNotifier(private val context: Context) {
    fun notifySorted(summary: AutoSortSummary) {
        if (summary.movedCount <= 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val breakdown = summary.movedByCategory.entries
            .joinToString(", ") { (category, count) -> "$count ${category.folderName}" }
        val title = context.resources.getQuantityString(
            R.plurals.sorted_notification_title,
            summary.movedCount,
            summary.movedCount,
        )
        val body = if (breakdown.isNotEmpty()) "$breakdown → Organized/" else "Moved to Organized/"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_folder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked concurrently; ignore cleanly.
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Download sorting",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Summary of downloads automatically organized into category folders"
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "s26_sort_results"
        const val NOTIFICATION_ID = 2601
    }
}

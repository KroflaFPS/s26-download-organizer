package com.kroflafps.s26downloadorganizer.automation

import android.content.Context
import android.os.Build
import android.os.FileObserver
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import java.io.File

/** Opportunistic in-process watcher for Downloads events; paired with WorkManager reconciliation. */
class DownloadsWatcher(
    private val context: Context,
    private val downloadsDir: File,
    private val onEvent: () -> Unit = { SortScheduler.enqueueSoon(context, delaySeconds = 15L) },
) {
    private var observer: FileObserver? = null

    @Synchronized
    fun start() {
        if (observer != null || !downloadsDir.isDirectory) return
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
        val created = if (Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(downloadsDir, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(downloadsDir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = handleEvent(path)
            }
        }
        created.startWatching()
        observer = created
    }

    @Synchronized
    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun handleEvent(path: String?) {
        if (path != null && !OperationPolicy.eligibleName(path)) return
        onEvent()
    }
}

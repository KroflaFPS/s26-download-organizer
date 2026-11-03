package com.kroflafps.s26downloadorganizer.storage

import android.os.Build
import android.os.Environment
import com.kroflafps.s26downloadorganizer.classification.*
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ClassificationPreview {
    suspend fun classify(entry: InventoryEntry): Classification
}

class AndroidClassificationPreview(private val access: StorageAccessAdapter) : ClassificationPreview {
    private val mutex = Mutex()
    @Suppress("DEPRECATION")
    override suspend fun classify(entry: InventoryEntry): Classification = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 30) return@withContext FileClassifier.unavailable(Reason.PERMISSION, Safety.UNAVAILABLE)
            val context = currentCoroutineContext()
            RootedPreviewReader(Environment.getExternalStorageDirectory().toPath(), AndroidReadHandles(), {
                access.check() == StorageAccess.GRANTED && Environment.getExternalStorageState() in
                    listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
            }, { metadata ->
                // AndroidReadHandles' stat stamp: seconds:nanoseconds|ctime. No stability/closure inference.
                val parts = metadata.stamp.substringBefore('|').split(':')
                if (parts.size != 2) null else parts[0].toLongOrNull()?.let { seconds ->
                    parts[1].toLongOrNull()?.let { nanos -> Math.addExact(Math.multiplyExact(seconds, 1000L), nanos / 1_000_000L) }
                }
            }).classify(entry) { context.ensureActive() }
        }
    }
}

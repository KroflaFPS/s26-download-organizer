package com.kroflafps.s26downloadorganizer.storage

import android.os.Environment
import com.kroflafps.s26downloadorganizer.domain.DownloadsRepository
import com.kroflafps.s26downloadorganizer.domain.InventoryError
import com.kroflafps.s26downloadorganizer.domain.InventoryResult
import com.kroflafps.s26downloadorganizer.domain.SourceExclusionPolicy
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidDownloadsRepository(
    private val storageAccess: StorageAccessAdapter,
    private val scanner: ReadOnlyInventoryScanner = ReadOnlyInventoryScanner(),
) : DownloadsRepository {
    // Cancellation cannot interrupt every platform filesystem call. A new scan waits
    // until the previous reader has actually closed, even across pause/resume.
    private val scanMutex = Mutex()

    override fun access(): StorageAccess = storageAccess.check()

    @Suppress("DEPRECATION") // Supported primary public-directory resolution; never call mkdirs().
    override suspend fun scan(): InventoryResult = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            if (access() != StorageAccess.GRANTED) {
                return@withContext InventoryResult.Failure(InventoryError.ACCESS_DENIED)
            }
            val state = Environment.getExternalStorageState()
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                return@withContext InventoryResult.Failure(InventoryError.STORAGE_UNAVAILABLE)
            }
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .toPath()
            val organized = Environment.getExternalStorageDirectory().toPath().resolve("Organized")
            val context = currentCoroutineContext()
            scanner.scan(downloads, SourceExclusionPolicy(organized)) { context.ensureActive() }
        }
    }
}

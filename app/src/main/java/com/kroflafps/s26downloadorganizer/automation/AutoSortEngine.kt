package com.kroflafps.s26downloadorganizer.automation

import com.kroflafps.s26downloadorganizer.classification.FileClassifier
import com.kroflafps.s26downloadorganizer.domain.DownloadsRepository
import com.kroflafps.s26downloadorganizer.domain.FileCategory
import com.kroflafps.s26downloadorganizer.domain.InventoryResult
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.operations.OperationCoordinator
import com.kroflafps.s26downloadorganizer.operations.OperationResult
import com.kroflafps.s26downloadorganizer.operations.Phase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

data class AutoSortSummary(
    val movedCount: Int = 0,
    val movedByCategory: Map<FileCategory, Int> = emptyMap(),
    val settlingCount: Int = 0,
    val failedCount: Int = 0,
    val skippedReason: String? = null,
    val lastFailureDetail: String? = null,
)

class AutoSortEngine(
    private val repository: DownloadsRepository,
    private val readiness: DownloadReadinessTracker,
    private val coordinator: OperationCoordinator,
    private val classifyFile: (Path) -> FileCategory = { FileClassifier.classify(it) },
    private val onMovedPaths: (sourcePath: String, destinationPath: String) -> Unit = { _, _ -> },
    private val onSummaryNotification: (AutoSortSummary) -> Unit = {},
) {
    private val runMutex = Mutex()

    suspend fun runOnce(forceImmediate: Boolean = false): AutoSortSummary = runMutex.withLock {
        if (repository.access() != StorageAccess.GRANTED) {
            return@withLock AutoSortSummary(skippedReason = "Storage permission is not granted.")
        }
        val snapshot = when (val scan = repository.scan()) {
            is InventoryResult.Success -> scan.snapshot
            is InventoryResult.Failure -> return@withLock AutoSortSummary(skippedReason = "Scan failed: ${scan.error.name}")
        }
        val evaluation = readiness.evaluate(snapshot.entries)
        val candidates = if (forceImmediate) {
            snapshot.entries.filter {
                it.type == com.kroflafps.s26downloadorganizer.domain.EntryType.FILE &&
                    it.sizeBytes != null &&
                    it.modifiedAtMillis != null &&
                    com.kroflafps.s26downloadorganizer.operations.OperationPolicy.eligibleName(it.name)
            }
        } else {
            evaluation.ready
        }

        val movedCounts = linkedMapOf<FileCategory, Int>()
        var movedCount = 0
        var failedCount = 0
        var lastDetail: String? = null

        for (entry in candidates) {
            val category = classifyFile(Paths.get(entry.id))
            val result = coordinator.move(
                id = UUID.randomUUID().toString(),
                source = entry.id,
                confirmedFinished = true,
                targetCategory = category,
            )
            when (result) {
                is OperationResult.Recorded -> {
                    readiness.clear(entry.id)
                    if (result.record.phase == Phase.COMPLETED) {
                        movedCount++
                        movedCounts[category] = (movedCounts[category] ?: 0) + 1
                        result.record.destinationPath?.let { dest ->
                            onMovedPaths(result.record.sourcePath, dest)
                        }
                    } else {
                        failedCount++
                        lastDetail = coordinator.lastFailureDetail ?: "${result.record.phase}:${result.record.error}"
                    }
                }
                is OperationResult.Blocked -> {
                    return@withLock AutoSortSummary(
                        movedCount = movedCount,
                        movedByCategory = movedCounts,
                        settlingCount = if (forceImmediate) 0 else evaluation.settlingCount,
                        failedCount = failedCount,
                        skippedReason = result.reason,
                        lastFailureDetail = lastDetail,
                    )
                }
                is OperationResult.Rejected -> {
                    readiness.clear(entry.id)
                    failedCount++
                    lastDetail = coordinator.lastFailureDetail ?: "Rejected:${result.error}"
                }
                is OperationResult.JournalUnavailable -> {
                    return@withLock AutoSortSummary(
                        movedCount = movedCount,
                        movedByCategory = movedCounts,
                        settlingCount = if (forceImmediate) 0 else evaluation.settlingCount,
                        failedCount = failedCount + 1,
                        skippedReason = "Operation journal unavailable.",
                        lastFailureDetail = coordinator.lastFailureDetail,
                    )
                }
            }
        }

        val summary = AutoSortSummary(
            movedCount = movedCount,
            movedByCategory = movedCounts,
            settlingCount = if (forceImmediate) 0 else evaluation.settlingCount,
            failedCount = failedCount,
            lastFailureDetail = lastDetail,
        )
        if (movedCount > 0) {
            onSummaryNotification(summary)
        }
        summary
    }
}

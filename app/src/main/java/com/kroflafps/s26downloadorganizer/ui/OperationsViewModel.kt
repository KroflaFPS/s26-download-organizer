package com.kroflafps.s26downloadorganizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kroflafps.s26downloadorganizer.automation.AutoSortSummary
import com.kroflafps.s26downloadorganizer.classification.FileClassifier
import com.kroflafps.s26downloadorganizer.domain.FileCategory
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.operations.*
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OperationConfirmation(
    val id: String,
    val source: String,
    val name: String,
    val target: String,
    val originalId: String? = null,
    val category: FileCategory? = null,
)

data class OperationsScreenState(
    val history: List<OperationHistoryEntry> = emptyList(),
    val unresolved: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val journalError: Boolean = false,
    val confirmation: OperationConfirmation? = null,
    val autoSortEnabled: Boolean = true,
)

class OperationsViewModel(
    history: Flow<List<OperationHistoryEntry>>,
    unresolved: Flow<Int>,
    private val coordinator: OperationCoordinator,
    private val recovery: OperationRecovery,
    private val target: (FileCategory) -> String,
    private val onFilesChanged: () -> Unit,
    autoSortEnabled: Flow<Boolean> = flowOf(true),
    private val onToggleAutoSort: (Boolean) -> Unit = {},
    private val onRunAutoSort: (suspend () -> AutoSortSummary)? = null,
    private val classifyFile: (InventoryEntry) -> FileCategory = { entry ->
        FileClassifier.classify(Paths.get(entry.id))
    },
    private val onMovedPaths: (String, String) -> Unit = { _, _ -> },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(OperationsScreenState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var checkedLaunch = false

    init {
        viewModelScope.launch {
            try {
                combine(history, unresolved, autoSortEnabled) { rows, count, autoSort ->
                    Triple(rows, count, autoSort)
                }.collect { (rows, count, autoSort) ->
                    mutable.value = mutable.value.copy(
                        history = rows,
                        unresolved = count,
                        loading = false,
                        journalError = false,
                        autoSortEnabled = autoSort,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    loading = false,
                    journalError = true,
                    message = "The operation journal could not be opened. After fixing storage availability, close and reopen the app to retry. Do not uninstall or clear app data.",
                )
            }
        }
    }

    fun onResume() {
        if (!checkedLaunch) {
            checkedLaunch = true
            recover()
        }
    }

    fun onPause() {
        job?.cancel()
        mutable.value = mutable.value.copy(confirmation = null)
    }

    fun setAutoSort(enabled: Boolean) {
        onToggleAutoSort(enabled)
        mutable.value = mutable.value.copy(
            autoSortEnabled = enabled,
            message = if (enabled) {
                "Background auto-sorting enabled."
            } else {
                "Background auto-sorting paused."
            },
        )
    }

    fun sortNow() {
        val runSort = onRunAutoSort ?: return
        if (mutable.value.busy || mutable.value.journalError) return
        runAction {
            val summary = runSort()
            onFilesChanged()
            val message = when {
                summary.skippedReason != null && summary.movedCount == 0 -> summary.skippedReason
                summary.movedCount > 0 -> {
                    val details = summary.movedByCategory.entries.joinToString(", ") { "${it.value} ${it.key.folderName}" }
                    val suffix = if (summary.failedCount > 0) " (${summary.failedCount} need review)" else ""
                    "Sorted ${summary.movedCount} file(s): $details.$suffix"
                }
                summary.settlingCount > 0 -> "No files ready yet (${summary.settlingCount} still settling after download)."
                summary.failedCount > 0 -> "${summary.failedCount} file(s) could not be moved safely. Review history."
                else -> "Downloads is already organized."
            }
            mutable.value = mutable.value.copy(message = message)
        }
    }

    fun review(entry: InventoryEntry) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            val category = withContext(ioDispatcher) { classifyFile(entry) }
            mutable.value = mutable.value.copy(
                confirmation = OperationConfirmation(
                    id = UUID.randomUUID().toString(),
                    source = entry.id,
                    name = entry.name,
                    target = target(category),
                    category = category,
                ),
            )
        }
    }

    fun reviewUndo(item: OperationHistoryEntry) {
        if (!item.canUndo || mutable.value.busy) return
        val record = item.record
        mutable.value = mutable.value.copy(
            confirmation = OperationConfirmation(
                id = UUID.randomUUID().toString(),
                source = requireNotNull(record.destinationPath),
                name = record.originalName,
                target = record.sourcePath,
                originalId = record.id,
            ),
        )
    }

    fun dismiss() {
        mutable.value = mutable.value.copy(confirmation = null)
    }

    fun confirm(finished: Boolean, uniqueName: Boolean) {
        val request = mutable.value.confirmation ?: return
        if (mutable.value.busy || mutable.value.journalError) return
        mutable.value = mutable.value.copy(confirmation = null)
        runAction {
            val result = if (request.originalId == null) {
                coordinator.move(request.id, request.source, finished, request.category ?: FileCategory.OTHER)
            } else {
                coordinator.undo(request.id, request.originalId, uniqueName)
            }
            val message = when (result) {
                is OperationResult.Blocked -> result.reason
                is OperationResult.Rejected -> "The file could not be safely validated. Nothing was removed."
                is OperationResult.JournalUnavailable -> "Journal state could not be confirmed. Review history and recovery first."
                is OperationResult.Recorded -> {
                    onFilesChanged()
                    if (result.record.phase == Phase.COMPLETED) {
                        result.record.destinationPath?.let { dest ->
                            onMovedPaths(result.record.sourcePath, dest)
                        }
                    }
                    when (result.record.phase) {
                        Phase.COMPLETED -> "Moved ${result.record.originalName} → ${result.record.destinationDirectory}."
                        Phase.COPY_ONLY -> "Copy only: the original was not removed. Review history."
                        else -> "Operation stopped. Preserve all files and review its recovery status in history."
                    }
                }
            }
            mutable.value = mutable.value.copy(message = message)
        }
    }

    fun recover() {
        if (mutable.value.busy || mutable.value.journalError) return
        runAction { mutable.value = mutable.value.copy(message = recovery.reconcile().message) }
    }

    private fun runAction(action: suspend () -> Unit) {
        if (job?.isActive == true) return
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(message = "The operation stopped. Journal records and files are retained.")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
}

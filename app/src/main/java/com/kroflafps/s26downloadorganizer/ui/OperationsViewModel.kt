package com.kroflafps.s26downloadorganizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.operations.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class OperationConfirmation(val id: String, val source: String, val name: String, val target: String, val originalId: String? = null)
data class OperationsScreenState(
    val history: List<OperationHistoryEntry> = emptyList(),
    val unresolved: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val journalError: Boolean = false,
    val confirmation: OperationConfirmation? = null,
)

class OperationsViewModel(
    history: Flow<List<OperationHistoryEntry>>,
    unresolved: Flow<Int>,
    private val coordinator: OperationCoordinator,
    private val recovery: OperationRecovery,
    private val target: () -> String,
    private val onFilesChanged: () -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(OperationsScreenState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var checkedLaunch = false

    init {
        viewModelScope.launch {
            try {
                history.combine(unresolved) { rows, count -> rows to count }.collect { (rows, count) ->
                    mutable.value = mutable.value.copy(history = rows, unresolved = count, loading = false, journalError = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loading = false, journalError = true,
                    message = "The operation journal could not be opened. After fixing storage availability, close and reopen the app to retry. Do not uninstall or clear app data. File changes remain disabled.")
            }
        }
    }

    fun onResume() {
        if (!checkedLaunch) { checkedLaunch = true; recover() }
    }

    fun onPause() {
        job?.cancel()
        mutable.value = mutable.value.copy(confirmation = null)
    }

    fun review(entry: InventoryEntry) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(confirmation = OperationConfirmation(UUID.randomUUID().toString(), entry.id, entry.name, target()))
    }

    fun reviewUndo(item: OperationHistoryEntry) {
        if (!item.canUndo || mutable.value.busy) return
        val record = item.record
        mutable.value = mutable.value.copy(confirmation = OperationConfirmation(UUID.randomUUID().toString(),
            requireNotNull(record.destinationPath), record.originalName, record.sourcePath, record.id))
    }

    fun dismiss() { mutable.value = mutable.value.copy(confirmation = null) }

    fun confirm(finished: Boolean, uniqueName: Boolean) {
        val request = mutable.value.confirmation ?: return
        if (mutable.value.busy || mutable.value.journalError) return
        mutable.value = mutable.value.copy(confirmation = null)
        runAction {
            val result = if (request.originalId == null) coordinator.move(request.id, request.source, finished)
            else coordinator.undo(request.id, request.originalId, uniqueName)
            val message = when (result) {
                is OperationResult.Blocked -> result.reason
                is OperationResult.Rejected -> "The file could not be safely validated. Nothing was intentionally removed. Check the file and history."
                is OperationResult.JournalUnavailable -> "Journal state could not be confirmed. Do not retry or uninstall. Review history and recovery first."
                is OperationResult.Recorded -> {
                    onFilesChanged()
                    when (result.record.phase) {
                        Phase.COMPLETED -> "Operation completed. Review its recorded paths in history."
                        Phase.COPY_ONLY -> "Copy only: the original was not removed. Do not repeat this operation or Undo it automatically."
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
            try { action()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(message = "The check stopped. Journal records and files are retained; review recovery before trying again.")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

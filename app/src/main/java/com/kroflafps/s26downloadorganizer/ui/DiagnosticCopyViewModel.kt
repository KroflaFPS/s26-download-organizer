package com.kroflafps.s26downloadorganizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kroflafps.s26downloadorganizer.diagnostic.*
import com.kroflafps.s26downloadorganizer.operations.FileEvidence
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DiagnosticState(
    val entries: List<FileEvidence> = emptyList(), val history: List<CopyRecord> = emptyList(),
    val selected: FileEvidence? = null, val requestId: String? = null,
    val busy: Boolean = false, val message: String? = null, val journalError: Boolean = false,
)
class DiagnosticCopyViewModel(private val coordinator: DiagnosticCopyCoordinator, history: Flow<List<CopyRecord>>) : ViewModel() {
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init {
        viewModelScope.launch {
            try { history.collect { rows -> rows.forEach { it.validate() }; mutable.value = mutable.value.copy(history = rows) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(journalError = true, message = "Diagnostic journal unavailable. Do not uninstall or clear data; close and reopen after resolving storage availability.") }
        }
    }
    fun inspect() = runAction {
        mutable.value = mutable.value.copy(entries = emptyList())
        val entries = coordinator.inspect()
        mutable.value = mutable.value.copy(entries = entries, message = if (entries.isEmpty()) "No documented fixtures found. Create only the test folders/files described in README." else "Synthetic fixture hashes verified. Review one file to copy.")
    }
    fun review(file: FileEvidence) {
        if (!mutable.value.busy && file in mutable.value.entries) mutable.value = mutable.value.copy(selected = file, requestId = UUID.randomUUID().toString())
    }
    fun dismiss() { mutable.value = mutable.value.copy(selected = null, requestId = null) }
    fun confirm(acknowledged: Boolean) {
        if (!acknowledged || mutable.value.busy) return
        val selection = mutable.value.selected ?: return
        val id = mutable.value.requestId ?: return
        dismiss()
        runAction {
            val record = coordinator.copy(id, java.nio.file.Paths.get(selection.path).fileName.toString(), acknowledged)
            mutable.value = mutable.value.copy(message = if (record.phase == CopyPhase.COPY_ONLY) DiagnosticPolicy.SUCCESS else "Test copy unresolved; preserve all outputs and review history.")
        }
    }
    fun recover() = runAction { val n = coordinator.recover(); mutable.value = mutable.value.copy(message = "Checked $n diagnostic records. Interrupted operations stay unresolved; no files were changed or removed.") }
    fun onPause() { job?.cancel(); mutable.value = mutable.value.copy(entries = emptyList(), selected = null, requestId = null) }
    private fun runAction(action: suspend () -> Unit) {
        if (job?.isActive == true || mutable.value.journalError) return
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { mutable.value = mutable.value.copy(message = failureMessage(cancelled)); throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(entries = emptyList(), message = failureMessage(error) + " Test unavailable or interrupted. Requires supported anchored I/O, All Files Access, writable storage and both exact test folders. Use only documented unchanged synthetic fixtures. Preserve partial outputs; check diagnostic recovery before another attempt.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

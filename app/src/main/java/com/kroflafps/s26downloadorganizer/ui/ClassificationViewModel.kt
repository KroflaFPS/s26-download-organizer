package com.kroflafps.s26downloadorganizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kroflafps.s26downloadorganizer.classification.*
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.storage.ClassificationPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ClassificationScreenState(val entryId: String? = null, val busy: Boolean = false, val result: Classification? = null)

/** One explicit selection, no result cache, batch traversal or operation/journal dependency. */
class ClassificationViewModel(private val preview: ClassificationPreview, private val canRead: () -> Boolean) : ViewModel() {
    private val mutableState = MutableStateFlow(ClassificationScreenState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var resumed = false

    fun onResume() { resumed = true }
    fun onPause() { resumed = false; clear() }
    fun clear() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = ClassificationScreenState()
    }
    fun inspect(entry: InventoryEntry) {
        if (!resumed) return
        clear()
        val request = generation
        mutableState.value = ClassificationScreenState(entry.id, busy = true)
        job = viewModelScope.launch {
            val result = try {
                if (!canRead()) FileClassifier.unavailable(Reason.PERMISSION, Safety.UNAVAILABLE) else preview.classify(entry)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { FileClassifier.unavailable(Reason.READ_ERROR, Safety.UNAVAILABLE) }
            if (request == generation && resumed) {
                mutableState.value = ClassificationScreenState(entry.id, result = if (canRead()) result else FileClassifier.unavailable(Reason.PERMISSION, Safety.UNAVAILABLE))
            }
        }
    }
}

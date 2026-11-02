package com.kroflafps.s26downloadorganizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kroflafps.s26downloadorganizer.domain.DownloadsRepository
import com.kroflafps.s26downloadorganizer.domain.InventoryError
import com.kroflafps.s26downloadorganizer.domain.InventoryResult
import com.kroflafps.s26downloadorganizer.domain.InventorySnapshot
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface InventoryScreenState {
    data object Checking : InventoryScreenState
    data class PermissionRequired(val settingsUnavailable: Boolean = false) : InventoryScreenState
    data object Unsupported : InventoryScreenState
    data object Loading : InventoryScreenState
    data class Loaded(val inventory: InventorySnapshot) : InventoryScreenState
    data class Error(val reason: InventoryError) : InventoryScreenState
}

class InventoryViewModel(private val repository: DownloadsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<InventoryScreenState>(InventoryScreenState.Checking)
    val state = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var generation = 0L
    private var resumed = false

    fun onResume() {
        resumed = true
        refresh()
    }

    fun onPause() {
        resumed = false
        invalidateScan()
        // Do not retain a previously authorized inventory through a Settings visit.
        mutableState.value = InventoryScreenState.Checking
    }

    fun settingsUnavailable() {
        if (mutableState.value is InventoryScreenState.PermissionRequired) {
            mutableState.value = InventoryScreenState.PermissionRequired(settingsUnavailable = true)
        }
    }

    fun refresh() {
        if (!resumed) return
        val access = repository.access()
        if (access != StorageAccess.GRANTED) {
            invalidateScan()
            mutableState.value = accessState(access)
            return
        }
        if (scanJob?.isActive == true) return
        val request = ++generation
        mutableState.value = InventoryScreenState.Loading
        scanJob = viewModelScope.launch {
            val result = try {
                repository.scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                InventoryResult.Failure(InventoryError.ACCESS_DENIED)
            } catch (_: Exception) {
                InventoryResult.Failure(InventoryError.CANNOT_READ)
            }
            if (request != generation || !resumed) return@launch
            val currentAccess = repository.access()
            mutableState.value = if (currentAccess != StorageAccess.GRANTED) {
                accessState(currentAccess)
            } else {
                when (result) {
                    is InventoryResult.Success -> InventoryScreenState.Loaded(result.snapshot)
                    is InventoryResult.Failure -> InventoryScreenState.Error(result.error)
                }
            }
        }
    }

    private fun invalidateScan() {
        generation++
        scanJob?.cancel()
        scanJob = null
    }

    private fun accessState(access: StorageAccess): InventoryScreenState = when (access) {
        StorageAccess.UNSUPPORTED -> InventoryScreenState.Unsupported
        StorageAccess.REQUIRED -> InventoryScreenState.PermissionRequired()
        StorageAccess.GRANTED -> InventoryScreenState.Checking
    }
}

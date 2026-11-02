package com.kroflafps.s26downloadorganizer.ui

import com.kroflafps.s26downloadorganizer.domain.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val snapshot = InventorySnapshot(listOf(InventoryEntry("/fixture/a", "a", EntryType.FILE, 0, 100)))
    private val success get() = InventoryResult.Success(snapshot)

    private class Repository : DownloadsRepository {
        var permission = StorageAccess.REQUIRED
        var calls = 0
        var read: suspend () -> InventoryResult = { InventoryResult.Success(InventorySnapshot(emptyList())) }
        override fun access() = permission
        override suspend fun scan(): InventoryResult { calls++; return read() }
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun deniedOrCancelledSettingsNeverStartsAScan() = runTest(dispatcher) {
        val repository = Repository()
        val model = InventoryViewModel(repository)
        model.onResume()
        assertEquals(InventoryScreenState.PermissionRequired(), model.state.value)
        model.onPause()
        model.onResume()
        model.refresh()
        runCurrent()
        assertEquals(0, repository.calls)
        assertEquals(InventoryScreenState.PermissionRequired(), model.state.value)
    }

    @Test fun unsupportedAndroidNeverScansOrRequestsLegacyPermission() = runTest(dispatcher) {
        val repository = Repository().apply { permission = StorageAccess.UNSUPPORTED }
        val model = InventoryViewModel(repository)
        model.onResume()
        model.refresh()
        runCurrent()
        assertEquals(InventoryScreenState.Unsupported, model.state.value)
        assertEquals(0, repository.calls)
    }

    @Test fun returningWithPermissionLoadsAndCanRefresh() = runTest(dispatcher) {
        val repository = Repository()
        val model = InventoryViewModel(repository)
        model.onResume()
        model.onPause()
        repository.permission = StorageAccess.GRANTED
        repository.read = { success }
        model.onResume()
        assertEquals(InventoryScreenState.Loading, model.state.value)
        runCurrent()
        assertEquals(InventoryScreenState.Loaded(snapshot), model.state.value)
        model.refresh()
        runCurrent()
        assertEquals(2, repository.calls)
        model.onPause()
    }

    @Test fun revokedPermissionClearsPreviouslyLoadedInventory() = runTest(dispatcher) {
        val repository = Repository().apply { permission = StorageAccess.GRANTED; read = { success } }
        val model = InventoryViewModel(repository)
        model.onResume()
        runCurrent()
        assertTrue(model.state.value is InventoryScreenState.Loaded)
        model.onPause()
        assertEquals(InventoryScreenState.Checking, model.state.value)
        repository.permission = StorageAccess.REQUIRED
        model.onResume()
        assertEquals(InventoryScreenState.PermissionRequired(), model.state.value)
        assertEquals(1, repository.calls)
    }

    @Test fun revokedPermissionDuringScanCannotPublishAuthorizedResults() = runTest(dispatcher) {
        val completion = CompletableDeferred<InventoryResult>()
        val repository = Repository().apply { permission = StorageAccess.GRANTED; read = { completion.await() } }
        val model = InventoryViewModel(repository)
        model.onResume()
        runCurrent()
        repository.permission = StorageAccess.REQUIRED
        completion.complete(success)
        runCurrent()
        assertEquals(InventoryScreenState.PermissionRequired(), model.state.value)
    }

    @Test fun rapidRefreshesNeverLaunchOverlappingRequests() = runTest(dispatcher) {
        val completion = CompletableDeferred<InventoryResult>()
        val repository = Repository().apply { permission = StorageAccess.GRANTED; read = { completion.await() } }
        val model = InventoryViewModel(repository)
        model.onResume()
        repeat(50) { model.refresh() }
        runCurrent()
        repeat(50) { model.refresh() }
        assertEquals(1, repository.calls)
        completion.complete(success)
        runCurrent()
        assertEquals(InventoryScreenState.Loaded(snapshot), model.state.value)
        model.onPause()
    }

    @Test fun lateUninterruptibleResultAfterPauseCannotRestoreStaleInventory() = runTest(dispatcher) {
        val completion = CompletableDeferred<InventoryResult>()
        val repository = Repository().apply {
            permission = StorageAccess.GRANTED
            read = { withContext(NonCancellable) { completion.await() } }
        }
        val model = InventoryViewModel(repository)
        model.onResume()
        runCurrent()
        model.onPause()
        repository.permission = StorageAccess.REQUIRED
        model.onResume()
        completion.complete(success)
        runCurrent()
        assertEquals(InventoryScreenState.PermissionRequired(), model.state.value)
    }

    @Test fun scanErrorsAndUnexpectedIoExceptionsCanRecoverWithRetry() = runTest(dispatcher) {
        val repository = Repository().apply { permission = StorageAccess.GRANTED }
        val model = InventoryViewModel(repository)
        for (error in InventoryError.entries) {
            repository.read = { InventoryResult.Failure(error) }
            model.onResume()
            runCurrent()
            assertEquals(InventoryScreenState.Error(error), model.state.value)
        }
        repository.read = { throw IOException("fixture failure") }
        model.refresh()
        runCurrent()
        assertEquals(InventoryScreenState.Error(InventoryError.CANNOT_READ), model.state.value)
        repository.read = { success }
        model.refresh()
        runCurrent()
        assertEquals(InventoryScreenState.Loaded(snapshot), model.state.value)
        model.onPause()
    }

    @Test fun refreshRechecksPermissionEvenWithoutLifecycleTransition() = runTest(dispatcher) {
        val repository = Repository().apply { permission = StorageAccess.GRANTED; read = { success } }
        val model = InventoryViewModel(repository)
        model.onResume()
        runCurrent()
        repository.permission = StorageAccess.REQUIRED
        model.refresh()
        assertEquals(InventoryScreenState.PermissionRequired(), model.state.value)
        assertEquals(1, repository.calls)
    }

    @Test fun emptyInventoryIsRealLoadedDataAndSettingsFailureIsVisible() = runTest(dispatcher) {
        val repository = Repository()
        val model = InventoryViewModel(repository)
        model.onResume()
        model.settingsUnavailable()
        assertEquals(InventoryScreenState.PermissionRequired(true), model.state.value)
        model.onPause()
        repository.permission = StorageAccess.GRANTED
        model.onResume()
        runCurrent()
        assertEquals(InventoryScreenState.Loaded(InventorySnapshot(emptyList())), model.state.value)
        model.onPause()
    }
}

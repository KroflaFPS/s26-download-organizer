package com.kroflafps.s26downloadorganizer.ui

import com.kroflafps.s26downloadorganizer.classification.*
import com.kroflafps.s26downloadorganizer.domain.*
import com.kroflafps.s26downloadorganizer.storage.ClassificationPreview
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClassificationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val entry = InventoryEntry("/fixture/a", "a", EntryType.FILE, 1, 100)
    private val suggestion = Classification(format = "Fixture", decision = Decision.CANDIDATE, destination = Destination.OTHER, reason = Reason.NO_SIGNATURE)
    private class Preview : ClassificationPreview {
        var calls = 0
        var read: suspend () -> Classification = { Classification(reason = Reason.EMPTY) }
        override suspend fun classify(entry: InventoryEntry): Classification { calls++; return read() }
    }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun enteringOrResumingDoesNoClassificationAndDeniedTapDoesNoIo() = runTest(dispatcher) {
        val preview = Preview()
        val model = ClassificationViewModel(preview) { false }
        model.onResume(); runCurrent()
        assertEquals(0, preview.calls)
        model.inspect(entry); runCurrent()
        assertEquals(0, preview.calls)
        assertEquals(Reason.PERMISSION, model.state.value.result?.reason)
        model.onPause()
        assertNull(model.state.value.result)
    }

    @Test fun explicitSelectionIsSingleItemAndRefreshClearsItsResult() = runTest(dispatcher) {
        val preview = Preview().apply { read = { suggestion } }
        val model = ClassificationViewModel(preview) { true }
        model.onResume(); model.inspect(entry); runCurrent()
        assertEquals(1, preview.calls)
        assertEquals(suggestion, model.state.value.result)
        model.clear()
        assertEquals(ClassificationScreenState(), model.state.value)
        model.onPause()
    }

    @Test fun permissionRevokedDuringReadCannotPublishCandidate() = runTest(dispatcher) {
        val completion = CompletableDeferred<Classification>()
        val preview = Preview().apply { read = { completion.await() } }
        var permission = true
        val model = ClassificationViewModel(preview) { permission }
        model.onResume(); model.inspect(entry); runCurrent()
        permission = false; completion.complete(suggestion); runCurrent()
        assertEquals(Safety.UNAVAILABLE, model.state.value.result?.safety)
        assertNull(model.state.value.result?.destination)
        model.onPause()
    }

    @Test fun pauseOrNewSelectionSuppressesLateUninterruptibleCompletion() = runTest(dispatcher) {
        val completion = CompletableDeferred<Classification>()
        val preview = Preview().apply { read = { withContext(NonCancellable) { completion.await() } } }
        val model = ClassificationViewModel(preview) { true }
        model.onResume(); model.inspect(entry); runCurrent()
        model.onPause(); model.onResume()
        completion.complete(suggestion); runCurrent()
        assertEquals(ClassificationScreenState(), model.state.value)
        model.onPause()
    }

    @Test fun errorsNeverExposePrivateMessagesAndCanRetry() = runTest(dispatcher) {
        val preview = Preview().apply { read = { throw java.io.IOException("/private/real-filename") } }
        val model = ClassificationViewModel(preview) { true }
        model.onResume(); model.inspect(entry); runCurrent()
        assertFalse(model.state.value.toString().contains("real-filename"))
        assertEquals(Reason.READ_ERROR, model.state.value.result?.reason)
        preview.read = { suggestion }; model.inspect(entry); runCurrent()
        assertEquals(suggestion, model.state.value.result)
        model.onPause()
    }
}

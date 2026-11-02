package com.kroflafps.s26downloadorganizer.ui

import com.kroflafps.s26downloadorganizer.domain.*
import com.kroflafps.s26downloadorganizer.operations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun reviewAndConfirmationCannotBypassTheMutationGateAndPauseClearsSelection() = runTest(dispatcher) {
        val files = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        val model = OperationsViewModel(MutableStateFlow(emptyList()), MutableStateFlow(0), OperationCoordinator(files, journal),
            OperationRecovery(files, journal, { false }), { files.roots.other.toString() }, { fail("Files cannot change") })
        runCurrent()
        assertFalse(model.state.value.loading)
        val entry = InventoryEntry(files.roots.downloads.resolve("data").toString(), "data", EntryType.FILE, 4, 1)
        model.review(entry)
        assertNotNull(model.state.value.confirmation)
        model.onPause()
        assertNull(model.state.value.confirmation)
        model.review(entry)
        model.confirm(true, true)
        runCurrent()
        assertEquals(OperationPolicy.GATE_REASON, model.state.value.message)
        assertTrue(journal.records.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test fun journalOpenErrorsAreVisibleAndDoNotBecomeAnEmptySuccess() = runTest(dispatcher) {
        val files = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        val model = OperationsViewModel(flow { throw java.io.IOException("fixture journal error") }, MutableStateFlow(0),
            OperationCoordinator(files, journal), OperationRecovery(files, journal, { false }), { "unused" }, {})
        runCurrent()
        assertTrue(model.state.value.journalError)
        assertFalse(model.state.value.loading)
        assertTrue(requireNotNull(model.state.value.message).contains("Do not uninstall"))
        model.recover()
        assertFalse(model.state.value.busy)
    }
}

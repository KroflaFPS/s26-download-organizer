package com.kroflafps.s26downloadorganizer.ui

import com.kroflafps.s26downloadorganizer.automation.AutoSortSummary
import com.kroflafps.s26downloadorganizer.domain.*
import com.kroflafps.s26downloadorganizer.operations.*
import java.nio.file.Files
import java.nio.file.Paths
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

    @Test fun reviewAndConfirmationMoveToDetectedCategoryAndPauseClearsSelection() = runTest(dispatcher) {
        val files = FixtureFiles(temporary.newFolder().toPath())
        val source = Files.write(files.roots.downloads.resolve("photo.png"), byteArrayOf(1, 2, 3, 4))
        val journal = MemoryJournal()
        val autoSortFlow = MutableStateFlow(true)
        var refreshCalls = 0
        val model = OperationsViewModel(
            history = MutableStateFlow(emptyList()),
            unresolved = MutableStateFlow(0),
            coordinator = OperationCoordinator(files, journal, ioDispatcher = dispatcher),
            recovery = OperationRecovery(files, journal, { false }),
            target = { category -> files.roots.categoryDirectory(category).toString() },
            onFilesChanged = { refreshCalls++ },
            autoSortEnabled = autoSortFlow,
            onToggleAutoSort = { autoSortFlow.value = it },
            onRunAutoSort = { AutoSortSummary(movedCount = 1, movedByCategory = mapOf(FileCategory.IMAGES to 1)) },
            ioDispatcher = dispatcher,
        )
        runCurrent()
        assertFalse(model.state.value.loading)
        val entry = InventoryEntry(source.toString(), "photo.png", EntryType.FILE, 4, 1)
        model.review(entry)
        runCurrent()
        assertNotNull(model.state.value.confirmation)
        assertEquals(FileCategory.IMAGES, model.state.value.confirmation?.category)
        model.onPause()
        assertNull(model.state.value.confirmation)

        model.review(entry)
        runCurrent()
        model.confirm(finished = true, uniqueName = true)
        runCurrent()
        assertEquals(1, refreshCalls)
        assertFalse(Files.exists(source))
        assertTrue(Files.exists(files.roots.categoryDirectory(FileCategory.IMAGES).resolve("photo.png")))
        assertEquals(1, journal.records.size)
        assertFalse(model.state.value.busy)

        model.setAutoSort(false)
        assertFalse(autoSortFlow.value)
        model.sortNow()
        runCurrent()
        assertTrue(model.state.value.message.orEmpty().contains("Sorted 1 file(s)"))
    }

    @Test fun journalOpenErrorsAreVisibleAndDoNotBecomeAnEmptySuccess() = runTest(dispatcher) {
        val files = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        val model = OperationsViewModel(
            history = flow { throw java.io.IOException("fixture journal error") },
            unresolved = MutableStateFlow(0),
            coordinator = OperationCoordinator(files, journal),
            recovery = OperationRecovery(files, journal, { false }),
            target = { "unused" },
            onFilesChanged = {},
        )
        runCurrent()
        assertTrue(model.state.value.journalError)
        assertFalse(model.state.value.loading)
        assertTrue(requireNotNull(model.state.value.message).contains("Do not uninstall"))
        model.recover()
        assertFalse(model.state.value.busy)
    }
}

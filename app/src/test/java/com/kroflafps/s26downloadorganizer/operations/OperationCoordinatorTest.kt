package com.kroflafps.s26downloadorganizer.operations

import java.io.IOException
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun files(removal: Boolean = true) = FixtureFiles(temporary.newFolder().toPath(), removal)
    private fun id() = UUID.randomUUID().toString()
    private fun source(files: FixtureFiles, name: String = "binary.bin", bytes: ByteArray = byteArrayOf(0, 1, -1, 2)) = Files.write(files.roots.downloads.resolve(name), bytes)
    private fun engine(files: FixtureFiles, journal: OperationJournal, hook: (Checkpoint) -> Unit = {}) = OperationCoordinator(files, journal, { true }, checkpoint = hook)
    private fun record(result: OperationResult) = (result as OperationResult.Recorded).record
    private suspend fun recovery(files: FixtureFiles, journal: OperationJournal) = OperationRecovery(files, journal, { files.access }).reconcile()
    private class Terminated : Error()

    @Test fun singleFileMoveAndUndoUseActualVerifiedBytesAndRetainHistory() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val move = record(engine(files, journal).move(id(), original.toString(), true))
        assertEquals(Phase.COMPLETED, move.phase)
        assertFalse(Files.exists(original))
        assertEquals(files.roots.other.resolve("binary.bin").toString(), move.destinationPath)
        assertTrue(move.sourceRemoved && move.destinationPublished && move.destinationVerified && move.destinationSynced)
        assertEquals(move.contentHash, FixtureFiles.hash(Paths.get(requireNotNull(move.destinationPath))))
        val undo = record(engine(files, journal).undo(id(), move.id, true))
        assertEquals(Phase.COMPLETED, undo.phase)
        assertEquals(OperationType.UNDO, undo.type)
        assertEquals(move.id, undo.relatedId)
        assertArrayEquals(byteArrayOf(0, 1, -1, 2), Files.readAllBytes(original))
        assertFalse(Files.exists(Paths.get(requireNotNull(move.destinationPath))))
        assertEquals(2, journal.records.size)
        assertEquals(move, journal.get(move.id))
    }

    @Test fun unsupportedConditionalRemovalReturnsCopyOnlyAndNeverOffersUndo() = runBlocking {
        val files = files(false); val journal = MemoryJournal(); val original = source(files)
        val moved = record(engine(files, journal).move(id(), original.toString(), true))
        assertEquals(Phase.COPY_ONLY, moved.phase)
        assertTrue(Files.exists(original))
        assertFalse(moved.sourceRemoved || moved.undoEligible || moved.removalAuthorized)
        assertEquals(OperationError.NO_SAFE_REMOVAL, moved.error)
        assertTrue(engine(files, journal).undo(id(), moved.id, true) is OperationResult.Rejected)
        val count = journal.records.size
        recovery(files, journal); recovery(files, journal)
        assertEquals(count, journal.records.size)
        assertTrue(Files.exists(original))
    }

    @Test fun shippingSafetyGateStopsBeforeAnyFileOrJournalMutation() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        assertTrue(OperationCoordinator(files, journal, authorized = { false }).move(id(), original.toString(), true) is OperationResult.Blocked)
        assertTrue(journal.records.isEmpty())
        assertFalse(Files.exists(files.roots.organized))
        assertTrue(Files.exists(original))
    }

    @Test fun confirmationAndInvalidTypesPathsNamesAreRejectedBeforeIntent() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val directory = Files.createDirectory(files.roots.downloads.resolve("directory"))
        val link = Files.createSymbolicLink(files.roots.downloads.resolve("link"), original)
        val temp = source(files, "unfinished.CRDownload")
        for (path in listOf("", "relative.bin", original.parent.resolve("../outside").toString(), directory.toString(), link.toString(), temp.toString(), original.resolveSibling("missing").toString())) {
            assertTrue(path, engine(files, journal).move(id(), path, true) is OperationResult.Rejected)
        }
        assertTrue(engine(files, journal).move(id(), original.toString(), false) is OperationResult.Rejected)
        assertTrue(journal.records.isEmpty())
    }

    @Test fun destinationHierarchySymlinksFailClosedWithoutTouchingTarget() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val outside = temporary.newFolder().toPath()
        Files.createSymbolicLink(files.roots.other.parent, outside)
        val result = record(engine(files, journal).move(id(), original.toString(), true))
        assertEquals(Phase.RECOVERY_REQUIRED, result.phase)
        assertTrue(Files.exists(original))
        Files.newDirectoryStream(outside).use { assertFalse(it.iterator().hasNext()) }
    }

    @Test fun repeatedCollisionsNeverChangeExistingBytes() = runBlocking {
        val files = files(); val journal = MemoryJournal(); files.ensureDestination(files.roots.other)
        for (name in listOf("file.txt", "file (1).txt")) Files.write(files.roots.other.resolve(name), byteArrayOf(77))
        val original = source(files, "file.txt")
        val result = record(engine(files, journal).move(id(), original.toString(), true))
        assertEquals("file (2).txt", Paths.get(requireNotNull(result.destinationPath)).fileName.toString())
        for (name in listOf("file.txt", "file (1).txt")) assertArrayEquals(byteArrayOf(77), Files.readAllBytes(files.roots.other.resolve(name)))
    }

    @Test fun publicationRaceWithAnotherCreatorAllocatesANewNameWithoutReplacement() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        var once = false
        files.publicationHook = { if (!once) { once = true; Files.write(Paths.get(it), byteArrayOf(99), StandardOpenOption.CREATE_NEW) } }
        val result = record(engine(files, journal).move(id(), original.toString(), true))
        assertEquals("binary (1).bin", Paths.get(requireNotNull(result.destinationPath)).fileName.toString())
        assertArrayEquals(byteArrayOf(99), Files.readAllBytes(files.roots.other.resolve("binary.bin")))
    }

    @Test fun concurrentCoordinatorsAllocateTheSameNameWithoutCorruption() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val first = async(Dispatchers.Default) { engine(files, journal).move(id(), original.toString(), true) }
        val second = async(Dispatchers.Default) { engine(files, journal).move(id(), original.toString(), true) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, journal.records.size)
        assertEquals(1, results.count { it is OperationResult.Recorded && it.record.phase == Phase.COMPLETED })
    }

    @Test fun exclusivePublicationPrimitiveIsSafeUnderConcurrentSameNameAttempts() = runBlocking {
        val files = files(); files.ensureDestination(files.roots.other)
        val stage1 = files.roots.other.resolve(".s26-a.part").toString()
        val stage2 = files.roots.other.resolve(".s26-b.part").toString()
        val key1 = files.reserveStage(stage1); val key2 = files.reserveStage(stage2)
        val target = files.roots.other.resolve("same").toString()
        val results = listOf(stage1 to key1, stage2 to key2).map { (path, key) -> async(Dispatchers.Default) {
            try { files.publishNoClobber(path, key, target); true } catch (_: FileAlreadyExistsException) { false }
        } }.awaitAll()
        assertEquals(1, results.count { it })
        assertTrue(Files.exists(Paths.get(stage1)) && Files.exists(Paths.get(stage2)))
    }

    @Test fun duplicateRequestIdReturnsOriginalRecordWithoutRepeatingWork() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files); val request = id()
        val first = record(engine(files, journal).move(request, original.toString(), true))
        val second = record(engine(files, journal).move(request, original.toString(), true))
        assertEquals(first, second)
        assertEquals(1, journal.records.size)
    }

    @Test fun undoCollisionRequiresUniqueConsentAndPreservesBothFiles() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files, "report.pdf")
        val move = record(engine(files, journal).move(id(), original.toString(), true))
        Files.write(original, byteArrayOf(42), StandardOpenOption.CREATE_NEW)
        assertTrue(engine(files, journal).undo(id(), move.id, false) is OperationResult.Blocked)
        val undo = record(engine(files, journal).undo(id(), move.id, true))
        assertEquals("report (1).pdf", Paths.get(requireNotNull(undo.destinationPath)).fileName.toString())
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(original))
        assertArrayEquals(byteArrayOf(0, 1, -1, 2), Files.readAllBytes(Paths.get(requireNotNull(undo.destinationPath))))
        assertTrue(engine(files, journal).undo(id(), move.id, true) is OperationResult.Rejected)
    }

    @Test fun modifiedOrganizedFileCannotBeUndone() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val move = record(engine(files, journal).move(id(), original.toString(), true))
        val destination = Paths.get(requireNotNull(move.destinationPath))
        Files.write(destination, byteArrayOf(4, 3, 2, 1))
        assertEquals(OperationResult.Rejected(OperationError.SOURCE_CHANGED), engine(files, journal).undo(id(), move.id, true))
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), Files.readAllBytes(destination))
        assertFalse(Files.exists(original))
    }

    @Test fun sourceChangesDuringCopyAreDetectedIncludingSameSizeAndRestoredMtime() = runBlocking {
        for (sameSize in listOf(false, true)) {
            val files = files(); val journal = MemoryJournal(); val original = source(files)
            val time = Files.getLastModifiedTime(original)
            var once = false
            files.copyHook = { if (!once) { once = true; Files.write(original, if (sameSize) byteArrayOf(9, 9, 9, 9) else byteArrayOf(9)); Files.setLastModifiedTime(original, time) } }
            val result = record(engine(files, journal).move(id(), original.toString(), true))
            assertEquals(Phase.RECOVERY_REQUIRED, result.phase)
            assertEquals(OperationError.SOURCE_CHANGED, result.error)
            assertTrue(Files.exists(original))
            assertFalse(result.sourceRemoved)
        }
    }

    @Test fun externalRenameOrReplacementBeforeRemovalNeverDeletesTheNewOccupant() = runBlocking {
        for (rename in listOf(false, true)) {
            val files = files(); val journal = MemoryJournal(); val original = source(files)
            val renamed = original.resolveSibling("renamed-by-another-app")
            val result = record(engine(files, journal) { point ->
                if (point == Checkpoint.BEFORE_REMOVAL) {
                    Files.move(original, renamed) // Fixture simulates another app, not the engine.
                    if (!rename) Files.write(original, byteArrayOf(55), StandardOpenOption.CREATE_NEW)
                }
            }.move(id(), original.toString(), true))
            assertEquals(Phase.RECOVERY_REQUIRED, result.phase)
            assertArrayEquals(byteArrayOf(0, 1, -1, 2), Files.readAllBytes(renamed))
            if (!rename) assertArrayEquals(byteArrayOf(55), Files.readAllBytes(original))
        }
    }

    @Test fun destinationModificationBeforeRemovalPreservesSourceAndModifiedDestination() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val result = record(engine(files, journal) {
            if (it == Checkpoint.BEFORE_REMOVAL) Files.write(files.roots.other.resolve("binary.bin"), byteArrayOf(66))
        }.move(id(), original.toString(), true))
        assertEquals(Phase.RECOVERY_REQUIRED, result.phase)
        assertTrue(Files.exists(original))
        assertArrayEquals(byteArrayOf(66), Files.readAllBytes(files.roots.other.resolve("binary.bin")))
    }

    @Test fun zeroByteAndLargeBinaryCopiesAreVerifiedWithBoundedBuffers() = runBlocking {
        for (size in listOf(0, 8 * 1024 * 1024)) {
            val files = files(); val journal = MemoryJournal(); val original = source(files, bytes = byteArrayOf())
            Files.newOutputStream(original).use { output -> repeat(size / 8192) { output.write(ByteArray(8192) { (it % 251).toByte() }) } }
            var chunks = 0; files.copyHook = { chunks++ }
            val hash = FixtureFiles.hash(original)
            val result = record(engine(files, journal).move(id(), original.toString(), true))
            assertEquals(Phase.COMPLETED, result.phase)
            assertEquals(size.toLong(), result.bytesCopied)
            assertEquals(hash, FixtureFiles.hash(Paths.get(requireNotNull(result.destinationPath))))
            if (size > 0) assertTrue(chunks >= size / (64 * 1024))
        }
    }

    @Test fun storageFullInterruptedIoReadOnlyAndPermissionRevocationPreserveOriginal() = runBlocking {
        for (mode in listOf("full", "revoke", "readonly", "source-inaccessible", "destination-inaccessible")) {
            val files = files(); val journal = MemoryJournal(); val original = source(files)
            if (mode == "full") files.bytesLimit = 0
            val result = engine(files, journal) { point ->
                if (point == Checkpoint.COPYING_SAVED) {
                    when (mode) {
                        "revoke" -> files.access = false
                        "readonly" -> files.writable = false
                        "source-inaccessible", "destination-inaccessible" -> throw AccessDeniedException(mode)
                    }
                }
            }.move(id(), original.toString(), true)
            assertTrue(result is OperationResult.Recorded)
            assertTrue(Files.exists(original))
            assertFalse(record(result).sourceRemoved)
        }
    }

    @Test fun syncFailureIsCopyOnlyNeverMoveCompletion() = runBlocking {
        val files = files(); files.directorySync = false
        val journal = MemoryJournal(); val original = source(files)
        val result = record(engine(files, journal).move(id(), original.toString(), true))
        assertEquals(Phase.COPY_ONLY, result.phase)
        assertEquals(OperationError.DURABILITY_UNCONFIRMED, result.error)
        assertTrue(Files.exists(original))
    }

    @Test fun everyImportantTerminationBoundaryRecoversIdempotentlyWithoutDuplicateCreation() = runBlocking {
        for (stop in Checkpoint.entries) {
            val files = files(); val journal = MemoryJournal(); val original = source(files)
            try { engine(files, journal) { if (it == stop) throw Terminated() }.move(id(), original.toString(), true); fail(stop.name) }
            catch (_: Terminated) { }
            // A new coordinator/reconciler models a new process; the durable-store behavior is tested separately with Room.
            val namesBefore = Files.walk(files.roots.downloads.parent).use { it.map(Path::toString).toList().sorted() }
            recovery(files, journal)
            val once = journal.records.values.toList()
            recovery(files, journal)
            assertEquals(stop.name, once, journal.records.values.toList())
            assertEquals(stop.name, namesBefore, Files.walk(files.roots.downloads.parent).use { it.map(Path::toString).toList().sorted() })
            val record = journal.records.values.single()
            if (record.phase == Phase.COMPLETED) {
                assertFalse(Files.exists(original))
                assertEquals(record.contentHash, FixtureFiles.hash(Paths.get(requireNotNull(record.destinationPath))))
            } else if (!Files.exists(original)) {
                assertNotNull("A verified output must survive $stop", record.destinationPath)
                assertEquals(record.contentHash, FixtureFiles.hash(Paths.get(requireNotNull(record.destinationPath))))
                assertTrue(record.recoveryNeeded)
            }
        }
    }

    @Test fun interruptedUndoUsesTheSameRecoveryRules() = runBlocking {
        for (stop in Checkpoint.entries) {
            val files = files(); val journal = MemoryJournal(); val original = source(files)
            val moved = record(engine(files, journal).move(id(), original.toString(), true))
            try { engine(files, journal) { if (it == stop) throw Terminated() }.undo(id(), moved.id, true); fail(stop.name) }
            catch (_: Terminated) { }
            recovery(files, journal); val once = journal.records.values.toList(); recovery(files, journal)
            assertEquals(stop.name, once, journal.records.values.toList())
            val undo = journal.records.values.last()
            if (undo.phase == Phase.COMPLETED) assertTrue(Files.exists(Paths.get(requireNotNull(undo.destinationPath))))
            else if (!Files.exists(Paths.get(requireNotNull(moved.destinationPath)))) {
                assertEquals(undo.contentHash, FixtureFiles.hash(Paths.get(requireNotNull(undo.destinationPath))))
                assertTrue(undo.recoveryNeeded)
            }
            assertEquals(2, journal.records.size)
        }
    }

    @Test fun journalFailuresBeforeAndAfterImportantCommitsNeverCauseUntrackedDeletion() = runBlocking {
        for (phase in Phase.entries.filter { it !in setOf(Phase.COPY_ONLY, Phase.FAILED_SAFE, Phase.RECOVERY_REQUIRED) }) {
            for (afterCommit in listOf(false, true)) {
                val files = files(); val journal = MemoryJournal(); val original = source(files)
                val fail: (OperationRecord) -> Boolean = { it.phase == phase }
                if (afterCommit) journal.failAfter = fail else journal.failBefore = fail
                engine(files, journal).move(id(), original.toString(), true)
                journal.failBefore = { false }; journal.failAfter = { false }
                recovery(files, journal)
                val operation = journal.records.values.firstOrNull()
                if (!Files.exists(original)) {
                    assertNotNull(phase.name, operation)
                    assertNotNull(operation?.destinationPath)
                    assertEquals(operation?.contentHash, FixtureFiles.hash(Paths.get(requireNotNull(operation?.destinationPath))))
                }
                assertTrue(phase.name, Files.exists(original) || operation?.destinationPublished == true)
            }
        }
    }

    @Test fun recoveryDefersWithoutPermissionAndPreservesOrphanAndModifiedFiles() = runBlocking {
        val files = files(false); val journal = MemoryJournal(); val original = source(files)
        val moved = record(engine(files, journal).move(id(), original.toString(), true))
        val orphan = Files.write(files.roots.other.resolve(".s26-orphan.part"), byteArrayOf(78))
        files.access = false
        assertTrue(recovery(files, journal).needsAttention)
        assertEquals(moved, journal.get(moved.id))
        files.access = true
        Files.write(Paths.get(requireNotNull(moved.destinationPath)), byteArrayOf(79))
        recovery(files, journal)
        assertEquals(Phase.RECOVERY_REQUIRED, journal.get(moved.id)?.phase)
        assertTrue(Files.exists(original))
        assertArrayEquals(byteArrayOf(78), Files.readAllBytes(orphan))
    }

    @Test fun cancellationDuringCopyStopsAndTracksPartialOutput() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        try { engine(files, journal) { if (it == Checkpoint.COPY_CHUNK) throw CancellationException("fixture cancellation") }.move(id(), original.toString(), true); fail() }
        catch (_: CancellationException) { }
        assertTrue(Files.exists(original))
        assertEquals(Phase.RECOVERY_REQUIRED, journal.records.values.single().phase)
    }

    @Test fun repeatedTapsAndRecoveryCannotOverlapAnActiveCoordinator() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val mutex = kotlinx.coroutines.sync.Mutex()
        val coordinator = OperationCoordinator(files, journal, { true }, checkpoint = {
            if (it == Checkpoint.COPYING_SAVED) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }, operationMutex = mutex)
        val first = async(Dispatchers.Default) { coordinator.move(id(), original.toString(), true) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            repeat(10) { assertTrue(coordinator.move(id(), original.toString(), true) is OperationResult.Blocked) }
            val before = journal.records.values.toList()
            val recovery = OperationRecovery(files, journal, { true }, operationMutex = mutex).reconcile()
            assertEquals(0, recovery.examined)
            assertEquals(before, journal.records.values.toList())
        } finally { release.countDown() }
        assertEquals(Phase.COMPLETED, record(first.await()).phase)
        assertEquals(1, journal.records.size)
    }


    @Test fun mismatchedAdapterEvidenceIsRejectedBeforeIntent() = runBlocking {
        val fixture = files(); val journal = MemoryJournal(); val original = source(fixture)
        val invalid = object : OperationFiles by fixture {
            override fun prepare(path: String, type: OperationType) = fixture.prepare(path, type).copy(path = "/outside-the-request/file")
        }
        assertEquals(OperationResult.Rejected(OperationError.UNTRUSTED_EVIDENCE),
            OperationCoordinator(invalid, journal, { true }).move(id(), original.toString(), true))
        assertTrue(journal.records.isEmpty())
        assertTrue(Files.exists(original))
    }


    @Test fun duplicateUndoReturnsExistingResultAndFailedUndoPreservesNewDownloadsOccupant() = runBlocking {
        val files = files(); val journal = MemoryJournal(); val original = source(files, "name.bin")
        val move = record(engine(files, journal).move(id(), original.toString(), true))
        Files.write(original, byteArrayOf(99), StandardOpenOption.CREATE_NEW)
        files.bytesLimit = 0
        val undoId = id()
        val failed = record(engine(files, journal).undo(undoId, move.id, true))
        assertEquals(Phase.RECOVERY_REQUIRED, failed.phase)
        assertArrayEquals(byteArrayOf(99), Files.readAllBytes(original))
        assertArrayEquals(byteArrayOf(0, 1, -1, 2), Files.readAllBytes(Paths.get(requireNotNull(move.destinationPath))))
        assertEquals(failed, record(engine(files, journal).undo(undoId, move.id, true)))
        assertEquals(2, journal.records.size)
        assertFalse(Files.exists(original.resolveSibling("name (1).bin")))
    }

}

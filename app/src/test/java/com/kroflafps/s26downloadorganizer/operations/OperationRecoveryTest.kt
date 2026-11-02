package com.kroflafps.s26downloadorganizer.operations

import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun id() = UUID.randomUUID().toString()

    @Test fun bothMissingAndReplacedTrackedStagingPathsRemainUnresolved() = runBlocking {
        for (scenario in listOf("both-missing", "replaced-stage", "source-missing")) {
            val files = FixtureFiles(temporary.newFolder().toPath(), false)
            val journal = MemoryJournal()
            val source = Files.write(files.roots.downloads.resolve("data"), byteArrayOf(1, 2))
            val operation = (OperationCoordinator(files, journal, { true }).move(id(), source.toString(), true) as OperationResult.Recorded).record
            when (scenario) {
                "both-missing" -> { Files.delete(source); Files.delete(Paths.get(requireNotNull(operation.destinationPath))); Files.delete(Paths.get(operation.stagingPath)) }
                "replaced-stage" -> { Files.delete(Paths.get(operation.stagingPath)); Files.write(Paths.get(operation.stagingPath), byteArrayOf(9), java.nio.file.StandardOpenOption.CREATE_NEW) }
                "source-missing" -> Files.delete(source)
            }
            val recovery = OperationRecovery(files, journal, { true })
            recovery.reconcile()
            val once = journal.get(operation.id)
            assertEquals(Phase.RECOVERY_REQUIRED, once?.phase)
            assertFalse(once?.undoEligible ?: true)
            if (scenario == "both-missing") assertEquals(OperationError.MISSING_BOTH, once?.error)
            recovery.reconcile()
            assertEquals(once, journal.get(operation.id))
        }
    }

    @Test fun untrustedObservationsCanNeverCertifyCompletion() = runBlocking {
        val fixture = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        val source = Files.write(fixture.roots.downloads.resolve("data"), byteArrayOf(1))
        class Stop : Error()
        try { OperationCoordinator(fixture, journal, { true }, checkpoint = { if (it == Checkpoint.SOURCE_REMOVED_SAVED) throw Stop() }).move(id(), source.toString(), true) }
        catch (_: Stop) { }
        val untrusted = object : OperationFiles by fixture {
            override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation = when (val actual = fixture.observe(path, maximumBytes, checkpoint)) {
                is Observation.Present -> actual.copy(trusted = false)
                else -> actual
            }
        }
        OperationRecovery(untrusted, journal, { true }).reconcile()
        assertEquals(Phase.RECOVERY_REQUIRED, journal.records.values.single().phase)
        assertEquals(OperationError.UNTRUSTED_EVIDENCE, journal.records.values.single().error)
    }

    @Test fun launchRecoveryIsBoundedAndNeverScansUnjournaledFiles() = runBlocking {
        val fixture = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        var observations = 0
        val observing = object : OperationFiles by fixture {
            override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation { observations++; return Observation.Unverifiable }
        }
        for (number in 0..24) {
            val source = Files.write(fixture.roots.downloads.resolve("data-$number"), byteArrayOf(1))
            val evidence = fixture.prepare(source.toString(), OperationType.MOVE)
            journal.create(OperationRecord(UUID(0L, number.toLong()).toString(), OperationType.MOVE, null, source.toString(), fixture.roots.other.toString(), "data-$number",
                evidence.identity, if (number == 0) 100L * 1024 * 1024 else evidence.size, evidence.stamp, evidence.hash,
                "claim-$number", fixture.roots.other.resolve(".s26-$number.part").toString(), startedAt = number.toLong(), updatedAt = number.toLong()))
        }
        val report = OperationRecovery(observing, journal, { true }).reconcile()
        assertEquals(19, report.examined) // 20-record page, oversized first record deferred.
        assertEquals(19 * 2, observations) // Source and staging; destination absent in intent.
        assertTrue(report.needsAttention)
        assertEquals(25, journal.records.size)
    }

    @Test fun externalDeletionAndStaleRemovalFlagsNeverBecomeAutomaticCompletion() = runBlocking {
        val files = FixtureFiles(temporary.newFolder().toPath(), false)
        val journal = MemoryJournal()
        val source = Files.write(files.roots.downloads.resolve("data"), byteArrayOf(1, 2, 3))
        val original = (OperationCoordinator(files, journal, { true }).move(id(), source.toString(), true) as OperationResult.Recorded).record
        Files.delete(source) // Another actor removes the original; the organizer did not.
        val stale = original.copy(phase = Phase.SOURCE_REMOVED, error = null, removalAuthorized = true,
            sourceRemoved = true, sourceRemovalSynced = true, removalEvidence = "adapter-confirmed")
        val recovery = OperationRecovery(files, journal, { true })
        val result = recovery.classify(stale, files.observe(stale.sourcePath, 3), files.observe(stale.stagingPath, 3),
            files.observe(requireNotNull(stale.destinationPath), 3))
        assertEquals(Phase.RECOVERY_REQUIRED, result.phase)
        assertEquals(OperationError.REMOVAL_UNCONFIRMED, result.error)
        assertFalse(result.undoEligible)
        assertNull(result.completedAt)
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(Paths.get(requireNotNull(result.destinationPath))))
        assertEquals(original, journal.get(original.id)) // Classification alone cannot rewrite historical evidence.
    }

    @Test fun everyRemovalInterruptionRetainsUnconfirmedStateEvenWithSyncFlags() = runBlocking {
        for (stop in listOf(Checkpoint.REMOVAL_INTENT_SAVED, Checkpoint.BEFORE_REMOVAL, Checkpoint.AFTER_REMOVAL,
                Checkpoint.SOURCE_REMOVED_SAVED, Checkpoint.BEFORE_COMPLETION)) {
            class Stop : Error()
            val files = FixtureFiles(temporary.newFolder().toPath())
            val journal = MemoryJournal()
            val source = Files.write(files.roots.downloads.resolve("data"), byteArrayOf(1, 2))
            try { OperationCoordinator(files, journal, { true }, checkpoint = { if (it == stop) throw Stop() }).move(id(), source.toString(), true); fail() }
            catch (_: Stop) { }
            if (Files.exists(source)) Files.delete(source) // External disappearance before any app-controlled removal.
            OperationRecovery(files, journal, { true }).reconcile()
            val once = journal.records.values.single()
            assertEquals(stop.name, Phase.RECOVERY_REQUIRED, once.phase)
            assertEquals(OperationError.REMOVAL_UNCONFIRMED, once.error)
            assertFalse(once.undoEligible)
            assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(Paths.get(requireNotNull(once.destinationPath))))
            OperationRecovery(files, journal, { true }).reconcile()
            assertEquals(once, journal.records.values.single())
        }
    }

    @Test fun largeAndPermanentRecordsCannotStarveLaterRecordsOrSpamEvents() = runBlocking {
        val files = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        val records = (0..44).map { number ->
            val source = Files.write(files.roots.downloads.resolve("data-$number"), byteArrayOf(1))
            val evidence = files.prepare(source.toString(), OperationType.MOVE)
            val operationId = UUID(0L, number.toLong()).toString()
            OperationRecord(operationId, OperationType.MOVE, null, source.toString(), files.roots.other.toString(), "data-$number",
                evidence.identity, if (number < 20) 100L * 1024 * 1024 else evidence.size, evidence.stamp, evidence.hash,
                "claim-$number", files.roots.other.resolve(".s26-$operationId.part").toString(), startedAt = 1, updatedAt = 1)
                .also { journal.create(it) }
        }
        val seen = mutableSetOf<String>()
        val observations = object : OperationFiles by files {
            override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation {
                seen.add(path)
                return if (path.endsWith("data-44")) files.observe(path, maximumBytes, checkpoint) else Observation.Unverifiable
            }
        }
        repeat(3) { OperationRecovery(observations, journal, { true }).reconcile() } // New instance each time, durable journal cursor.
        assertTrue(seen.contains(records.last().sourcePath))
        assertEquals(Phase.RECOVERY_REQUIRED, journal.get(records.first().id)?.phase)
        assertEquals(OperationError.RECOVERY_BUDGET, journal.get(records.first().id)?.error)
        val revisions = journal.records.mapValues { it.value.revision }
        repeat(4) { OperationRecovery(observations, journal, { true }).reconcile() }
        assertEquals(revisions, journal.records.mapValues { it.value.revision })
        assertEquals(45, journal.records.size)
        assertTrue(records.all { Files.exists(Paths.get(it.sourcePath)) })
    }

    @Test fun aRowWriteFailureDoesNotPreventLaterRecoveryAndCancellationDoesNotLosePosition() = runBlocking {
        val files = FixtureFiles(temporary.newFolder().toPath())
        val journal = MemoryJournal()
        val prepared = (0..1).map { index ->
            val source = Files.write(files.roots.downloads.resolve("data-$index"), byteArrayOf(1))
            val evidence = files.prepare(source.toString(), OperationType.MOVE)
            val operationId = UUID(0L, index.toLong()).toString()
            OperationRecord(operationId, OperationType.MOVE, null, source.toString(), files.roots.other.toString(), "data-$index",
                evidence.identity, evidence.size, evidence.stamp, evidence.hash, "claim-$index",
                files.roots.other.resolve(".s26-$operationId.part").toString(), startedAt = 1, updatedAt = 1).also { journal.create(it) }
        }
        journal.failBefore = { it.id == prepared.first().id }
        val report = OperationRecovery(files, journal, { true }).reconcile()
        assertEquals(1, report.journalFailures)
        assertEquals(Phase.PREPARED, journal.get(prepared.first().id)?.phase)
        assertEquals(Phase.FAILED_SAFE, journal.get(prepared.last().id)?.phase)
        journal.failBefore = { false }
        val cancelling = object : OperationFiles by files {
            override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation = throw kotlinx.coroutines.CancellationException("fixture")
        }
        try { OperationRecovery(cancelling, journal, { true }).reconcile(); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(Phase.PREPARED, journal.get(prepared.first().id)?.phase)
        OperationRecovery(files, journal, { true }).reconcile()
        assertEquals(Phase.FAILED_SAFE, journal.get(prepared.first().id)?.phase)
        assertTrue(prepared.all { Files.exists(Paths.get(it.sourcePath)) })
    }

}

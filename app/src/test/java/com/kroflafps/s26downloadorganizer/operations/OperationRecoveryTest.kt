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
            override fun observe(path: String, maximumBytes: Long): Observation = when (val actual = fixture.observe(path, maximumBytes)) {
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
            override fun observe(path: String, maximumBytes: Long): Observation { observations++; return Observation.Unverifiable }
        }
        for (number in 0..24) {
            val source = Files.write(fixture.roots.downloads.resolve("data-$number"), byteArrayOf(1))
            val evidence = fixture.prepare(source.toString(), OperationType.MOVE)
            journal.create(OperationRecord(id(), OperationType.MOVE, null, source.toString(), fixture.roots.other.toString(), "data-$number",
                evidence.identity, if (number == 0) 100L * 1024 * 1024 else evidence.size, evidence.stamp, evidence.hash,
                "claim-$number", fixture.roots.other.resolve(".s26-$number.part").toString(), startedAt = number.toLong(), updatedAt = number.toLong()))
        }
        val report = OperationRecovery(observing, journal, { true }).reconcile()
        assertEquals(19, report.examined) // 20-record page, oversized first record deferred.
        assertEquals(19 * 2, observations) // Source and staging; destination absent in intent.
        assertTrue(report.needsAttention)
        assertEquals(25, journal.records.size)
    }
}

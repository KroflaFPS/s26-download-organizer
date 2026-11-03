package com.kroflafps.s26downloadorganizer.history

import android.app.Application
import com.kroflafps.s26downloadorganizer.diagnostic.*
import com.kroflafps.s26downloadorganizer.operations.*
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room-generated DAO and file-backed SQLite in Robolectric; not Android 17 hardware proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class JournalDatabaseTest {
    private fun record(id: String = UUID.randomUUID().toString(), start: Long = 100) = OperationRecord(
        id, OperationType.MOVE, null, "/fixture/Download/file", "/fixture/Organized/Other", "file",
        "fixture-source-key", 4, "mtime|ctime", "a".repeat(64), "source-$id", "/fixture/Organized/Other/.s26-$id.part",
        startedAt = start, updatedAt = start,
    )

    @Test fun journalAndEventsSurviveClosingAndReopeningDatabase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val entry = record()
        var database = JournalDatabase.open(context)
        assertTrue(database.journal().create(entry))
        database.journal().save(entry.copy(phase = Phase.ALLOCATING, revision = 1, updatedAt = 101))
        database.close()
        database = JournalDatabase.open(context)
        assertEquals(Phase.ALLOCATING, database.journal().get(entry.id)?.record?.phase)
        assertEquals(listOf(0, 1), database.journal().events(entry.id).map { it.revision })
        assertEquals(listOf(100L, 101L), database.journal().events(entry.id).map { it.at })
        assertTrue(context.getDatabasePath("operation-journal.db").isFile)
        val exported = org.json.JSONObject(java.io.File(requireNotNull(System.getProperty("s26.schema"))).readText())
            .getJSONObject("database").getString("identityHash")
        database.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(exported, cursor.getString(0))
        }
        database.close()
    }

    @Test fun claimsAndCompareAndSetAreAtomicAndDoNotOverwriteJournalRows() = runBlocking {
        val database = JournalDatabase.open(RuntimeEnvironment.getApplication())
        val dao = database.journal()
        val entry = record()
        assertTrue(dao.create(entry))
        assertFalse(dao.create(entry.copy(id = UUID.randomUUID().toString())))
        assertEquals(entry, dao.get(entry.id)?.record)
        try { dao.save(entry.copy(revision = 2, phase = Phase.COPYING)); fail("Stale/out-of-order revision accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(entry, dao.get(entry.id)?.record)
        assertEquals(1, dao.events(entry.id).size)
        database.close()
    }

    @Test fun historyPrioritizesUnresolvedRecordsAndShowsUndoClaims() = runBlocking {
        val database = JournalDatabase.open(RuntimeEnvironment.getApplication())
        val root = Files.createTempDirectory(RuntimeEnvironment.getApplication().cacheDir.toPath(), "history-fixture-")
        val files = FixtureFiles(root)
        val source = Files.write(files.roots.downloads.resolve("file"), byteArrayOf(1, 2, 3, 4))
        val first = (OperationCoordinator(files, RoomOperationJournal(database.journal()), { true }).move(UUID.randomUUID().toString(), source.toString(), true) as OperationResult.Recorded).record
        val unresolved = record(start = first.startedAt + 10)
        val latest = record(start = first.startedAt + 20)
        assertTrue(database.journal().create(unresolved)); assertTrue(database.journal().create(latest))
        database.journal().save(unresolved.copy(phase = Phase.RECOVERY_REQUIRED, recoveryNeeded = true, revision = 1))
        val undo = record(start = first.startedAt + 30).copy(type = OperationType.UNDO, relatedId = first.id)
        assertTrue(database.journal().create(undo))
        val history = database.journal().history().first()
        assertEquals(listOf(undo.id, latest.id, unresolved.id, first.id), history.map { it.record.id })
        assertTrue(history.single { it.record.id == first.id }.undoRequested)
        assertEquals(3, database.journal().unresolvedCount().first())
        database.close()
    }

    @Test fun unsupportedDatabaseVersionFailsClosedWithoutDeletingJournal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var database = JournalDatabase.open(context)
        val entry = record()
        assertTrue(database.journal().create(entry))
        database.openHelper.writableDatabase.execSQL("PRAGMA user_version=4")
        database.close()
        database = JournalDatabase.open(context)
        try { database.journal().get(entry.id); fail("Missing downgrade migration must fail closed") }
        catch (_: Exception) { /* The app must report unavailability, never recreate the database. */ }
        database.close()
        // Inspect the isolated fixture with SQLite directly: the row survived the rejected open.
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("operation-journal.db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT id FROM operations WHERE id = ?", arrayOf(entry.id)).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(entry.id, cursor.getString(0))
            }
        }
    }

    @Test fun terminationRecoveryWorksWithRoomReopenedAtEveryImportantBoundary() = runBlocking {
        class Terminated : Error()
        val context = RuntimeEnvironment.getApplication()
        for (stop in Checkpoint.entries) {
            val name = "phase-${UUID.randomUUID()}.db"
            val root = Files.createTempDirectory(context.cacheDir.toPath(), "disposable-operation-")
            val files = FixtureFiles(root)
            val source = Files.write(files.roots.downloads.resolve("data.bin"), byteArrayOf(1, 2, 3, 4))
            val hash = FixtureFiles.hash(source)
            val id = UUID.randomUUID().toString()
            var database = JournalDatabase.open(context, name)
            try {
                OperationCoordinator(files, RoomOperationJournal(database.journal()), { true }, checkpoint = {
                    if (it == stop) throw Terminated()
                }).move(id, source.toString(), true)
                fail(stop.name)
            } catch (_: Terminated) { }
            database.close()
            database = JournalDatabase.open(context, name)
            val journal = RoomOperationJournal(database.journal())
            val recovery = OperationRecovery(files, journal, { true })
            recovery.reconcile()
            val once = journal.get(id)
            recovery.reconcile()
            assertEquals(stop.name, once, journal.get(id))
            assertNotNull(once)
            if (Files.exists(source)) assertEquals(hash, FixtureFiles.hash(source))
            else assertEquals(hash, FixtureFiles.hash(Paths.get(requireNotNull(once?.destinationPath))))
            database.close()
        }
    }


    @Test fun migrationPreservesRowsAndQuarantinesLegacyInferredCompletion() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "migration-fixture-")
        val files = FixtureFiles(root)
        val source = Files.write(files.roots.downloads.resolve("data"), byteArrayOf(1, 2))
        var database = JournalDatabase.open(context)
        val complete = (OperationCoordinator(files, RoomOperationJournal(database.journal()), { true }).move(UUID.randomUUID().toString(), source.toString(), true) as OperationResult.Recorded).record
        val pending = record(); assertTrue(database.journal().create(pending))
        val secondSource = Files.write(files.roots.downloads.resolve("confirmed"), byteArrayOf(3))
        val confirmed = (OperationCoordinator(files, RoomOperationJournal(database.journal()), { true }).move(UUID.randomUUID().toString(), secondSource.toString(), true) as OperationResult.Recorded).record
        val confirmedEvents = database.journal().events(confirmed.id)
        val eventsBefore = database.journal().events(complete.id)
        val oldSchema = org.json.JSONObject(java.io.File(requireNotNull(System.getProperty("s26.schema"))) .parentFile!!.resolve("1.json").readText()).getJSONObject("database")
        val sql = database.openHelper.writableDatabase
        // Downgrade the disposable fixture structurally to the exact v1 layout/hash, without deleting operation data.
        sql.execSQL("DROP TABLE recovery_cursor")
        sql.execSQL("DROP TABLE diagnostic_copies")
        sql.execSQL("DROP TABLE diagnostic_events")
        sql.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", arrayOf(oldSchema.getString("identityHash")))
        sql.execSQL("UPDATE operations SET removalEvidence='absent-after-recorded-removal-intent' WHERE id=?", arrayOf(complete.id))
        sql.execSQL("PRAGMA user_version=1")
        database.close()
        database = JournalDatabase.open(context)
        val migrated = requireNotNull(database.journal().get(complete.id)).record
        assertEquals(Phase.RECOVERY_REQUIRED, migrated.phase)
        assertEquals(OperationError.REMOVAL_UNCONFIRMED, migrated.error)
        assertEquals(pending, database.journal().get(pending.id)?.record)
        assertEquals(confirmed, database.journal().get(confirmed.id)?.record)
        assertEquals(confirmedEvents, database.journal().events(confirmed.id))
        assertEquals(complete.sourceToken, migrated.sourceToken)
        assertEquals(complete.contentHash, migrated.contentHash)
        assertNull(migrated.completedAt)
        assertFalse(migrated.undoEligible)
        assertEquals(complete.revision + 1, migrated.revision)
        assertEquals(eventsBefore, database.journal().events(complete.id).dropLast(1))
        assertEquals(Phase.RECOVERY_REQUIRED, database.journal().events(complete.id).last().phase)
        assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(Paths.get(requireNotNull(complete.destinationPath))))
        database.close()
        database = JournalDatabase.open(context)
        assertEquals(migrated, database.journal().get(complete.id)?.record)
        database.close()
    }

    @Test fun cursorSurvivesReopenAndPageBoundariesDoNotUseMutableTimestamps() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var database = JournalDatabase.open(context)
        val rows = (0..44).map { index -> record(UUID(0, index.toLong()).toString(), index.toLong()).also { database.journal().create(it) } }
        val first = database.journal().nextRecoveryPage(20)
        assertEquals(rows.take(20), first.records)
        first.records.forEach { database.journal().advanceRecoveryCursor(it.id, requireNotNull(first.ceilingId)) }
        database.close()
        database = JournalDatabase.open(context)
        val second = database.journal().nextRecoveryPage(20)
        assertEquals(rows.drop(20).take(20), second.records)
        // A newly inserted high ID does not extend the current cycle and prevent wrapping.
        database.journal().create(record(UUID(0, 100).toString(), 100))
        second.records.forEach { database.journal().advanceRecoveryCursor(it.id, requireNotNull(second.ceilingId)) }
        val last = database.journal().nextRecoveryPage(20)
        assertEquals(rows.drop(40), last.records)
        last.records.forEach { database.journal().advanceRecoveryCursor(it.id, requireNotNull(last.ceilingId)) }
        assertEquals(rows.take(20), database.journal().nextRecoveryPage(20).records)
        assertEquals(rows.last().revision, database.journal().get(rows.last().id)?.record?.revision)
        database.close()
    }

    @Test fun illegalTransitionsClaimReleaseAndEventFailureCannotPartiallyRewriteRows() = runBlocking {
        val database = JournalDatabase.open(RuntimeEnvironment.getApplication())
        val dao = database.journal(); val original = record()
        assertTrue(dao.create(original))
        val invalid = listOf(
            original.copy(phase = Phase.COPY_VERIFIED, bytesCopied = 4, destinationVerified = true, revision = 1),
            original.copy(phase = Phase.ALLOCATING, sourceToken = null, revision = 1),
            original.copy(phase = Phase.ALLOCATING, contentHash = "b".repeat(64), revision = 1),
            original.copy(phase = Phase.ALLOCATING, revision = 2),
        )
        for (next in invalid) {
            try { dao.save(next); fail("Invalid journal transition") } catch (_: IllegalArgumentException) { }
            assertEquals(original, dao.get(original.id)?.record)
            assertEquals(1, dao.events(original.id).size)
        }
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_event BEFORE INSERT ON operation_events WHEN NEW.revision=1 BEGIN SELECT RAISE(ABORT, 'fixture event failure'); END")
        val next = original.copy(phase = Phase.ALLOCATING, revision = 1)
        try { dao.save(next); fail() } catch (_: Exception) { }
        assertEquals(original, dao.get(original.id)?.record)
        assertEquals(1, dao.events(original.id).size)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_event")
        dao.save(next)
        assertEquals(next, dao.get(original.id)?.record)
        assertEquals(2, dao.events(original.id).size)
        database.close()
    }

    @Test fun uniqueUndoClaimCannotAttachToAnIncompleteParentOrCreateTwoReverseIntents() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = JournalDatabase.open(context)
        val prepared = record(); database.journal().create(prepared)
        try { database.journal().create(record().copy(type = OperationType.UNDO, relatedId = prepared.id)); fail() } catch (_: IllegalArgumentException) { }
        val files = FixtureFiles(Files.createTempDirectory(context.cacheDir.toPath(), "undo-claim-fixture-"))
        val source = Files.write(files.roots.downloads.resolve("data"), byteArrayOf(1))
        val complete = (OperationCoordinator(files, RoomOperationJournal(database.journal()), { true }).move(UUID.randomUUID().toString(), source.toString(), true) as OperationResult.Recorded).record
        val undo = record().copy(type = OperationType.UNDO, relatedId = complete.id)
        assertTrue(database.journal().create(undo))
        val duplicateRelated = record().copy(type = OperationType.UNDO, relatedId = complete.id)
        assertFalse(database.journal().create(duplicateRelated))
        assertNull(database.journal().get(duplicateRelated.id))
        assertTrue(database.journal().events(duplicateRelated.id).isEmpty())
        assertEquals(undo, database.journal().undoFor(complete.id)?.record)
        database.close()
    }

    @Test fun mismatchedEventTailBlocksRevisionWithoutRewritingEvidence() = runBlocking {
        val database = JournalDatabase.open(RuntimeEnvironment.getApplication())
        val dao = database.journal(); val original = record()
        assertTrue(dao.create(original))
        database.openHelper.writableDatabase.execSQL("UPDATE operation_events SET phase='COPYING' WHERE operationId=?", arrayOf(original.id))
        try { dao.save(original.copy(phase = Phase.ALLOCATING, revision = 1)); fail("Event mismatch accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(original, dao.get(original.id)?.record)
        assertEquals(1, dao.events(original.id).size)
        assertEquals(Phase.COPYING, dao.events(original.id).single().phase)
        database.close()
    }

    @Test fun migrationTwoToThreePreservesLegacyRowsEventsAndSupportsSeparateCopyJournal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var database = JournalDatabase.open(context)
        val original = record(); database.journal().create(original)
        val previousEvents = database.journal().events(original.id)
        val schema = org.json.JSONObject(java.io.File(requireNotNull(System.getProperty("s26.schema"))).parentFile!!.resolve("2.json").readText()).getJSONObject("database")
        val sql = database.openHelper.writableDatabase
        sql.execSQL("DROP TABLE diagnostic_copies"); sql.execSQL("DROP TABLE diagnostic_events")
        sql.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", arrayOf(schema.getString("identityHash")))
        sql.execSQL("PRAGMA user_version=2"); database.close()
        database = JournalDatabase.open(context)
        assertEquals(original, database.journal().get(original.id)?.record)
        assertEquals(previousEvents, database.journal().events(original.id))
        assertTrue(database.diagnostic().history().first().isEmpty())
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "copy-migration-")
        Files.createDirectories(root.resolve(DiagnosticPolicy.INPUT)); Files.createDirectories(root.resolve(DiagnosticPolicy.OUTPUT))
        val source = Files.write(root.resolve(DiagnosticPolicy.INPUT).resolve("s26-test-empty.bin"), byteArrayOf())
        val engine = DiagnosticCopyCoordinator({ DiagnosticIo(FixtureDirectories(root)) }, database.diagnostic(), { true }, { true })
        val copied = engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true)
        assertEquals(CopyPhase.COPY_ONLY, copied.phase)
        val events = database.diagnostic().events(copied.id)
        assertEquals((0..copied.revision).toList(), events.map { it.revision })
        database.close(); database = JournalDatabase.open(context)
        assertEquals(copied, database.diagnostic().get(copied.id))
        assertEquals(events, database.diagnostic().events(copied.id))
        assertTrue(database.diagnostic().page().isEmpty())
        assertTrue(Files.exists(source)); assertEquals(0L, Files.size(Paths.get(requireNotNull(copied.finalPath))))
        assertEquals(original, database.journal().get(original.id)?.record)
        database.close()
    }

    @Test fun diagnosticEventFailureRollsBackAndReopenedRecoveryPreservesPartialOutput() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var database = JournalDatabase.open(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "copy-rollback-")
        Files.createDirectories(root.resolve(DiagnosticPolicy.INPUT)); Files.createDirectories(root.resolve(DiagnosticPolicy.OUTPUT))
        val source = Files.write(root.resolve(DiagnosticPolicy.INPUT).resolve("s26-test-empty.bin"), byteArrayOf())
        val id = UUID.randomUUID().toString()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_copy_event BEFORE INSERT ON diagnostic_events WHEN NEW.revision=1 BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        val engine = DiagnosticCopyCoordinator({ DiagnosticIo(FixtureDirectories(root)) }, database.diagnostic(), { true }, { true })
        try { engine.copy(id, source.fileName.toString(), true); fail() } catch (_: Exception) { }
        val intent = requireNotNull(database.diagnostic().get(id))
        assertEquals(CopyPhase.STAGE_ALLOCATING, intent.phase)
        assertEquals(1, database.diagnostic().events(id).size)
        assertTrue(Files.exists(Paths.get(intent.stagePath)))
        assertTrue(Files.exists(source))
        // Active source claims prevent another request adopting an unknown stage or creating duplicates.
        assertFalse(database.diagnostic().create(intent.copy(id = UUID.randomUUID().toString(), stagePath = root.resolve(DiagnosticPolicy.OUTPUT).resolve(".s26-diagnostic-00000000-0000-0000-0000-000000000000.part").toString()).let {
            it.copy(stagePath = root.resolve(DiagnosticPolicy.OUTPUT).resolve(".s26-diagnostic-${it.id}.part").toString())
        }))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_copy_event")
        database.close(); database = JournalDatabase.open(context)
        val recovery = DiagnosticCopyCoordinator({ DiagnosticIo(FixtureDirectories(root)) }, database.diagnostic(), { true }, { true })
        recovery.recover(); val once = database.diagnostic().get(id); recovery.recover()
        assertEquals(once, database.diagnostic().get(id))
        assertEquals(CopyPhase.RECOVERY_REQUIRED, once?.phase)
        assertTrue(Files.exists(source)); assertTrue(Files.exists(Paths.get(intent.stagePath)))
        assertNull(once?.finalPath)
        database.close()
    }

}

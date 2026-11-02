package com.kroflafps.s26downloadorganizer.history

import android.app.Application
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
        val first = record(start = 10)
        val unresolved = record(start = 20).copy(recoveryNeeded = true, phase = Phase.RECOVERY_REQUIRED)
        val latest = record(start = 30)
        assertTrue(database.journal().create(first)); assertTrue(database.journal().create(unresolved)); assertTrue(database.journal().create(latest))
        val undo = record(start = 40).copy(type = OperationType.UNDO, relatedId = first.id)
        assertTrue(database.journal().create(undo))
        val history = database.journal().history().first()
        assertEquals(listOf(unresolved.id, undo.id, latest.id, first.id), history.map { it.record.id })
        assertTrue(history.single { it.record.id == first.id }.undoRequested)
        assertEquals(1, database.journal().unresolvedCount().first())
        database.close()
    }

    @Test fun unsupportedDatabaseVersionFailsClosedWithoutDeletingJournal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var database = JournalDatabase.open(context)
        val entry = record()
        assertTrue(database.journal().create(entry))
        database.openHelper.writableDatabase.execSQL("PRAGMA user_version=2")
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

}

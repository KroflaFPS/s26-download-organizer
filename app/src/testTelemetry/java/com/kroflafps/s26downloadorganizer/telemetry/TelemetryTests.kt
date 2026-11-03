package com.kroflafps.s26downloadorganizer.telemetry

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.kroflafps.s26downloadorganizer.readiness.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TelemetryTests {
    private fun database() = TelemetryDatabase.open(RuntimeEnvironment.getApplication(), "test-${UUID.randomUUID()}.db")
    private val file = MetadataEvidence("hashed-relative-path", "dev:ino", 0, 1, false, null)
    private fun engine(dao: TelemetryDao, scanner: MetadataPages, permission: () -> Boolean = { true }, clock: () -> Long = { 1000L }) = TelemetryCoordinator(dao, scanner, permission, { true }, { "boot-1" }, clock, clock)
    private fun scanner(page: MetadataPage = MetadataPage(listOf(file), emptyList(), null, 0)) = object : MetadataPages {
        override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage { check(); return page }
    }
    @Test @Config(sdk = [26, 29])
    fun legacyAndroidDeniesTelemetryAndIgnoresBootWithoutInitializingNewerAdapters() {
        assertFalse(TelemetryGate.supported())
        assertFalse(TelemetryGate.canRead())
        // Generic Application deliberately has no telemetry graph. An unsafe legacy dispatch would crash.
        TelemetryRestoreReceiver().onReceive(RuntimeEnvironment.getApplication(), android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
    }
    @Test fun nativeTriggerUsesBothRootsDescendantsAndOneShotNonPersistedDelivery() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("COALESCED_OR_UNKNOWN_URI_HINT", AndroidMetadataPages(context, "fixture-salt").hint(null))
        assertEquals("COALESCED_OR_UNKNOWN_URI_HINT", AndroidMetadataPages(context, "fixture-salt").hint(emptyArray()))
        val job = TelemetryScheduling.trigger(context)
        assertFalse(job.isPeriodic); assertFalse(job.isPersisted)
        assertEquals(2, job.triggerContentUris!!.size)
        assertEquals(setOf("content://media/external/downloads", "content://media/external/file"), job.triggerContentUris!!.map { it.uri.toString() }.toSet())
        job.triggerContentUris!!.forEach { assertEquals(android.app.job.JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS, it.flags) }
        assertEquals(2000L, job.triggerContentUpdateDelay); assertEquals(15_000L, job.triggerContentMaxDelay)
        assertNotEquals(job.extras.getString("registration"), TelemetryScheduling.trigger(context).extras.getString("registration"))
    }
    @Test fun replacementRearmsOnlyAfterWorkAndBeforeFinishWithoutCancellingFinishedRun() {
        val state = TriggerLifecycle(); val calls = mutableListOf<String>()
        calls.add("work")
        state.finish({ calls.add("rearm"); assertFalse(state.stop { calls.add("cancel") }) }, { calls.add("jobFinished") })
        assertEquals(listOf("work", "rearm", "jobFinished"), calls)
    }
    @Test fun stoppedJobCancelsAndRequestsFrameworkRetryWithoutEarlyRearming() {
        val state = TriggerLifecycle(); var cancelled = false; var finished = false
        assertTrue(state.stop { cancelled = true }); state.finish({ fail("Rearmed stopped job") }, { finished = true })
        assertTrue(cancelled); assertFalse(finished)
    }
    @Test fun periodicWorkIsUniqueLowFrequencyAndDeferredWorkIsBoundedNotExpedited() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build())
        val manager = WorkManager.getInstance(context)
        repeat(2) { manager.enqueueUniquePeriodicWork(TelemetryScheduling.PERIODIC, ExistingPeriodicWorkPolicy.KEEP, TelemetryScheduling.periodicRequest()).result.get() }
        assertEquals(1, manager.getWorkInfosForUniqueWork(TelemetryScheduling.PERIODIC).get().size)
        val periodic = TelemetryScheduling.periodicRequest().workSpec
        assertEquals(TimeUnit.HOURS.toMillis(2), periodic.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(2), periodic.initialDelay)
        assertFalse(periodic.expedited)
        assertEquals(15_000L, TelemetryScheduling.drainRequest().workSpec.initialDelay)
        manager.cancelAllWork().result.get()
        Unit
    }
    @Test fun transactionLeaseFencesOtherProcessesStaleCommitsAndStaleRelease() = runBlocking {
        val db = database(); val dao = db.telemetry()
        dao.request("MANUAL", 1000)
        assertTrue(dao.acquire("old", "boot", 1000)); assertFalse(dao.acquire("other", "boot", 2000))
        val task = requireNotNull(dao.nextDirectory())
        assertTrue(dao.acquire("new", "boot", 40_000))
        assertFalse(dao.commit("old", "boot", 40_001, task, null, emptyList(), emptyList(), TelemetryEvent(at = 40001, source = "MANUAL", code = "METADATA_PAGE"), 0))
        dao.release("old"); assertEquals("new", dao.state()?.owner)
        assertTrue(dao.acquire("reboot", "new-boot", 1)); assertEquals(task, dao.nextDirectory())
        db.close()
    }
    @Test fun checkpointsAndObservationsSurviveReopenAndContinueSubfoldersWithoutRootMtime() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val name = "reopen-${UUID.randomUUID()}.db"
        var db = TelemetryDatabase.open(context, name)
        var pages = 0
        val source = object : MetadataPages {
            override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage {
                check(); pages++
                return if (task.relative.isEmpty()) MetadataPage(emptyList(), listOf(DirectoryTask("nested", 1)), null, 0)
                else MetadataPage(listOf(file), emptyList(), null, 0)
            }
        }
        assertTrue(engine(db.telemetry(), source).run("CONTENT"))
        assertEquals("nested", db.telemetry().nextDirectory()?.relative)
        db.close(); db = TelemetryDatabase.open(context, name)
        assertFalse(engine(db.telemetry(), source, clock = { 2000 }).run("DEFERRED", signal = false))
        assertEquals(2, pages); assertEquals(1, db.telemetry().observations().size)
        assertEquals("CYCLE_COMPLETE_NOT_ATOMIC", db.telemetry().state()?.coverage)
        db.close()
    }
    @Test fun irrelevantSignalsAreDurablyDeferredAndNeverDiscardSubfolderWork() = runBlocking {
        val db = database(); var calls = 0
        val source = object : MetadataPages { override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage { calls++; return MetadataPage(listOf(file), emptyList(), null, 0) } }
        val coordinator = engine(db.telemetry(), source)
        assertTrue(coordinator.run("CONTENT", hint = "IRRELEVANT_URI_HINT_SCAN_STILL_REQUIRED")); assertEquals(0, calls)
        assertTrue(db.telemetry().pending())
        assertFalse(coordinator.run("DEFERRED", signal = false)); assertEquals(1, calls)
        db.close()
    }
    @Test fun permissionRevocationAndCancellationKeepCheckpointAndProduceNoFalseCompletion() = runBlocking {
        val db = database(); var access = false; var calls = 0
        val source = object : MetadataPages {
            override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage { calls++; access = false; check(); error("Must stop") }
        }
        val coordinator = engine(db.telemetry(), source, { access })
        assertFalse(coordinator.run("MANUAL")); assertEquals(0, calls); assertTrue(db.telemetry().pending())
        access = true; coordinator.run("MANUAL"); assertNotNull(db.telemetry().nextDirectory()); assertEquals(0L, db.telemetry().state()?.lastScan)
        val cancel = object : MetadataPages { override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage = throw CancellationException("fixture") }
        try { engine(db.telemetry(), cancel).run("MANUAL"); fail() } catch (_: CancellationException) { }
        assertNotNull(db.telemetry().nextDirectory()); assertNull(db.telemetry().state()?.owner)
        db.close()
    }
    @Test fun permanentlyUnreadableDirectoryDoesNotStarveLaterCheckpoint() = runBlocking {
        val db = database()
        engine(db.telemetry(), scanner(MetadataPage(emptyList(), listOf(DirectoryTask("a", 1), DirectoryTask("b", 1)), null, 0))).run("MANUAL")
        val source = object : MetadataPages {
            override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage {
                if (task.relative == "a") throw java.io.IOException("private path must not be exported")
                return MetadataPage(listOf(file), emptyList(), null, 0)
            }
        }
        val coordinator = engine(db.telemetry(), source)
        assertTrue(coordinator.run("DEFERRED", signal = false)); assertEquals("b", db.telemetry().nextDirectory()?.relative)
        assertFalse(coordinator.run("DEFERRED", signal = false))
        assertEquals("LIMITED_OR_UNREADABLE", db.telemetry().state()?.coverage); assertEquals(1, db.telemetry().observations().size)
        db.close()
    }
    @Test fun eventRetentionIsBoundedAndUnknownDatabaseUpgradeDoesNotEraseLogs() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val name = "retention-${UUID.randomUUID()}.db"
        var db = TelemetryDatabase.open(context, name)
        repeat(420) { db.telemetry().log(TelemetryEvent(at = it.toLong(), source = "MANUAL", code = "SIGNAL")) }
        assertEquals(400, db.telemetry().events().size)
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version=2"); db.close()
        db = TelemetryDatabase.open(context, name)
        try { db.telemetry().state(); fail("Missing downgrade migration erased logs") } catch (_: Exception) { }
        db.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM telemetry_events", null).use { cursor -> cursor.moveToFirst(); assertEquals(400, cursor.getInt(0)) }
        }
    }
    @Test fun failedJournalTransactionDoesNotAdvanceDirectoryCheckpoint() = runBlocking {
        val db = database(); val dao = db.telemetry(); dao.request("MANUAL", 1000)
        assertTrue(dao.acquire("token", "boot", 1000)); val before = requireNotNull(dao.nextDirectory())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_event BEFORE INSERT ON telemetry_events BEGIN SELECT RAISE(ABORT, 'fixture journal failure'); END")
        try { dao.commit("token", "boot", 1001, before, null, emptyList(), emptyList(), TelemetryEvent(at = 1001, source = "MANUAL", code = "METADATA_PAGE"), 0); fail() } catch (_: Exception) { }
        assertEquals(before, dao.nextDirectory()); assertEquals(0L, dao.state()?.lastScan)
        db.close()
    }
    @Test fun defaultExportRejectsPrivateNamesPathsUrisAndUnknownCodesEvenIfInjected() {
        val secret = "/storage/emulated/0/Download/PRIVATE.pdf"
        val event = TelemetryEvent(at = 1000, source = secret, code = "content://media/external/file/123", policy = secret)
        val report = TelemetryExport.render(TelemetryState(coverage = secret), listOf(event), emptyMap(), 0, true, true, true, "powerSave=false;$secret", 37)
        assertFalse(report.contains("PRIVATE")); assertFalse(report.contains("/storage/")); assertFalse(report.contains("content://"))
        assertTrue(report.contains("REDACTED")); assertTrue(report.contains("NOT event/completion-to-wake"))
    }
    @Test fun duplicateRequestsCoalesceDurablyAndRapidReplaysDoNotFakeQuiescence() = runBlocking {
        val db = database(); var now = 1000L; var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = object : MetadataPages {
            override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage {
                calls++; entered.complete(Unit); runBlocking { release.await() }
                return MetadataPage(listOf(file), emptyList(), null, 0)
            }
        }
        val first = async(Dispatchers.IO) { engine(db.telemetry(), source, clock = { now }).run("MANUAL") }
        entered.await()
        assertTrue(engine(db.telemetry(), object : MetadataPages { override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage = error("Concurrent scan must be denied") }).run("CONTENT"))
        release.complete(Unit); assertTrue(first.await())
        assertEquals(1, calls)
        val continuation = engine(db.telemetry(), scanner(), clock = { now })
        continuation.run("DEFERRED", signal = false)
        assertEquals(1, db.telemetry().observations().single().value.samples)
        assertEquals(1, db.telemetry().observations().size)
        now = 16_000; continuation.run("MANUAL"); now = 31_000; continuation.run("MANUAL")
        assertEquals(Readiness.POSSIBLY_READY, db.telemetry().observations().single().value.state)
        assertFalse(db.telemetry().observations().single().value.authorizesMutation)
        db.close()
    }

    @Test fun lexicalPaginationHandlesRealFixtureNamesAndEarlierSubfolderAdditionsOnNextCycle() {
        val root = java.nio.file.Files.createTempDirectory(RuntimeEnvironment.getApplication().cacheDir.toPath(), "metadata-only-fixtures-")
        repeat(140) { java.nio.file.Files.write(root.resolve("file-%03d.bin".format(it)), byteArrayOf()) }
        val damaged = java.nio.file.Files.write(root.resolve("damaged.zip"), byteArrayOf(0x50, 0x4b, 0, 0))
        java.nio.file.Files.write(root.resolve("空 白.crdownload"), byteArrayOf(1))
        fun names() = java.nio.file.Files.newDirectoryStream(root).use { it.map { entry -> entry.fileName.toString() }.toList() }
        val first = ScanWindow.select(names(), null)
        assertEquals(128, first.names.size)
        val nested = java.nio.file.Files.createDirectory(root.resolve("a-new-subfolder"))
        val second = ScanWindow.select(names(), first.nextAfter)
        assertFalse(second.names.contains(nested.fileName.toString()))
        assertTrue(ScanWindow.select(names(), null).names.contains(nested.fileName.toString())) // Fresh cycles cannot trust root mtime to skip children.
        assertNull(second.nextAfter)
        assertArrayEquals(byteArrayOf(0x50, 0x4b, 0, 0), java.nio.file.Files.readAllBytes(damaged))
        val attributes = java.nio.file.Files.readAttributes(damaged, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val evidence = MetadataEvidence("fixture-key", attributes.fileKey().toString(), attributes.size(), attributes.lastModifiedTime().toMillis(), false, null)
        assertNotEquals(Readiness.READY_WITH_EVIDENCE, ReadinessPolicy.observe(System.currentTimeMillis(), "boot", evidence, null).state)
        try { ScanWindow.select(listOf("../outside"), null); fail() } catch (_: IllegalArgumentException) { }
        try { ScanWindow.select(List(4097) { "file-$it" }, null); fail() } catch (_: IllegalArgumentException) { }
    }

}

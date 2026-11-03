package com.kroflafps.s26downloadorganizer.telemetry

import android.app.Application
import android.content.Context
import android.os.Looper
import com.google.common.util.concurrent.SettableFuture
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercises WorkManager 2.12 KEEP with an actual RUNNING worker/future and durable telemetry DAO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DrainKeepRaceTest {
    @Test fun lateSignalAfterMoreFalseIsKeptDurableButKeepDoesNotEnqueueFollowup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = TelemetryDatabase.open(context, "keep-race-${UUID.randomUUID()}.db")
        val dao = db.telemetry()
        var scans = 0
        val coordinator = TelemetryCoordinator(dao, object : MetadataPages {
            override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage {
                check(); scans++; return MetadataPage(emptyList(), emptyList(), null, 0)
            }
        }, { true }, { true }, { "fixture-boot" }, { 1000L }, { 1000L })
        dao.request("CONTENT", 1000)
        var held: SettableFuture<ListenableWorker.Result>? = null
        var more: Boolean? = null
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                // Same boundary as TelemetryWorker: coordinator returned more=false, Result not delivered yet.
                return object : ListenableWorker(appContext, workerParameters) {
                    override fun startWork(): com.google.common.util.concurrent.ListenableFuture<Result> {
                        more = runBlocking { coordinator.run("DEFERRED", signal = false) }
                        return SettableFuture.create<Result>().also { held = it }
                    }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.ERROR).setExecutor(java.util.concurrent.Executor { it.run() })
            .setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        try {
            val first = TelemetryScheduling.drainRequest()
            manager.enqueueUniqueWork(TelemetryScheduling.DRAIN, ExistingWorkPolicy.KEEP, first).result.get(10, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(first.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(held)
            assertEquals(false, more)
            assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(first.id).get()!!.state)
            assertFalse(dao.pending())

            // The audited interleaving: another content request lands AFTER more=false, BEFORE old completion.
            dao.request("CONTENT", 2000)
            val late = TelemetryScheduling.drainRequest()
            manager.enqueueUniqueWork(TelemetryScheduling.DRAIN, ExistingWorkPolicy.KEEP, late).result.get(10, TimeUnit.SECONDS)
            assertEquals(listOf(first.id), manager.getWorkInfosForUniqueWork(TelemetryScheduling.DRAIN).get().map { it.id })
            assertNull(manager.getWorkInfoById(late.id).get())
            held!!.set(ListenableWorker.Result.success())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(first.id).get()!!.state)
            assertTrue(dao.pending())
            assertEquals(1, scans) // No hidden follow-up drain. Inexact periodic/later signal/manual is required.
            assertFalse(coordinator.run("PERIODIC", signal = false))
            assertFalse(dao.pending()); assertEquals(2, scans)
        } finally {
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            db.close()
        }
    }
}

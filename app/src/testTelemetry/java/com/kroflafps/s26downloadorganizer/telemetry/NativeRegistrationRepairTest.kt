package com.kroflafps.s26downloadorganizer.telemetry

import android.app.Application
import android.app.AppOpsManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Environment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/** Real scheduling adapter against Robolectric's pending-job registry, NOT a live OS delivery test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class NativeRegistrationRepairTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val jobs get() = context.getSystemService(JobScheduler::class.java)
    private val preferences get() = context.getSharedPreferences("read-only-observation", Context.MODE_PRIVATE)
    private val graph get() = TelemetryGraph(context)
    @Before fun grantFixtureAccess() {
        val primary = ShadowEnvironment.addExternalDir("synthetic-primary")
        ShadowEnvironment.setExternalStorageDirectory(primary.toPath())
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            "android:manage_external_storage", context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        graph.consent(true)
        assertTrue(TelemetryGate.canRead())
    }
    @Test fun successorNeverReplacesAnyUnacknowledgedJobAndSurvivesGraphRecreation() {
        val scheduling = graph.scheduling
        assertTrue(scheduling.register())
        val first = jobs.allPendingJobs.single()
        // Model a finish notification whose framework acknowledgement has NOT arrived: leave old jobs present.
        assertTrue(scheduling.register(completingJobId = first.id))
        assertSame(first, jobs.getPendingJob(first.id))
        val second = jobs.allPendingJobs.single { it.id != first.id }
        assertTrue(second.id > first.id)
        assertTrue(graph.scheduling.register(completingJobId = second.id)) // New graph, durable allocator.
        assertSame(first, jobs.getPendingJob(first.id))
        assertSame(second, jobs.getPendingJob(second.id))
        assertEquals(3, jobs.allPendingJobs.map { it.id }.toSet().size)
        assertTrue(jobs.allPendingJobs.all { it.id >= 20_000 })
        assertTrue(scheduling.register())
        assertEquals(3, jobs.allPendingJobs.size) // UI/periodic must not replace or multiply registrations.
        assertTrue(scheduling.hasSuccessor(first.id))
        assertTrue(scheduling.register(completingJobId = first.id)) // Stale finalizer cannot schedule another.
        assertEquals(3, jobs.allPendingJobs.size)
    }
    @Test fun concurrentCompletionRequestsShareOneFreshSuccessor() {
        assertTrue(graph.scheduling.register())
        val first = jobs.allPendingJobs.single()
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) { pool.submit<Boolean> { start.await(); graph.scheduling.register(completingJobId = first.id) } }
            start.countDown()
            calls.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(2, jobs.allPendingJobs.size)
            assertSame(first, jobs.getPendingJob(first.id))
        } finally { pool.shutdownNow() }
    }
    @Test fun unavailablePrimaryVolumeDeniesReadsAndRegistrationWithoutCrashing() {
        ShadowEnvironment.reset() // Disposable simulated volumes only, not device storage.
        assertFalse(TelemetryGate.canRead())
        assertFalse(graph.scheduling.register())
        assertTrue(jobs.allPendingJobs.isEmpty())
    }
    @Test fun interruptedReservationIsSkippedAndLegacyIdIsNotReplaced() {
        val legacy = TelemetryScheduling.trigger(context, 4001)
        jobs.schedule(legacy)
        assertTrue(graph.scheduling.register())
        assertSame(legacy, jobs.getPendingJob(4001))
        preferences.edit().putLong("nextNativeJobId", 20_005).commit() // Earlier process reserved then died before scheduling.
        assertTrue(graph.scheduling.register(completingJobId = 4001))
        assertNotNull(jobs.getPendingJob(20_005))
        assertSame(legacy, jobs.getPendingJob(4001))
    }
    @Test fun schedulerSnapshotAndCurrentCallbackPreventReuseEvenWithStaleAllocator() {
        jobs.schedule(TelemetryScheduling.trigger(context, 20_100))
        preferences.edit().putLong("nextNativeJobId", 20_000).commit()
        assertTrue(graph.scheduling.register(completingJobId = 20_100))
        assertNotNull(jobs.getPendingJob(20_101))
        jobs.cancelAll() // Only this test's synthetic scheduler registry; never device jobs.
        assertTrue(graph.scheduling.register(completingJobId = 20_200))
        assertNotNull(jobs.getPendingJob(20_201))
    }
    @Test fun exhaustedIdsPauseAndRevocationFailClosedWithoutCancellingOtherServices() {
        preferences.edit().putLong("nextNativeJobId", Int.MAX_VALUE.toLong() + 1).commit()
        assertFalse(graph.scheduling.register()); assertTrue(jobs.allPendingJobs.isEmpty())
        preferences.edit().putLong("nextNativeJobId", 20_000).commit()
        assertTrue(graph.scheduling.register())
        graph.consent(false)
        assertFalse(graph.scheduling.register(completingJobId = 20_000))
        graph.consent(true)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            "android:manage_external_storage", context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ERRORED)
        assertFalse(graph.scheduling.register(completingJobId = 20_000))
        // An unrelated component (e.g. WorkManager) is not a native content registration.
        jobs.schedule(android.app.job.JobInfo.Builder(10_000, ComponentName(context, "unrelated.Service")).setMinimumLatency(1).build())
        // Initialize only the test WorkManager so pause can cancel its named work as well.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build())
        graph.scheduling.cancel()
        assertNull(jobs.getPendingJob(20_000)); assertNotNull(jobs.getPendingJob(10_000))
        assertFalse(graph.scheduling.registered())
    }
}

package com.kroflafps.s26downloadorganizer.telemetry

import android.app.ActivityManager
import android.app.job.*
import android.content.*
import android.os.*
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.work.*
import com.kroflafps.s26downloadorganizer.*
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.storage.StorageAccessAdapter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

object TelemetryGate {
    const val ID = "com.kroflafps.s26downloadorganizer.telemetry"
    @androidx.annotation.ChecksSdkIntAtLeast(api = 30)
    fun supported() = BuildConfig.TELEMETRY_ENABLED && BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == ID && Build.VERSION.SDK_INT >= 30
    @androidx.annotation.ChecksSdkIntAtLeast(api = 30)
    fun canRead() = Build.VERSION.SDK_INT >= 30 && StorageAccessAdapter().check() == StorageAccess.GRANTED &&
        Environment.getExternalStorageState() in listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
}
class TelemetryApplication : OrganizerApplication() {
    val telemetry by lazy { TelemetryGraph(this) }
    override fun onCreate() {
        super.onCreate()
        if (TelemetryGate.supported()) WorkManager.initialize(this, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).setJobSchedulerJobIdRange(10_000, 19_999).build())
        // NEVER rearm a native job here: this application may be starting because that job is executing.
    }
}
class TelemetryGraph(private val context: Context) {
    private val preferences = context.getSharedPreferences("read-only-observation", Context.MODE_PRIVATE)
    val enabled get() = TelemetryGate.supported() && preferences.getBoolean("enabled", false)
    fun consent(value: Boolean) { check(preferences.edit().putBoolean("enabled", value).commit()) }
    val database by lazy { TelemetryDatabase.open(context) }
    val dao get() = database.telemetry()
    private val salt by lazy { preferences.getString("salt", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("salt", it).commit())
    } }
    val scanner: AndroidMetadataPages by lazy {
        if (Build.VERSION.SDK_INT < 30) error("Unsupported platform")
        AndroidMetadataPages(context, salt)
    }
    fun boot(): String = try { "boot-${Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)}" }
        catch (_: Exception) { "boot-estimate-${(System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000}" }
    val coordinator by lazy { TelemetryCoordinator(dao, object : MetadataPages {
        override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage {
            if (Build.VERSION.SDK_INT < 30) error("Unsupported platform")
            return scanner.scan(task, check)
        }
    }, TelemetryGate::canRead, { enabled }, ::boot, System::currentTimeMillis, SystemClock::elapsedRealtime, policy = ::battery) }
    val scheduling by lazy { TelemetryScheduling(context, this) }
    fun battery(): String = try {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val restricted = if (Build.VERSION.SDK_INT >= 28) activity.isBackgroundRestricted.toString() else "unknown"
        val bucket = if (Build.VERSION.SDK_INT >= 28) try { context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket.toString() } catch (_: Exception) { "unknown" } else "unknown"
        "powerSave=${power.isPowerSaveMode}; idle=${power.isDeviceIdleMode}; ignoringOptimizations=${power.isIgnoringBatteryOptimizations(context.packageName)}; backgroundRestricted=$restricted; standbyBucket=$bucket"
    } catch (_: Exception) { "powerSave=unknown;idle=unknown;ignoringOptimizations=unknown;backgroundRestricted=unknown;standbyBucket=unknown" }
}
class TelemetryScheduling(private val context: Context, private val graph: TelemetryGraph) {
    companion object {
        const val FIRST_JOB_ID = 20_000 // Disjoint from WorkManager's 10000–19999 range.
        private val registrationLock = Any() // All app components use the default, single process.
        const val PERIODIC = "s26-readonly-two-hour-reconciliation"
        const val DRAIN = "s26-readonly-checkpoint-drain"
        const val HOURS = 2L
        fun periodicRequest() = PeriodicWorkRequestBuilder<TelemetryWorker>(HOURS, TimeUnit.HOURS)
            .setInputData(workDataOf("source" to "PERIODIC")).setInitialDelay(HOURS, TimeUnit.HOURS).build()
        fun drainRequest() = OneTimeWorkRequestBuilder<TelemetryWorker>().setInputData(workDataOf("source" to "DEFERRED"))
            .setInitialDelay(15, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build()
        @RequiresApi(30) fun trigger(context: Context, id: Int) = JobInfo.Builder(id, ComponentName(context, TelemetryJobService::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .addTriggerContentUri(JobInfo.TriggerContentUri(android.provider.MediaStore.Files.getContentUri("external"), JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setExtras(PersistableBundle().apply { putString("registration", UUID.randomUUID().toString()) })
            .setTriggerContentUpdateDelay(2_000).setTriggerContentMaxDelay(15_000).build()
    }
    private val jobs get() = context.getSystemService(JobScheduler::class.java)
    private fun nativeJobs() = jobs.allPendingJobs.filter { it.service == ComponentName(context, TelemetryJobService::class.java) }
    fun registered(): Boolean = try { nativeJobs().isNotEmpty() } catch (_: Exception) { false } // Snapshot only, not delivery assurance.
    fun hasSuccessor(id: Int): Boolean = try { nativeJobs().any { it.id > id } } catch (_: Exception) { false }
    fun register(completingJobId: Int? = null): Boolean = synchronized(registrationLock) {
        if (Build.VERSION.SDK_INT < 30 || !graph.enabled || !TelemetryGate.canRead()) return false
        try {
            val existing = nativeJobs()
            if (existing.any { completingJobId == null || it.id > completingJobId }) return true
            val preferences = context.getSharedPreferences("read-only-observation", Context.MODE_PRIVATE)
            // Persist reservation BEFORE schedule. Process death skips an ID rather than reusing a live one.
            // Also advance beyond the current callback and every still-pending/running native registration.
            val next = maxOf(preferences.getLong("nextNativeJobId", FIRST_JOB_ID.toLong()),
                FIRST_JOB_ID.toLong(), (existing.maxOfOrNull { it.id } ?: 0).toLong() + 1,
                (completingJobId ?: 0).toLong() + 1)
            if (next > Int.MAX_VALUE || !preferences.edit().putLong("nextNativeJobId", next + 1).commit()) return false
            jobs.schedule(trigger(context, next.toInt())) == JobScheduler.RESULT_SUCCESS
        } catch (_: Exception) { false } // No fixed-ID fallback or wraparound on reservation/scheduler failure.
    }
    fun periodic() { if (graph.enabled) WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodicRequest()) }
    // KEEP can ignore a request while the old drain is finishing (B4-02). Dirty state survives;
    // manual/later signals or inexact periodic work reconcile it. No timely handoff is promised.
    fun drain() { if (graph.enabled) WorkManager.getInstance(context).enqueueUniqueWork(DRAIN, ExistingWorkPolicy.KEEP, drainRequest()) }
    fun cancel() {
        synchronized(registrationLock) { nativeJobs().forEach { jobs.cancel(it.id) } }
        if (TelemetryGate.supported()) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(DRAIN)
        }
    }
}

/** Main-thread state; jobFinished is a message, not acknowledgement that the ID is reusable. */
class TriggerLifecycle {
    private enum class Phase { RUNNING, REARMING, FINISH_SENT, STOPPED }
    private var phase = Phase.RUNNING
    fun stop(cancel: () -> Unit): Boolean {
        if (phase == Phase.STOPPED || phase == Phase.FINISH_SENT) return false
        phase = Phase.STOPPED; cancel(); return true
    }
    fun finish(rearm: () -> Unit, complete: () -> Unit) {
        if (phase != Phase.RUNNING) return
        phase = Phase.REARMING
        try { rearm() } finally {
            if (phase != Phase.STOPPED) { phase = Phase.FINISH_SENT; complete() }
        }
    }
}
class TelemetryJobService : JobService() {
    private data class Active(val lifecycle: TriggerLifecycle, var job: Job? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = mutableMapOf<String, Active>()
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as TelemetryApplication).telemetry
        if (!graph.enabled || !TelemetryGate.supported() || graph.scheduling.hasSuccessor(params.jobId)) return false
        val wake = System.currentTimeMillis()
        val token = params.extras.getString("registration") ?: return false
        val current = Active(TriggerLifecycle()); active[token] = current
        current.job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val hint = if (TelemetryGate.canRead()) graph.scanner.hint(params.triggeredContentUris) else "ACCESS_UNAVAILABLE"
                    graph.coordinator.run("CONTENT", uriCount = params.triggeredContentUris?.size ?: 0, hint = hint, wakeAt = wake)
                    graph.scheduling.drain()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* A pending generation/periodic work can reconcile; no exception paths enter exports. */
            } finally {
                try {
                    // A FRESH ID cannot replace this job even while its asynchronous finish is pending.
                    current.lifecycle.finish({ graph.scheduling.register(completingJobId = params.jobId) }, { jobFinished(params, false) })
                } finally { active.remove(token) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        val token = params.extras.getString("registration") ?: return false
        val current = active[token] ?: return false // A late replacement callback cannot cancel a newer registration.
        val graph = (application as TelemetryApplication).telemetry
        val stopped = current.lifecycle.stop {
            current.job?.cancel()
            CoroutineScope(Dispatchers.IO).launch { try { graph.dao.log(TelemetryEvent(at = System.currentTimeMillis(), source = "CONTENT", code = "STOPPED")) } catch (_: Exception) { } }
        }
        return stopped && graph.enabled && TelemetryGate.canRead() && !graph.scheduling.hasSuccessor(params.jobId)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class TelemetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as? TelemetryApplication)?.telemetry ?: return Result.failure()
        if (!graph.enabled || !TelemetryGate.supported()) return Result.success()
        return try {
            val source = if (inputData.getString("source") == "PERIODIC") "PERIODIC" else "DEFERRED"
            val more = graph.coordinator.run(source, signal = runAttemptCount == 0)
            graph.scheduling.register()
            val sampling = graph.dao.observations().any { it.value.reason == com.kroflafps.s26downloadorganizer.readiness.ReadinessReason.OBSERVING }
            if (TelemetryGate.canRead() && runAttemptCount < 7 && (more || sampling)) {
                if (!more && sampling) graph.dao.request("READINESS_RECHECK", System.currentTimeMillis())
                Result.retry()
            } else Result.success() // Durable unfinished checkpoints remain for the next external/periodic/manual request.
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
class TelemetryRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TelemetryGate.supported() || intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val graph = (context.applicationContext as TelemetryApplication).telemetry
        if (!graph.enabled) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                graph.dao.request(if (intent.action == Intent.ACTION_BOOT_COMPLETED) "BOOT" else "PACKAGE_UPDATE", System.currentTimeMillis())
                graph.scheduling.register(); graph.scheduling.periodic(); graph.scheduling.drain()
            } catch (_: Exception) { /* Next manual launch/periodic attempt can retry. No promise of restricted-state delivery. */ }
            finally { pending.finish() }
        }
    }
}

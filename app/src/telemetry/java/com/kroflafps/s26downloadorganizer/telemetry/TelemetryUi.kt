package com.kroflafps.s26downloadorganizer.telemetry

import android.content.*
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kroflafps.s26downloadorganizer.*
import com.kroflafps.s26downloadorganizer.readiness.Readiness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

/** Export accepts only aggregates/fixed codes, never queue paths, names, identities or URIs. */
object TelemetryExport {
    private val sources = setOf("NONE", "CONTENT", "PERIODIC", "DEFERRED", "READINESS_RECHECK", "BOOT", "PACKAGE_UPDATE", "MANUAL", "SYSTEM")
    private val codes = setOf("SIGNAL", "METADATA_PAGE", "METADATA_PAGE_UNREADABLE", "PERMISSION_OR_STORAGE_UNAVAILABLE", "STALE_LEASE_DISCARDED", "INTERRUPTED_PENDING_RECONCILIATION",
        "SCAN_FAILED_PENDING_RECONCILIATION", "IRRELEVANT_HINT_DURABLE_DEFERRED", "COALESCED_OR_UNKNOWN_URI_HINT", "UNKNOWN_URI_HINT",
        "POSSIBLE_DOWNLOADS_URI_HINT", "IRRELEVANT_URI_HINT_SCAN_STILL_REQUIRED", "ACCESS_UNAVAILABLE", "ENABLED", "PAUSED", "REGISTER_OK", "REGISTER_FAILED", "STOPPED")
    private val coverage = setOf("NOT_SCANNED", "CONTINUING", "LIMITED_OR_UNREADABLE", "CYCLE_COMPLETE_NOT_ATOMIC")
    private fun safePolicy(value: String) = value.split(';').filter { it.trim().matches(Regex("(powerSave|idle|ignoringOptimizations|backgroundRestricted|standbyBucket)=(true|false|unknown|[0-9]+)")) }.joinToString(";")
    fun render(state: TelemetryState?, events: List<TelemetryEvent>, counts: Map<Readiness, Int>, queued: Int,
        enabled: Boolean, granted: Boolean, registered: Boolean, battery: String, sdk: Int): String = buildString {
        appendLine("# S26 Build 4 — READ-ONLY EXPERIMENT")
        appendLine("No organizing, moving, copying or deleting. No writer-closure or mutation authorization.")
        appendLine("Variant: telemetry; version: ${BuildConfig.VERSION_NAME}; API: $sdk; enabled: $enabled; access: $granted; registered snapshot: $registered")
        // Battery text is assembled solely from booleans/integers by the caller; exports still limit its vocabulary.
        appendLine("Current battery snapshot: " + safePolicy(battery))
        appendLine("Latest wake source: ${state?.lastSource?.takeIf { it in sources } ?: "NONE"}")
        appendLine("Last wake UTC: ${state?.lastWake?.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: "none"}")
        appendLine("Last completed metadata page UTC: ${state?.lastScan?.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: "none"}")
        appendLine("Coverage: ${state?.coverage?.takeIf { it in coverage } ?: "NOT_SCANNED"}; queued directories: ${queued.coerceIn(0, 128)}; warnings: ${state?.warnings ?: 0}")
        appendLine("Retained readiness observations (not live inventory): " + Readiness.entries.joinToString { "$it=${counts[it] ?: 0}" })
        appendLine("Event counts in retained window: " + events.take(400).groupingBy { it.source.takeIf { s -> s in sources } ?: "REDACTED" }.eachCount())
        appendLine("Retention: <=400 events / 4000 observations / 7 days. Directory queue <=128; depth <=4; enumeration <=4096 names/directory; pages <=128 entries.")
        appendLine("URI counts are hints. No event timestamp is supplied; rough mtime age below is NOT event/completion-to-wake latency.")
        appendLine("| UTC | source | code | entries | page ms | URI count | rough wake-minus-mtime age ms | battery snapshot |")
        appendLine("| --- | --- | --- | ---: | ---: | ---: | ---: | --- |")
        for (event in events.take(400)) appendLine("| ${Instant.ofEpochMilli(event.at)} | ${event.source.takeIf { it in sources } ?: "REDACTED"} | ${event.code.takeIf { it in codes } ?: "REDACTED"} | ${event.entries} | ${event.durationMs} | ${event.uriCount} | ${event.roughMtimeAge ?: "unknown"} | ${safePolicy(event.policy)} |")
        appendLine("Default export contains no private filenames, file paths, full MediaStore URIs or device identifiers. No richer path export is implemented.")
        appendLine("Doze/One UI/quotas can delay work. Force-stop prevents execution until manual launch. No Samsung coverage or power-loss guarantee follows from CI.")
    }
}
data class TelemetryScreenState(val enabled: Boolean = false, val busy: Boolean = false, val granted: Boolean = false,
    val registered: Boolean = false, val report: String = "Loading private diagnostics…", val message: String? = null)
class TelemetryViewModel(private val graph: TelemetryGraph) : ViewModel() {
    private val mutable = MutableStateFlow(TelemetryScreenState())
    val state = mutable.asStateFlow()
    private var action: Job? = null
    init {
        viewModelScope.launch {
            try { graph.dao.observeState().combine(graph.dao.eventsFlow()) { _, _ -> Unit }.collectLatest { refresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(message = "Private telemetry database unavailable. Do not clear app data; reopen after resolving storage availability.") } }
        }
    }
    private suspend fun refresh() = withContext(Dispatchers.IO) {
        val snapshot = graph.dao.state(); val events = graph.dao.events(); val counts = graph.dao.observations().groupingBy { it.value.state }.eachCount()
        val registered = graph.scheduling.registered(); val granted = TelemetryGate.canRead()
        val report = TelemetryExport.render(snapshot, events, counts, graph.dao.directoryCount(), graph.enabled, granted, registered, graph.battery(), Build.VERSION.SDK_INT)
        mutable.update { it.copy(enabled = graph.enabled, granted = granted, registered = registered, report = report) }
    }
    fun enable() = runAction {
        check(TelemetryGate.supported() && TelemetryGate.canRead())
        graph.consent(true)
        graph.dao.log(TelemetryEvent(at = System.currentTimeMillis(), source = "SYSTEM", code = "ENABLED"))
        graph.scheduling.periodic(); register(); graph.coordinator.run("MANUAL"); graph.scheduling.drain()
    }
    fun pause() = runAction {
        graph.consent(false); graph.scheduling.cancel()
        graph.dao.log(TelemetryEvent(at = System.currentTimeMillis(), source = "SYSTEM", code = "PAUSED"))
    }
    fun scan() = runAction { check(graph.enabled); graph.coordinator.run("MANUAL"); graph.scheduling.drain() }
    fun checkRegistration() = runAction { if (graph.enabled) { register(); graph.scheduling.periodic(); if (graph.dao.pending()) graph.scheduling.drain() } }
    private suspend fun register() { graph.dao.log(TelemetryEvent(at = System.currentTimeMillis(), source = "SYSTEM", code = if (graph.scheduling.register()) "REGISTER_OK" else "REGISTER_FAILED")) }
    private fun runAction(block: suspend () -> Unit) {
        if (action?.isActive == true) return
        mutable.update { it.copy(busy = true, message = null) }
        action = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() }; refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(message = "Read-only action unavailable. Check All Files Access, supported Android version, storage and registration status; retry. No user files were changed.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
class TelemetryActivity : MainActivity() {
    private val telemetryModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(TelemetryViewModel::class.java))
                @Suppress("UNCHECKED_CAST") return TelemetryViewModel((application as TelemetryApplication).telemetry) as T
            }
        })[TelemetryViewModel::class.java]
    }
    @Composable override fun ExtraDiagnostics() {
        val state by telemetryModel.state.collectAsStateWithLifecycle()
        TelemetryPanel(state, telemetryModel::enable, telemetryModel::pause, telemetryModel::scan, telemetryModel::checkRegistration)
    }
    override fun onResume() { super.onResume(); telemetryModel.checkRegistration() }
}
@Composable private fun TelemetryPanel(state: TelemetryScreenState, enable: () -> Unit, pause: () -> Unit, scan: () -> Unit, registration: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("READ-ONLY EXPERIMENT", style = MaterialTheme.typography.titleLarge)
            Text("No organizing, moving, copying or deleting. Android 11+ only. Readiness evidence never proves writer closure or authorizes a move.")
            Text("Observation enabled: ${state.enabled}; access: ${state.granted}; registered public snapshot: ${state.registered}")
            Text("This separate telemetry package has its own journal, permission, standby bucket and scheduler. Results cannot establish identical main-app behavior.")
            if (state.busy) CircularProgressIndicator()
            state.message?.let { Text(it) }
            Button(enable, enabled = !state.busy && TelemetryGate.supported() && state.granted && !state.enabled) { Text("Enable read-only observation") }
            Button(pause, enabled = !state.busy && state.enabled) { Text("Pause observation") }
            Button(scan, enabled = !state.busy && state.enabled && state.granted) { Text("Run read-only scan") }
            Button(registration, enabled = !state.busy) { Text("Check trigger registration") }
            Button(onClick = {
                val clip = ClipData.newPlainText("Redacted S26 read-only telemetry", state.report)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            }) { Text("Copy diagnostics as Markdown") }
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/markdown"; putExtra(Intent.EXTRA_TEXT, state.report) }, "Share redacted diagnostics")) }) { Text("Share redacted diagnostics") }
            Text(state.report, style = MaterialTheme.typography.bodySmall)
        }
    }
}

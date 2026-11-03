package com.kroflafps.s26downloadorganizer.telemetry

import com.kroflafps.s26downloadorganizer.readiness.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Scanner exposes metadata only; no content-read or file-mutation method. */
interface MetadataPages {
    fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage
}
data class MetadataPage(val entries: List<MetadataEvidence>, val children: List<DirectoryTask>, val next: DirectoryTask?, val warnings: Int)
class TelemetryCoordinator(
    private val dao: TelemetryDao, private val scanner: MetadataPages,
    private val permission: () -> Boolean, private val enabled: () -> Boolean,
    private val boot: () -> String, private val wall: () -> Long, private val elapsed: () -> Long,
    private val mutex: Mutex = Mutex(),
    private val policy: () -> String = { "" },
) {
    suspend fun run(source: String, signal: Boolean = true, uriCount: Int = 0, hint: String = "SIGNAL", wakeAt: Long = wall()): Boolean = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext false
        if (signal) dao.request(source, wakeAt, uriCount, hint, policy())
        if (!permission()) { dao.log(TelemetryEvent(at = wall(), source = source, code = "PERMISSION_OR_STORAGE_UNAVAILABLE")); return@withContext false }
        if (source == "CONTENT" && hint == "IRRELEVANT_URI_HINT_SCAN_STILL_REQUIRED") {
            dao.log(TelemetryEvent(at = wall(), source = source, code = "IRRELEVANT_HINT_DURABLE_DEFERRED"))
            return@withContext true // Dirty generation stays durable; the delayed worker must still inspect subfolders.
        }
        if (!mutex.tryLock()) return@withContext true
        val owner = UUID.randomUUID().toString(); val bootId = boot(); val start = elapsed()
        try {
            if (!dao.acquire(owner, bootId, start)) return@withContext true
            val task = dao.nextDirectory() ?: return@withContext false
            val context = currentCoroutineContext()
            fun check() { context.ensureActive(); check(enabled() && permission()) { "Storage read not authorized" } }
            check()
            val page = try { scanner.scan(task, ::check) } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                check() // Permission loss preserves the checkpoint; unreadable directories do not starve siblings.
                dao.commit(owner, bootId, elapsed(), task, null, emptyList(), emptyList(),
                    TelemetryEvent(at = wall(), source = source, code = "METADATA_PAGE_UNREADABLE"), 1)
                return@withContext dao.pending()
            }
            check()
            val previous = dao.observations().associateBy { it.value.key }
            val now = wall()
            val rows = page.entries.map { ObservationRow(ReadinessPolicy.observe(now, bootId, it, previous[it.key]?.value, elapsed())) }
                .filter { it != previous[it.value.key] }
            val newest = page.entries.mapNotNull { it.modified }.maxOrNull()
            val age = newest?.takeIf { it <= wakeAt }?.let { wakeAt - it }
            val event = TelemetryEvent(at = now, source = source, code = "METADATA_PAGE", entries = page.entries.size,
                durationMs = (elapsed() - start).coerceAtLeast(0), roughMtimeAge = age)
            val committed = dao.commit(owner, bootId, elapsed(), task, page.next, page.children, rows, event, page.warnings)
            if (!committed) dao.log(TelemetryEvent(at = wall(), source = source, code = "STALE_LEASE_DISCARDED"))
            dao.pending()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.log(TelemetryEvent(at = wall(), source = source, code = "INTERRUPTED_PENDING_RECONCILIATION")) }
            throw cancelled
        } catch (_: Exception) {
            dao.log(TelemetryEvent(at = wall(), source = source, code = "SCAN_FAILED_PENDING_RECONCILIATION")); true
        } finally {
            try { withContext(NonCancellable) { dao.release(owner) } } finally { mutex.unlock() }
        }
    }
}

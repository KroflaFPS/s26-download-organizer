package com.kroflafps.s26downloadorganizer.automation

import com.kroflafps.s26downloadorganizer.domain.EntryType
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.history.CandidateStore
import com.kroflafps.s26downloadorganizer.history.ObservedCandidate
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy

data class ReadinessEvaluation(
    val ready: List<InventoryEntry>,
    val settlingCount: Int,
)

class DownloadReadinessTracker(
    private val store: CandidateStore,
    private val settleDelayMillis: Long = 15_000L,
    private val preexistingQuietMillis: Long = 60_000L,
    private val zeroByteGraceMillis: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun evaluate(entries: List<InventoryEntry>): ReadinessEvaluation {
        val now = clock()
        val eligible = entries.filter {
            it.type == EntryType.FILE &&
                it.sizeBytes != null &&
                it.modifiedAtMillis != null &&
                OperationPolicy.eligibleName(it.name)
        }
        val activePaths = eligible.mapTo(HashSet()) { it.id }
        for (stale in store.all()) {
            if (stale.path !in activePaths) {
                store.delete(stale.path)
            }
        }

        val ready = mutableListOf<InventoryEntry>()
        var settlingCount = 0
        for (entry in eligible) {
            val size = requireNotNull(entry.sizeBytes)
            val mtime = requireNotNull(entry.modifiedAtMillis)
            val existing = store.get(entry.id)
            val requiredWait = if (size == 0L) maxOf(settleDelayMillis, zeroByteGraceMillis) else settleDelayMillis

            if (existing == null) {
                if (size > 0L && now - mtime >= preexistingQuietMillis) {
                    ready += entry
                } else {
                    store.upsert(
                        ObservedCandidate(
                            path = entry.id,
                            sizeBytes = size,
                            modifiedAtMillis = mtime,
                            firstStableAtMillis = now,
                            lastSeenAtMillis = now,
                        ),
                    )
                    settlingCount++
                }
            } else if (existing.sizeBytes != size || existing.modifiedAtMillis != mtime) {
                store.upsert(
                    ObservedCandidate(
                        path = entry.id,
                        sizeBytes = size,
                        modifiedAtMillis = mtime,
                        firstStableAtMillis = now,
                        lastSeenAtMillis = now,
                    ),
                )
                settlingCount++
            } else {
                store.upsert(existing.copy(lastSeenAtMillis = now))
                if (now - existing.firstStableAtMillis >= requiredWait) {
                    ready += entry
                } else {
                    settlingCount++
                }
            }
        }
        return ReadinessEvaluation(ready, settlingCount)
    }

    suspend fun clear(path: String) {
        store.delete(path)
    }
}

package com.kroflafps.s26downloadorganizer.readiness

/** Evidence labels, NEVER permission to move/delete. No content/classifier dependency. */
enum class Readiness { UNKNOWN, NOT_READY, POSSIBLY_READY, READY_WITH_EVIDENCE }
enum class ReadinessReason { MISSING_METADATA, TEMPORARY_NAME, PROVIDER_PENDING, CHANGED, OBSERVING, QUIET, PUBLISHED_AND_QUIET, CLOCK_UNCERTAIN, METADATA_ONLY }
data class MetadataEvidence(val key: String, val identity: String?, val size: Long?, val modified: Long?, val temporary: Boolean, val pending: Boolean?)
data class ReadinessObservation(
    val key: String, val identity: String?, val size: Long?, val modified: Long?,
    val boot: String, val sampledAt: Long, val quietSince: Long, val samples: Int,
    val state: Readiness, val reason: ReadinessReason, val nextCheck: Long,
    val sampledElapsed: Long = sampledAt, val quietElapsed: Long = quietSince,
) { val authorizesMutation: Boolean get() = false }
object ReadinessPolicy {
    const val MIN_SAMPLE_GAP = 15_000L
    const val QUIET_WINDOW = 30_000L
    fun temporary(name: String): Boolean = name.lowercase(java.util.Locale.ROOT).let { n ->
        listOf(".part", ".partial", ".crdownload", ".download", ".tmp", ".temp", ".opdownload", ".filepart").any(n::endsWith) || n.startsWith(".pending-")
    }
    fun observe(now: Long, boot: String, current: MetadataEvidence, previous: ReadinessObservation?, elapsedNow: Long = now): ReadinessObservation {
        val comparable = previous?.takeIf { it.boot == boot && now >= it.sampledAt && elapsedNow >= it.sampledElapsed }
        val valid = current.identity?.isNotBlank() == true && current.size != null && current.size >= 0 && current.modified != null && current.modified >= 0
        val changed = comparable != null && (comparable.reason == ReadinessReason.PROVIDER_PENDING && current.pending != true || current.identity != comparable.identity || current.size != comparable.size || current.modified != comparable.modified)
        val accepted = changed || comparable == null || elapsedNow - comparable.sampledElapsed >= MIN_SAMPLE_GAP
        val sample = if (changed || comparable == null) 1 else if (elapsedNow - comparable.sampledElapsed >= MIN_SAMPLE_GAP) minOf(8, comparable.samples + 1) else comparable.samples
        val sampledAt = if (comparable != null && !accepted) comparable.sampledAt else now
        val since = if (changed || comparable == null) now else comparable.quietSince
        val sinceElapsed = if (changed || comparable == null) elapsedNow else comparable.quietElapsed
        val sampleElapsed = if (comparable != null && !accepted) comparable.sampledElapsed else elapsedNow
        val pair = when {
            current.temporary -> Readiness.NOT_READY to ReadinessReason.TEMPORARY_NAME
            current.pending == true -> Readiness.NOT_READY to ReadinessReason.PROVIDER_PENDING
            previous != null && previous.boot == boot && now < previous.sampledAt -> Readiness.UNKNOWN to ReadinessReason.CLOCK_UNCERTAIN
            !valid -> Readiness.UNKNOWN to ReadinessReason.MISSING_METADATA
            current.modified!! > now + 300_000 -> Readiness.UNKNOWN to ReadinessReason.CLOCK_UNCERTAIN
            changed -> Readiness.NOT_READY to ReadinessReason.CHANGED
            sample < 3 || elapsedNow - sinceElapsed < QUIET_WINDOW -> Readiness.UNKNOWN to ReadinessReason.OBSERVING
            current.pending == false -> Readiness.READY_WITH_EVIDENCE to ReadinessReason.PUBLISHED_AND_QUIET
            else -> Readiness.POSSIBLY_READY to ReadinessReason.QUIET
        }
        if (comparable != null && !accepted && pair.first == comparable.state && pair.second == comparable.reason) return comparable
        val delay = when { sample < 2 -> MIN_SAMPLE_GAP; sample < 4 -> 45_000L; sample < 6 -> 120_000L; else -> 600_000L }
        return ReadinessObservation(current.key, current.identity, current.size, current.modified, boot, sampledAt, since, sample, pair.first, pair.second, now + delay, sampleElapsed, sinceElapsed)
    }
}

package com.kroflafps.s26downloadorganizer.operations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** Evidence-only reconciliation. NEVER creates, publishes, deletes, retries or undoes files. */
class OperationRecovery(
    private val files: OperationFiles,
    private val journal: OperationJournal,
    private val permission: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val operationMutex: Mutex = Mutex(),
) {
    suspend fun reconcile(): RecoveryReport = withContext(Dispatchers.IO) {
        if (!operationMutex.tryLock()) return@withContext RecoveryReport(0, true, "An operation is still stopping or running. Wait before checking recovery.")
        try {
            if (!permission()) return@withContext RecoveryReport(0, true, "Storage access is required; journal retained.")
            val records = journal.incomplete(20)
            var remainingBytes = 64L * 1024 * 1024
            var examined = 0
            for (record in records) {
                if (!permission()) return@withContext RecoveryReport(examined, true, "Storage access changed; recovery deferred.")
                // Bound launch/recovery work. Large files require a later explicit audited recovery path.
                val multiplier = 3L
                if (record.sourceSize > remainingBytes / multiplier) continue
                remainingBytes -= record.sourceSize * multiplier
                val source = files.observe(record.sourcePath, record.sourceSize)
                val stage = files.observe(record.stagingPath, record.sourceSize)
                val destination = record.destinationPath?.let { files.observe(it, record.sourceSize) } ?: Observation.Missing
                val next = classify(record, source, stage, destination)
                examined++
                if (next != record) journal.save(next.copy(revision = record.revision + 1, updatedAt = maxOf(clock(), record.updatedAt)))
            }
            RecoveryReport(examined, journal.incomplete(1).isNotEmpty(), "Recovery checks evidence only. Unresolved files are preserved; no automatic retry or cleanup.")
        } finally { operationMutex.unlock() }
    }

    internal fun classify(record: OperationRecord, source: Observation, stage: Observation, destination: Observation): OperationRecord {
        fun unresolved(error: OperationError) = record.copy(phase = Phase.RECOVERY_REQUIRED, error = error, recoveryNeeded = true)
        if (listOf(source, stage, destination).any { it is Observation.Unverifiable || it is Observation.Present && !it.trusted }) {
            return unresolved(OperationError.UNTRUSTED_EVIDENCE)
        }
        val sourcePresent = source as? Observation.Present
        val destinationPresent = destination as? Observation.Present
        val stagePresent = stage as? Observation.Present
        if (stagePresent != null && record.stagingIdentity == null) return unresolved(OperationError.UNTRUSTED_EVIDENCE)
        if (stagePresent != null && stagePresent.evidence.identity != record.stagingIdentity) return unresolved(OperationError.CONFLICT)
        if (stagePresent != null && record.destinationVerified &&
            (stagePresent.evidence.hash != record.contentHash || stagePresent.evidence.size != record.sourceSize)) return unresolved(OperationError.CONFLICT)
        val sourceMatches = sourcePresent?.evidence?.let {
            it.identity == record.sourceIdentity && it.hash == record.contentHash && it.size == record.sourceSize && it.stamp == record.sourceStamp
        } == true
        val destinationMatches = destinationPresent?.evidence?.let {
            it.identity == record.destinationIdentity && it.hash == record.contentHash && it.size == record.sourceSize && it.stamp == record.destinationStamp
        } == true
        if (sourcePresent != null && !sourceMatches || destinationPresent != null && !destinationMatches) return unresolved(OperationError.CONFLICT)
        if (destinationMatches && sourceMatches) {
            if (record.sourceRemoved) return unresolved(OperationError.CONFLICT)
            return record.copy(phase = Phase.COPY_ONLY, error = OperationError.NO_SAFE_REMOVAL, recoveryNeeded = true)
        }
        if (source is Observation.Missing && destinationMatches && record.removalAuthorized && record.sourceRemovalSynced && record.destinationSynced &&
            record.destinationPublished && record.destinationVerified) {
            return record.copy(phase = Phase.COMPLETED, sourceRemoved = true, removalEvidence = "absent-after-recorded-removal-intent",
                completedAt = record.completedAt ?: maxOf(clock(), record.updatedAt), error = null, recoveryNeeded = false)
        }
        if (sourceMatches && destination is Observation.Missing && stage is Observation.Missing) {
            return record.copy(phase = Phase.FAILED_SAFE, sourceToken = null, error = OperationError.IO, recoveryNeeded = false)
        }
        if (source is Observation.Missing && destination is Observation.Missing && stage is Observation.Missing) return unresolved(OperationError.MISSING_BOTH)
        return unresolved(OperationError.PARTIAL_OUTPUT)
    }
}

data class RecoveryReport(val examined: Int, val needsAttention: Boolean, val message: String)

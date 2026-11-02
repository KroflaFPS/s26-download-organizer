package com.kroflafps.s26downloadorganizer.operations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

/** Evidence-only reconciliation. NEVER retries, mutates files or infers app-controlled removal. */
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
            val page = journal.nextRecoveryPage(20)
            var remainingBytes = 64L * 1024 * 1024
            var examined = 0
            var deferred = 0
            var journalFailures = 0
            val context = currentCoroutineContext()
            fun check() {
                context.ensureActive()
                if (!permission()) throw OperationFailure(OperationError.PERMISSION)
            }
            fun observe(path: String, size: Long): Observation = try {
                check()
                files.observe(path, size, ::check).also { check() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: OperationFailure) {
                if (failure.category == OperationError.PERMISSION) throw failure
                Observation.Unverifiable
            } catch (_: Exception) { Observation.Unverifiable }
            for (record in page.records) {
                check()
                try { record.requireConsistentEvidence() } catch (_: IllegalArgumentException) {
                    journalFailures++
                    journal.advanceRecoveryCursor(record.id, requireNotNull(page.ceilingId))
                    continue
                }
                val next = if (record.sourceSize > remainingBytes / 3L) {
                    deferred++
                    record.copy(phase = Phase.RECOVERY_REQUIRED, error = OperationError.RECOVERY_BUDGET, recoveryNeeded = true)
                } else {
                    remainingBytes -= record.sourceSize * 3L
                    val source = observe(record.sourcePath, record.sourceSize)
                    val stage = observe(record.stagingPath, record.sourceSize)
                    val destination = record.destinationPath?.let { observe(it, record.sourceSize) } ?: Observation.Missing
                    examined++
                    classify(record, source, stage, destination)
                }
                if (next != record) try {
                    journal.save(next.copy(revision = record.revision + 1, updatedAt = maxOf(clock(), record.updatedAt)))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    // A row conflict/failing event must not monopolize the queue. Never retry its mutation.
                    journalFailures++
                }
                // Includes oversized and unchanged/unverifiable records. Cursor is independent of evidence timestamps.
                journal.advanceRecoveryCursor(record.id, requireNotNull(page.ceilingId))
            }
            RecoveryReport(examined, journal.incomplete(1).isNotEmpty(),
                "Checked $examined records; $deferred deferred by the verification limit; $journalFailures journal updates need review. Further checks continue fairly. Large files remain visible for manual review. No files were changed or removals inferred.", deferred, journalFailures)
        } catch (failure: OperationFailure) {
            if (failure.category != OperationError.PERMISSION) throw failure
            RecoveryReport(0, true, "Storage access changed; recovery deferred and files preserved.")
        } finally { operationMutex.unlock() }
    }

    internal fun classify(record: OperationRecord, source: Observation, stage: Observation, destination: Observation): OperationRecord {
        fun unresolved(error: OperationError) = record.copy(phase = Phase.RECOVERY_REQUIRED, error = error, recoveryNeeded = true, completedAt = null)
        val observations = listOf(source to record.sourcePath, stage to record.stagingPath, destination to record.destinationPath)
        if (observations.any { (observation, path) -> observation is Observation.Unverifiable || observation is Observation.Present &&
                (!observation.trusted || observation.evidence.path != path || observation.evidence.identity.isBlank()) }) {
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
        // An absent name cannot identify who removed/renamed it. Historical booleans are not a removal receipt.
        // Even SOURCE_REMOVED + sync flags must not promote an interrupted operation to COMPLETED.
        if (source is Observation.Missing && destinationMatches) return unresolved(OperationError.REMOVAL_UNCONFIRMED)
        if (sourceMatches && destination is Observation.Missing && stage is Observation.Missing) {
            return record.copy(phase = Phase.FAILED_SAFE, sourceToken = null, error = OperationError.IO, recoveryNeeded = false)
        }
        if (source is Observation.Missing && destination is Observation.Missing && stage is Observation.Missing) return unresolved(OperationError.MISSING_BOTH)
        return unresolved(OperationError.PARTIAL_OUTPUT)
    }
}

data class RecoveryReport(val examined: Int, val needsAttention: Boolean, val message: String, val deferred: Int = 0, val journalFailures: Int = 0)

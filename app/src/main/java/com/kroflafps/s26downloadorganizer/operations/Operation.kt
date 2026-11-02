package com.kroflafps.s26downloadorganizer.operations

import java.nio.file.Path

// No Android or Room types in the coordinator's model/ports.
enum class OperationType { MOVE, UNDO }
enum class Phase {
    PREPARED, ALLOCATING, COPYING, COPY_VERIFIED, DESTINATION_COMMITTED,
    SOURCE_REMOVAL_PENDING, SOURCE_REMOVED, COMPLETED, COPY_ONLY, FAILED_SAFE, RECOVERY_REQUIRED,
}
enum class OperationError {
    INVALID_REQUEST, PERMISSION, STORAGE_UNAVAILABLE, SOURCE_CHANGED, CONFLICT,
    IO, JOURNAL, NO_SAFE_REMOVAL, DURABILITY_UNCONFIRMED, UNTRUSTED_EVIDENCE,
    MISSING_BOTH, PARTIAL_OUTPUT, CANCELLED,
}

data class OperationRecord(
    val id: String,
    val type: OperationType,
    val relatedId: String?,
    val sourcePath: String,
    val destinationDirectory: String,
    val originalName: String,
    val sourceIdentity: String,
    val sourceSize: Long,
    val sourceStamp: String,
    val contentHash: String,
    val sourceToken: String?,
    val stagingPath: String,
    val destinationPath: String? = null,
    val stagingIdentity: String? = null,
    val destinationIdentity: String? = null,
    val destinationStamp: String? = null,
    val bytesCopied: Long = 0,
    val destinationVerified: Boolean = false,
    val destinationPublished: Boolean = false,
    val destinationSynced: Boolean = false,
    val removalAuthorized: Boolean = false,
    val sourceRemoved: Boolean = false,
    val sourceRemovalSynced: Boolean = false,
    val removalEvidence: String? = null,
    val phase: Phase = Phase.PREPARED,
    val error: OperationError? = null,
    val recoveryNeeded: Boolean = false,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val revision: Int = 0,
) {
    val undoEligible: Boolean get() = type == OperationType.MOVE && phase == Phase.COMPLETED &&
        destinationVerified && destinationPublished && destinationSynced && sourceRemoved && sourceRemovalSynced && !recoveryNeeded
}

data class OperationHistoryEntry(val record: OperationRecord, val undoRequested: Boolean) {
    val canUndo get() = record.undoEligible && !undoRequested
}

data class FileEvidence(val path: String, val identity: String, val size: Long, val stamp: String, val hash: String)

sealed interface Observation {
    data object Missing : Observation
    data class Present(val evidence: FileEvidence, val trusted: Boolean = true) : Observation
    data object Unverifiable : Observation
}

enum class Removal { REMOVED, UNSUPPORTED, CONFLICT }

data class OperationRoots(val downloads: Path, val other: Path)

/** An adapter must independently enforce approved roots, no-follow I/O and no-clobber writes.
 * Conditional removal MUST be atomic against replacement, not just stat-then-unlink.
 * The Android adapter deliberately supplies no mutation capability in Build 3.
 */
interface OperationFiles {
    val roots: OperationRoots
    val canConditionallyRemove: Boolean
    fun isOccupied(path: String): Boolean
    fun requireWritableAccess()
    fun prepare(path: String, type: OperationType): FileEvidence
    fun ensureDestination(type: OperationType)
    fun reserveStage(path: String): String
    fun copyAndSync(source: FileEvidence, stage: String, stageIdentity: String, checkpoint: () -> Unit): Long
    fun verify(path: String, identity: String): FileEvidence
    fun publishNoClobber(stage: String, stageIdentity: String, destination: String): String
    fun syncDirectory(path: String): Boolean
    fun removeConditionally(source: FileEvidence, destination: FileEvidence): Removal
    fun observe(path: String, maximumBytes: Long): Observation
}

interface OperationJournal {
    suspend fun get(id: String): OperationRecord?
    suspend fun undoFor(id: String): OperationRecord?
    suspend fun create(record: OperationRecord): Boolean
    /** Compare-and-set revision and append a phase event in one short DB transaction. */
    suspend fun save(record: OperationRecord)
    suspend fun incomplete(limit: Int): List<OperationRecord>
}

sealed interface OperationResult {
    data class Recorded(val record: OperationRecord) : OperationResult
    data class Blocked(val reason: String) : OperationResult
    data class Rejected(val error: OperationError) : OperationResult
    data class JournalUnavailable(val operationId: String?) : OperationResult
}

// Termination tests throw an Error at these boundaries, escaping normal failure handling.
enum class Checkpoint {
    INTENT_SAVED, DIRECTORY_READY, STAGE_RESERVED, COPYING_SAVED, COPY_CHUNK,
    COPY_FINISHED, COPY_VERIFIED, PUBLISHED, COMMIT_SAVED, REMOVAL_INTENT_SAVED,
    BEFORE_REMOVAL, AFTER_REMOVAL, SOURCE_REMOVED_SAVED, BEFORE_COMPLETION, COMPLETED,
}

class OperationFailure(val category: OperationError) : java.io.IOException(category.name)

internal fun OperationRecord.requireConsistentEvidence() {
    require(sourceSize >= 0 && bytesCopied in 0..sourceSize && revision >= 0 && updatedAt >= startedAt)
    require(!destinationVerified || bytesCopied == sourceSize)
    require(!destinationPublished || destinationVerified && destinationPath != null && destinationIdentity != null && destinationStamp != null)
    require(!destinationSynced || destinationPublished)
    require(!removalAuthorized || destinationVerified && destinationPublished && destinationSynced)
    require(!sourceRemoved || removalAuthorized && removalEvidence != null)
    require(!sourceRemovalSynced || sourceRemoved)
    if (phase == Phase.COMPLETED) require(destinationVerified && destinationPublished && destinationSynced && sourceRemoved && sourceRemovalSynced && completedAt != null && !recoveryNeeded)
    if (phase == Phase.COPY_ONLY) require(destinationVerified && destinationPublished && !sourceRemoved && recoveryNeeded)
}

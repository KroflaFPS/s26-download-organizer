package com.kroflafps.s26downloadorganizer.operations

import com.kroflafps.s26downloadorganizer.domain.FileCategory
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
    REMOVAL_UNCONFIRMED, RECOVERY_BUDGET,
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
        destinationVerified && destinationPublished && destinationSynced && sourceRemoved && sourceRemovalSynced &&
        removalEvidence == "adapter-confirmed" && !recoveryNeeded
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

data class OperationRoots(val downloads: Path, val organized: Path) {
    val other: Path get() = categoryDirectory(FileCategory.OTHER)
    fun categoryDirectory(category: FileCategory): Path = organized.resolve(category.folderName)
    fun isOrganizedCategoryFile(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        val org = organized.toAbsolutePath().normalize()
        val parent = normalized.parent ?: return false
        if (parent.parent != org) return false
        val categoryName = parent.fileName?.toString().orEmpty()
        val fileName = normalized.fileName?.toString().orEmpty()
        return categoryName.isNotEmpty() && categoryName !in listOf(".", "..") && !categoryName.startsWith(".") &&
            fileName.isNotEmpty() && fileName !in listOf(".", "..")
    }
}

/** An adapter must independently enforce approved roots, no-follow I/O and no-clobber writes. */
interface OperationFiles {
    val roots: OperationRoots
    val canConditionallyRemove: Boolean
    fun isOccupied(path: String): Boolean
    fun requireWritableAccess()
    fun prepare(path: String, type: OperationType): FileEvidence
    fun ensureDestination(targetDirectory: Path)
    fun reserveStage(path: String): String
    fun copyAndSync(source: FileEvidence, stage: String, stageIdentity: String, checkpoint: () -> Unit): Long
    fun verify(path: String, identity: String): FileEvidence
    fun publishNoClobber(stage: String, stageIdentity: String, destination: String): String
    fun syncDirectory(path: String): Boolean
    fun removeConditionally(source: FileEvidence, destination: FileEvidence): Removal
    fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit = {}): Observation
    fun cleanupStage(stage: String, stageIdentity: String) {}
}

interface OperationJournal {
    suspend fun get(id: String): OperationRecord?
    suspend fun undoFor(id: String): OperationRecord?
    suspend fun create(record: OperationRecord): Boolean
    /** Compare-and-set revision and append a phase event in one short DB transaction. */
    suspend fun save(record: OperationRecord)
    suspend fun incomplete(limit: Int): List<OperationRecord>
    suspend fun nextRecoveryPage(limit: Int): RecoveryPage
    suspend fun advanceRecoveryCursor(lastId: String, ceilingId: String)
}

data class RecoveryPage(val records: List<OperationRecord>, val ceilingId: String?)

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
    require(java.util.UUID.fromString(id).toString() == id)
    require((type == OperationType.MOVE) == (relatedId == null))
    require(relatedId == null || relatedId != id && java.util.UUID.fromString(relatedId).toString() == relatedId)
    require(sourceIdentity.isNotBlank() && sourceStamp.isNotBlank() && contentHash.matches(Regex("[a-f0-9]{64}")))
    require(sourceSize >= 0 && bytesCopied in 0..sourceSize && revision >= 0 && updatedAt >= startedAt)
    require(!destinationVerified || bytesCopied == sourceSize)
    require(!destinationPublished || destinationVerified && destinationPath != null && destinationIdentity != null && destinationStamp != null)
    require(!destinationSynced || destinationPublished)
    require(!removalAuthorized || destinationVerified && destinationPublished && destinationSynced)
    require(!sourceRemoved || removalAuthorized && removalEvidence != null)
    require(!sourceRemovalSynced || sourceRemoved)
    if (phase == Phase.COMPLETED) require(destinationVerified && destinationPublished && destinationSynced && sourceRemoved && sourceRemovalSynced && completedAt != null && !recoveryNeeded && removalEvidence == "adapter-confirmed")
    if (phase == Phase.COPY_ONLY) require(destinationVerified && destinationPublished && !sourceRemoved && recoveryNeeded)
    if (phase == Phase.RECOVERY_REQUIRED) require(recoveryNeeded)
    if (phase == Phase.SOURCE_REMOVAL_PENDING) require(removalAuthorized)
    if (phase == Phase.SOURCE_REMOVED) require(sourceRemoved && sourceRemovalSynced)
    require(completedAt == null || phase == Phase.COMPLETED && completedAt in startedAt..updatedAt)
}

internal fun OperationRecord.requireNewIntent() {
    requireConsistentEvidence()
    require(phase == Phase.PREPARED && revision == 0 && updatedAt == startedAt && error == null && !recoveryNeeded)
    require(!sourceToken.isNullOrBlank() && destinationPath == null && stagingIdentity == null && destinationIdentity == null &&
        destinationStamp == null && bytesCopied == 0L && !destinationVerified && !destinationPublished && !destinationSynced &&
        !removalAuthorized && !sourceRemoved && !sourceRemovalSynced && removalEvidence == null && completedAt == null)
}

internal fun OperationRecord.requireSuccessorOf(previous: OperationRecord) {
    requireConsistentEvidence()
    require(previous.revision < Int.MAX_VALUE && revision == previous.revision + 1) { "Journal revision conflict" }
    require(type == previous.type && relatedId == previous.relatedId && sourcePath == previous.sourcePath &&
        destinationDirectory == previous.destinationDirectory && originalName == previous.originalName &&
        sourceIdentity == previous.sourceIdentity && sourceSize == previous.sourceSize && sourceStamp == previous.sourceStamp &&
        contentHash == previous.contentHash && stagingPath == previous.stagingPath && startedAt == previous.startedAt && updatedAt >= previous.updatedAt)
    require(legalTransition(previous.phase, phase) || previous.phase !in setOf(Phase.COMPLETED, Phase.FAILED_SAFE) && phase == Phase.COPY_ONLY)
    require(sourceToken == previous.sourceToken || phase == Phase.FAILED_SAFE && sourceToken == null)
    require(previous.stagingIdentity == null || stagingIdentity == previous.stagingIdentity)
    require(previous.destinationIdentity == null || destinationIdentity == previous.destinationIdentity && destinationStamp == previous.destinationStamp)
    require(destinationPath == previous.destinationPath || previous.phase == Phase.COPY_VERIFIED && phase == Phase.COPY_VERIFIED)
    if (previous.stagingIdentity == null && stagingIdentity != null) require(previous.phase == Phase.ALLOCATING && phase == Phase.COPYING)
    if (previous.destinationIdentity == null && destinationIdentity != null) require(previous.phase == Phase.COPY_VERIFIED && phase == Phase.DESTINATION_COMMITTED && destinationIdentity.isNotBlank())
    if (!previous.destinationVerified && destinationVerified) require(previous.phase == Phase.COPYING && phase == Phase.COPY_VERIFIED)
    if (!previous.destinationPublished && destinationPublished) require(previous.phase == Phase.COPY_VERIFIED && phase == Phase.DESTINATION_COMMITTED)
    if (!previous.destinationSynced && destinationSynced) require(previous.phase == Phase.COPY_VERIFIED && phase == Phase.DESTINATION_COMMITTED)
    if (!previous.removalAuthorized && removalAuthorized) require(previous.phase == Phase.DESTINATION_COMMITTED && phase == Phase.SOURCE_REMOVAL_PENDING)
    if (!previous.sourceRemoved && sourceRemoved) require(previous.phase == Phase.SOURCE_REMOVAL_PENDING && phase in setOf(Phase.SOURCE_REMOVED, Phase.RECOVERY_REQUIRED))
    if (!previous.sourceRemovalSynced && sourceRemovalSynced) require(previous.phase == Phase.SOURCE_REMOVAL_PENDING && phase in setOf(Phase.SOURCE_REMOVED, Phase.RECOVERY_REQUIRED))
    if (bytesCopied != previous.bytesCopied) require(previous.phase == Phase.COPYING && phase == Phase.COPY_VERIFIED)
    require(removalEvidence == previous.removalEvidence || previous.removalEvidence == null &&
        previous.phase == Phase.SOURCE_REMOVAL_PENDING && phase in setOf(Phase.SOURCE_REMOVED, Phase.RECOVERY_REQUIRED) &&
        sourceRemoved && removalEvidence == "adapter-confirmed")
    require(bytesCopied >= previous.bytesCopied)
    require(!previous.destinationVerified || destinationVerified)
    require(!previous.destinationPublished || destinationPublished)
    require(!previous.destinationSynced || destinationSynced)
    require(!previous.removalAuthorized || removalAuthorized)
    require(!previous.sourceRemoved || sourceRemoved)
    require(!previous.sourceRemovalSynced || sourceRemovalSynced)
    // Recovery must not manufacture completion from a path observation or stale flags.
    if (phase == Phase.COMPLETED) require(previous.phase == Phase.SOURCE_REMOVED && previous.sourceRemoved &&
        previous.sourceRemovalSynced && previous.removalEvidence == "adapter-confirmed")
}

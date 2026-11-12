package com.kroflafps.s26downloadorganizer.operations

import com.kroflafps.s26downloadorganizer.domain.FileCategory
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** One coordinator per application. DB uniqueness also protects against other coordinator instances. */
class OperationCoordinator(
    private val files: OperationFiles,
    private val journal: OperationJournal,
    private val authorized: () -> Boolean = { OperationPolicy.MUTATIONS_ENABLED },
    private val clock: () -> Long = System::currentTimeMillis,
    private val checkpoint: (Checkpoint) -> Unit = {},
    private val operationMutex: Mutex = Mutex(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun move(
        id: String,
        source: String,
        confirmedFinished: Boolean,
        targetCategory: FileCategory = FileCategory.OTHER,
    ): OperationResult =
        exclusive {
            if (!confirmedFinished) return@exclusive OperationResult.Rejected(OperationError.INVALID_REQUEST)
            execute(id, source, OperationType.MOVE, null, null, true, targetCategory)
        }

    suspend fun undo(id: String, originalId: String, acceptUniqueName: Boolean): OperationResult = exclusive {
        journal.get(id)?.let { existing ->
            return@exclusive if (existing.type == OperationType.UNDO && existing.relatedId == originalId) OperationResult.Recorded(existing)
            else OperationResult.Rejected(OperationError.CONFLICT)
        }
        val original = journal.get(originalId)
        if (original?.undoEligible != true || journal.undoFor(originalId) != null) {
            return@exclusive OperationResult.Rejected(OperationError.CONFLICT)
        }
        execute(id, requireNotNull(original.destinationPath), OperationType.UNDO, original, original.originalName, acceptUniqueName, null)
    }

    private suspend fun exclusive(block: suspend () -> OperationResult): OperationResult {
        if (!authorized()) return OperationResult.Blocked(OperationPolicy.GATE_REASON)
        if (!operationMutex.tryLock()) return OperationResult.Blocked("Another operation is running.")
        return try {
            withContext(ioDispatcher) { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OperationFailure) {
            OperationResult.Rejected(failure.category)
        } catch (_: Exception) {
            // Includes journal read failures before intent. No filesystem writes occurred there.
            OperationResult.JournalUnavailable(null)
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun execute(
        id: String,
        sourcePath: String,
        type: OperationType,
        original: OperationRecord?,
        restoreName: String?,
        acceptUnique: Boolean,
        targetCategory: FileCategory?,
    ): OperationResult {
        var current: OperationRecord? = null
        var stored = false
        var mutationAttempted = false
        var journalCall = false
        var removalPerformed = false
        var removalSynced = false
        val context = currentCoroutineContext()
        fun check() {
            context.ensureActive()
            if (!authorized()) throw OperationFailure(OperationError.PERMISSION)
            files.requireWritableAccess()
        }
        suspend fun persist(next: OperationRecord) {
            val previous = requireNotNull(current)
            require(legalTransition(previous.phase, next.phase))
            val saved = next.copy(revision = previous.revision + 1, updatedAt = maxOf(clock(), previous.updatedAt))
            journalCall = true
            journal.save(saved)
            journalCall = false
            current = saved
        }
        try {
            require(UUID.fromString(id).toString() == id)
            journalCall = true
            val previous = journal.get(id)
            journalCall = false
            if (previous != null) {
                return if (previous.sourcePath == sourcePath && previous.type == type) OperationResult.Recorded(previous)
                else OperationResult.Rejected(OperationError.CONFLICT)
            }
            check()
            val destinationDir = if (type == OperationType.MOVE) {
                files.roots.categoryDirectory(targetCategory ?: FileCategory.OTHER)
            } else {
                files.roots.downloads
            }
            val path = if (type == OperationType.MOVE) {
                OperationPolicy.sourcePath(sourcePath, files.roots.downloads)
            } else {
                OperationPolicy.undoSourcePath(sourcePath, files.roots.organized)
            }
            val source = files.prepare(path.toString(), type)
            if (source.path != path.toString() || source.identity.isBlank() || source.stamp.isBlank() || source.size < 0 || !source.hash.matches(Regex("[a-f0-9]{64}"))) {
                throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
            }
            if (original != null && (source.identity != original.destinationIdentity || source.hash != original.contentHash ||
                    source.size != original.sourceSize || source.stamp != original.destinationStamp)) {
                throw OperationFailure(OperationError.SOURCE_CHANGED)
            }
            val targetDirectory = destinationDir.toAbsolutePath().normalize()
            val name = restoreName ?: path.fileName.toString()
            OperationPolicy.candidate(name, 0)
            if (type == OperationType.UNDO && !acceptUnique && files.isOccupied(targetDirectory.resolve(name).toString())) {
                return OperationResult.Blocked("The original name is occupied. Choose unique-name restoration or Cancel.")
            }
            val now = clock()
            current = OperationRecord(
                id, type, original?.id, path.toString(), targetDirectory.toString(), name,
                source.identity, source.size, source.stamp, source.hash,
                sourceToken = "${source.path}|${source.identity}",
                stagingPath = targetDirectory.resolve(".s26-$id.part").toString(),
                startedAt = now, updatedAt = now,
            )
            journalCall = true
            val created = journal.create(requireNotNull(current))
            journalCall = false
            if (!created) return OperationResult.Rejected(OperationError.CONFLICT)
            stored = true
            checkpoint(Checkpoint.INTENT_SAVED)
            check()
            persist(requireNotNull(current).copy(phase = Phase.ALLOCATING))
            mutationAttempted = true
            files.ensureDestination(targetDirectory)
            checkpoint(Checkpoint.DIRECTORY_READY)
            check()
            val stage = requireNotNull(current).stagingPath
            val stageIdentity = files.reserveStage(stage)
            checkpoint(Checkpoint.STAGE_RESERVED)
            if (stageIdentity.isBlank()) throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
            persist(requireNotNull(current).copy(phase = Phase.COPYING, stagingIdentity = stageIdentity))
            checkpoint(Checkpoint.COPYING_SAVED)
            val count = files.copyAndSync(source, stage, stageIdentity) {
                check()
                checkpoint(Checkpoint.COPY_CHUNK)
            }
            checkpoint(Checkpoint.COPY_FINISHED)
            check()
            val stageEvidence = files.verify(stage, stageIdentity)
            val revalidated = files.prepare(source.path, type)
            if (stageEvidence.path != stage || stageEvidence.identity != stageIdentity || count != source.size || stageEvidence.size != source.size || stageEvidence.hash != source.hash || revalidated != source) {
                throw OperationFailure(OperationError.SOURCE_CHANGED)
            }
            persist(requireNotNull(current).copy(phase = Phase.COPY_VERIFIED, bytesCopied = count, destinationVerified = true))
            checkpoint(Checkpoint.COPY_VERIFIED)
            var published: FileEvidence? = null
            for (number in 0..9999) {
                check()
                val candidate = targetDirectory.resolve(OperationPolicy.candidate(name, number)).toString()
                if (candidate.toByteArray(Charsets.UTF_8).size > 4095) throw OperationFailure(OperationError.INVALID_REQUEST)
                persist(requireNotNull(current).copy(destinationPath = candidate)) // Intent BEFORE publication.
                val identity = try {
                    files.publishNoClobber(stage, stageIdentity, candidate)
                } catch (_: FileAlreadyExistsException) {
                    if (type == OperationType.UNDO && !acceptUnique) throw OperationFailure(OperationError.CONFLICT)
                    continue
                }
                checkpoint(Checkpoint.PUBLISHED)
                // Remove temporary stage before measuring destination stamp so unlinking a hard link does not invalidate ctime.
                if (files.canConditionallyRemove) {
                    runCatching { files.cleanupStage(stage, stageIdentity) }
                }
                val evidence = files.verify(candidate, identity)
                if (evidence.path != candidate || evidence.hash != source.hash || evidence.size != source.size || evidence.identity != identity) {
                    throw OperationFailure(OperationError.CONFLICT)
                }
                published = evidence
                break
            }
            val destination = published ?: throw OperationFailure(OperationError.CONFLICT)
            val synced = files.syncDirectory(targetDirectory.toString())
            persist(requireNotNull(current).copy(
                phase = Phase.DESTINATION_COMMITTED, destinationIdentity = destination.identity,
                destinationStamp = destination.stamp, destinationPublished = true, destinationSynced = synced,
            ))
            checkpoint(Checkpoint.COMMIT_SAVED)
            check()
            if (files.prepare(source.path, type) != source) throw OperationFailure(OperationError.SOURCE_CHANGED)
            if (!synced || !files.canConditionallyRemove) {
                persist(requireNotNull(current).copy(
                    phase = Phase.COPY_ONLY, recoveryNeeded = true,
                    error = if (!synced) OperationError.DURABILITY_UNCONFIRMED else OperationError.NO_SAFE_REMOVAL,
                ))
                return OperationResult.Recorded(requireNotNull(current))
            }
            persist(requireNotNull(current).copy(phase = Phase.SOURCE_REMOVAL_PENDING, removalAuthorized = true))
            checkpoint(Checkpoint.REMOVAL_INTENT_SAVED)
            check()
            checkpoint(Checkpoint.BEFORE_REMOVAL)
            when (files.removeConditionally(source, destination)) {
                Removal.UNSUPPORTED -> {
                    persist(requireNotNull(current).copy(phase = Phase.COPY_ONLY, error = OperationError.NO_SAFE_REMOVAL, recoveryNeeded = true))
                    return OperationResult.Recorded(requireNotNull(current))
                }
                Removal.CONFLICT -> throw OperationFailure(OperationError.CONFLICT)
                Removal.REMOVED -> Unit
            }
            removalPerformed = true
            checkpoint(Checkpoint.AFTER_REMOVAL)
            if (files.observe(source.path, source.size) !is Observation.Missing ||
                files.verify(destination.path, destination.identity) != destination) throw OperationFailure(OperationError.CONFLICT)
            if (!files.syncDirectory(path.parent.toString())) {
                persist(requireNotNull(current).copy(phase = Phase.RECOVERY_REQUIRED, sourceRemoved = true,
                    removalEvidence = "adapter-confirmed", error = OperationError.DURABILITY_UNCONFIRMED, recoveryNeeded = true))
                return OperationResult.Recorded(requireNotNull(current))
            }
            removalSynced = true
            persist(requireNotNull(current).copy(phase = Phase.SOURCE_REMOVED, sourceRemoved = true,
                sourceRemovalSynced = true, removalEvidence = "adapter-confirmed"))
            checkpoint(Checkpoint.SOURCE_REMOVED_SAVED)
            checkpoint(Checkpoint.BEFORE_COMPLETION)
            persist(requireNotNull(current).copy(phase = Phase.COMPLETED, sourceRemoved = true,
                removalEvidence = "adapter-confirmed", completedAt = maxOf(clock(), requireNotNull(current).updatedAt)))
            checkpoint(Checkpoint.COMPLETED)
            return OperationResult.Recorded(requireNotNull(current))
        } catch (failure: Exception) {
            if (!stored) {
                if (failure is CancellationException) throw failure
                if (journalCall) return OperationResult.JournalUnavailable(id)
                return when (failure) {
                    is OperationFailure -> OperationResult.Rejected(failure.category)
                    is IllegalArgumentException -> OperationResult.Rejected(OperationError.INVALID_REQUEST)
                    is SecurityException -> OperationResult.Rejected(OperationError.PERMISSION)
                    else -> OperationResult.Rejected(OperationError.IO)
                }
            }
            val previous = requireNotNull(current)
            if (previous.phase == Phase.COMPLETED) return OperationResult.Recorded(previous)
            val error = if (journalCall) OperationError.JOURNAL else when (failure) {
                is OperationFailure -> failure.category
                is CancellationException -> OperationError.CANCELLED
                is SecurityException -> OperationError.PERMISSION
                else -> OperationError.IO
            }
            val result = withContext(NonCancellable) {
                try {
                    persist(previous.copy(
                        phase = if (mutationAttempted) Phase.RECOVERY_REQUIRED else Phase.FAILED_SAFE,
                        error = error, recoveryNeeded = mutationAttempted,
                        sourceRemoved = previous.sourceRemoved || removalPerformed,
                        sourceRemovalSynced = previous.sourceRemovalSynced || removalSynced,
                        removalEvidence = if (removalPerformed) "adapter-confirmed" else previous.removalEvidence,
                        sourceToken = if (mutationAttempted) previous.sourceToken else null,
                    ))
                    OperationResult.Recorded(requireNotNull(current))
                } catch (_: Exception) {
                    OperationResult.JournalUnavailable(previous.id)
                }
            }
            if (failure is CancellationException) throw failure
            return result
        }
    }
}

internal fun legalTransition(from: Phase, to: Phase): Boolean = when {
    from == Phase.COMPLETED || from == Phase.FAILED_SAFE -> false
    to == Phase.RECOVERY_REQUIRED || to == Phase.FAILED_SAFE -> true
    from == to -> true
    else -> to in when (from) {
        Phase.PREPARED -> setOf(Phase.ALLOCATING)
        Phase.ALLOCATING -> setOf(Phase.COPYING)
        Phase.COPYING -> setOf(Phase.COPY_VERIFIED)
        Phase.COPY_VERIFIED -> setOf(Phase.DESTINATION_COMMITTED)
        Phase.DESTINATION_COMMITTED -> setOf(Phase.COPY_ONLY, Phase.SOURCE_REMOVAL_PENDING)
        Phase.SOURCE_REMOVAL_PENDING -> setOf(Phase.SOURCE_REMOVED, Phase.COPY_ONLY)
        Phase.SOURCE_REMOVED -> setOf(Phase.COMPLETED)
        else -> emptySet()
    }
}

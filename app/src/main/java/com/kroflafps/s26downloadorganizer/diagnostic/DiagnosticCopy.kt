package com.kroflafps.s26downloadorganizer.diagnostic

import com.kroflafps.s26downloadorganizer.operations.FileEvidence
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import java.io.Closeable
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Separate capability: never authorizes MOVE/UNDO, never accepts a caller-supplied path. */
object DiagnosticPolicy {
    const val APPLICATION_ID = "com.kroflafps.s26downloadorganizer.diagnostic"
    const val INPUT = "Download/S26_Organizer_Test_Input"
    const val OUTPUT = "Organized/S26_Organizer_Test_Output"
    const val MAX_BYTES = 1024L * 1024
    const val SUCCESS = "Copy only — original kept in Downloads."
    val fixtures = mapOf(
        "s26-test-empty.bin" to (0L to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        "s26-test-1MiB.bin" to (MAX_BYTES to "631b84027d6b9e52b539c4e8373622d23032dfadc64d60af87339c9037e4f769"),
    )
    fun requireFixture(evidence: FileEvidence, name: String) {
        require(fixtures[name] == (evidence.size to evidence.hash)) { "Only documented synthetic fixtures are accepted" }
        require(evidence.identity.isNotBlank() && evidence.stamp.isNotBlank())
    }
    fun requireName(name: String) { require(name in fixtures) { "Select a documented test fixture" } }
}

enum class CopyPhase { STAGE_ALLOCATING, STAGE_COPYING, STAGE_VERIFIED, FINAL_ALLOCATING, FINAL_COPYING, FINAL_VERIFIED, COPY_ONLY, RECOVERY_REQUIRED }

data class CopyRecord(
    val id: String, val name: String, val sourcePath: String, val outputDirectory: String,
    val sourceIdentity: String, val sourceStamp: String, val size: Long, val hash: String,
    val stagePath: String, val finalPath: String? = null,
    val stageIdentity: String? = null, val finalIdentity: String? = null,
    val stageVerified: Boolean = false, val finalVerified: Boolean = false,
    val fileSynced: Boolean = false, val directorySynced: Boolean = false,
    val phase: CopyPhase = CopyPhase.STAGE_ALLOCATING,
    val error: String? = null, val revision: Int = 0, val startedAt: Long, val updatedAt: Long,
) {
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        DiagnosticPolicy.requireName(name)
        require(DiagnosticPolicy.fixtures[name] == (size to hash))
        require(sourceIdentity.isNotBlank() && sourceStamp.isNotBlank())
        require(revision >= 0 && updatedAt >= startedAt)
        val output = java.nio.file.Paths.get(outputDirectory)
        require(output.isAbsolute && output == output.normalize())
        // Persistence never authorizes arbitrary paths, even when the journal is malformed.
        val primary = output.parent?.parent ?: error("Missing primary root")
        require(output == primary.resolve(DiagnosticPolicy.OUTPUT))
        require(sourcePath == primary.resolve(DiagnosticPolicy.INPUT).resolve(name).toString())
        require(stagePath == output.resolve(".s26-diagnostic-$id.part").toString())
        finalPath?.let { raw ->
            val path = java.nio.file.Paths.get(raw)
            require(path.parent == output && path == path.normalize())
            require((0..999).any { OperationPolicy.candidate(name, it) == path.fileName.toString() })
        }
        require(stageIdentity == null || stageIdentity.isNotBlank())
        require(finalIdentity == null || finalIdentity.isNotBlank())
        require(!stageVerified || stageIdentity != null)
        require(!finalVerified || stageVerified && finalIdentity != null && finalPath != null)
        require(!fileSynced || finalVerified)
        require(!directorySynced || fileSynced)
        if (phase == CopyPhase.COPY_ONLY || phase == CopyPhase.FINAL_VERIFIED) require(finalVerified && fileSynced && directorySynced && error == null)
    }
    fun successor(previous: CopyRecord) {
        validate(); previous.validate()
        require(previous.revision < Int.MAX_VALUE && revision == previous.revision + 1 && updatedAt >= previous.updatedAt)
        require(copy(finalPath = previous.finalPath, stageIdentity = previous.stageIdentity, finalIdentity = previous.finalIdentity,
            stageVerified = previous.stageVerified, finalVerified = previous.finalVerified, fileSynced = previous.fileSynced,
            directorySynced = previous.directorySynced, phase = previous.phase, error = previous.error,
            revision = previous.revision, updatedAt = previous.updatedAt) == previous)
        require(previous.phase != CopyPhase.COPY_ONLY)
        require(phase == CopyPhase.RECOVERY_REQUIRED || phase == previous.phase && phase == CopyPhase.FINAL_ALLOCATING ||
            phase.ordinal == previous.phase.ordinal + 1 && previous.phase.ordinal < CopyPhase.COPY_ONLY.ordinal)
        require(!previous.stageVerified || stageVerified)
        require(!previous.finalVerified || finalVerified)
        require(!previous.fileSynced || fileSynced)
        require(!previous.directorySynced || directorySynced)
        require(previous.stageIdentity == null || stageIdentity == previous.stageIdentity)
        require(previous.finalIdentity == null || finalIdentity == previous.finalIdentity)
        require(finalPath == previous.finalPath || phase == CopyPhase.FINAL_ALLOCATING)
        if (stageIdentity != previous.stageIdentity) require(previous.phase == CopyPhase.STAGE_ALLOCATING && phase == CopyPhase.STAGE_COPYING)
        if (finalIdentity != previous.finalIdentity) require(previous.phase == CopyPhase.FINAL_ALLOCATING && phase == CopyPhase.FINAL_COPYING)
        if (stageVerified != previous.stageVerified) require(phase == CopyPhase.STAGE_VERIFIED)
        if (finalVerified != previous.finalVerified || fileSynced != previous.fileSynced || directorySynced != previous.directorySynced) require(phase == CopyPhase.FINAL_VERIFIED)
    }
}

/** Session must anchor approved directories and retain exclusively created writable descriptors.
 * No delete, move, rename, overwrite, mkdir, traversal, or arbitrary-path method exists.
 */
interface DiagnosticSession : Closeable {
    val input: Path
    val output: Path
    fun source(name: String, check: () -> Unit): FileEvidence
    fun createStage(name: String): String
    fun copySourceToStage(source: FileEvidence, stageName: String, identity: String, check: () -> Unit)
    fun createFinal(name: String): String
    fun copyStageToFinal(stageName: String, stageIdentity: String, finalName: String, finalIdentity: String, source: FileEvidence, check: () -> Unit)
    fun syncOutputDirectory()
    /** Read-only observations for interrupted records, never creates or opens a file for writing. */
    fun describe(record: CopyRecord, check: () -> Unit): String
}

interface DiagnosticJournal {
    suspend fun get(id: String): CopyRecord?
    suspend fun create(record: CopyRecord): Boolean
    suspend fun save(record: CopyRecord)
    suspend fun page(): List<CopyRecord>
    suspend fun advance(id: String)
}

class DiagnosticCopyCoordinator(
    private val open: () -> DiagnosticSession,
    private val journal: DiagnosticJournal,
    private val enabled: () -> Boolean,
    private val permission: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun inspect(): List<FileEvidence> = exclusive {
        val context = currentCoroutineContext()
        open().use { session -> DiagnosticPolicy.fixtures.keys.mapNotNull { name ->
            try { session.source(name) { context.ensureActive(); check() }.also { DiagnosticPolicy.requireFixture(it, name) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.nio.file.NoSuchFileException) { null }
        } }
    }

    suspend fun copy(id: String, name: String, acknowledged: Boolean): CopyRecord = exclusive {
        require(acknowledged) { "Confirm disposable test copy first" }
        DiagnosticPolicy.requireName(name)
        require(UUID.fromString(id).toString() == id)
        journal.get(id)?.let { require(it.name == name); return@exclusive it }
        var current: CopyRecord? = null
        suspend fun save(next: CopyRecord) {
            val old = requireNotNull(current)
            val revision = next.copy(revision = old.revision + 1, updatedAt = maxOf(clock(), old.updatedAt))
            journal.save(revision); current = revision
        }
        try {
            open().use { session ->
                val context = currentCoroutineContext()
                fun checkpoint() { context.ensureActive(); check() }
                val source = session.source(name, ::checkpoint)
                DiagnosticPolicy.requireFixture(source, name)
                require(source.path == session.input.resolve(name).toString())
                val now = clock()
                val intent = CopyRecord(id, name, source.path, session.output.toString(), source.identity, source.stamp,
                    source.size, source.hash, session.output.resolve(".s26-diagnostic-$id.part").toString(), startedAt = now, updatedAt = now)
                intent.validate()
                check(journal.create(intent)) { "This fixture is already claimed by an unresolved operation" }
                current = intent // Durable intent precedes exclusive creation, copying and every final allocation attempt.
                checkpoint()
                val stageName = java.nio.file.Paths.get(intent.stagePath).fileName.toString()
                val stageKey = session.createStage(stageName)
                save(requireNotNull(current).copy(phase = CopyPhase.STAGE_COPYING, stageIdentity = stageKey))
                session.copySourceToStage(source, stageName, stageKey, ::checkpoint)
                save(requireNotNull(current).copy(phase = CopyPhase.STAGE_VERIFIED, stageVerified = true))
                var allocated = false
                for (number in 0..999) {
                    checkpoint()
                    val candidate = OperationPolicy.candidate(name, number)
                    save(requireNotNull(current).copy(phase = CopyPhase.FINAL_ALLOCATING, finalPath = session.output.resolve(candidate).toString()))
                    val key = try { session.createFinal(candidate) } catch (_: FileAlreadyExistsException) { continue }
                    save(requireNotNull(current).copy(phase = CopyPhase.FINAL_COPYING, finalIdentity = key))
                    session.copyStageToFinal(stageName, stageKey, candidate, key, source, ::checkpoint)
                    session.syncOutputDirectory(); checkpoint()
                    // Re-read source after final publication/sync. Never labels a changed source a verified copy.
                    require(session.source(name, ::checkpoint) == source) { "Source changed" }
                    save(requireNotNull(current).copy(phase = CopyPhase.FINAL_VERIFIED, finalVerified = true, fileSynced = true, directorySynced = true))
                    allocated = true; break
                }
                check(allocated) { "Collision limit reached" }
            } // A close failure must also prevent a success response.
            save(requireNotNull(current).copy(phase = CopyPhase.COPY_ONLY))
            requireNotNull(current)
        } catch (failure: Exception) {
            val old = current
            if (old != null) withContext(NonCancellable) {
                try { save(old.copy(phase = CopyPhase.RECOVERY_REQUIRED, error = "Stopped in ${old.phase}. ${failureMessage(failure)}")) } catch (_: Exception) { /* Keep last durable intent. Never retry filesystem writes. */ }
            }
            throw failure
        }
    }

    /** Bounded fair evidence-only check. No interrupted state becomes success, and no output is deleted. */
    suspend fun recover(): Int = exclusive {
        var checked = 0
        for (row in journal.page()) {
            currentCoroutineContext().ensureActive(); check()
            try {
                row.validate()
                val context = currentCoroutineContext()
                val description = open().use { it.describe(row) { context.ensureActive(); check() } }
                if (row.phase != CopyPhase.RECOVERY_REQUIRED || row.error != description) journal.save(row.copy(
                    phase = CopyPhase.RECOVERY_REQUIRED, error = description, revision = row.revision + 1, updatedAt = maxOf(clock(), row.updatedAt)))
                checked++
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* The persisted intent stays unresolved; advancing never certifies success. */ }
            journal.advance(row.id)
        }
        checked
    }

    private fun check() { check(enabled()) { "Diagnostic capability disabled" }; check(permission()) { "All Files Access and writable primary storage required" } }
    private suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        check(); check(mutex.tryLock()) { "A diagnostic operation is already running" }
        try { block() } finally { mutex.unlock() }
    }
}

class DiagnosticUnavailable(val capability: String, val errno: Int) : java.io.IOException("$capability unavailable (errno $errno)")

fun failureMessage(error: Exception): String = when (error) {
    is CancellationException -> "Cancelled. Preserve stage/final files and check diagnostic recovery."
    is DiagnosticUnavailable -> "${error.capability} is unsupported or unavailable (errno ${error.errno}). No unsafe fallback. Preserve all files and check diagnostic recovery."
    is java.nio.file.NoSuchFileException -> "An exact test directory or fixture is missing. Create only the documented folders/files with My Files."
    else -> "Unsupported or interrupted test copy (${error.javaClass.simpleName}). Preserve the original and any partial outputs; check diagnostic recovery."
}

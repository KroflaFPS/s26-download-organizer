package com.kroflafps.s26downloadorganizer.operations

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** TEST ONLY. These directories belong exclusively to a fixture. Its synchronized removal
 * models a capability contract in a closed world; it is NOT Android unlink-if-inode support.
 * Real no-clobber create/link, bounded copying, fsync, hashing and failure boundaries are exercised.
 */
internal class FixtureFiles(private val privateRoot: Path, override val canConditionallyRemove: Boolean = true) : OperationFiles {
    override val roots = OperationRoots(privateRoot.resolve("Download"), privateRoot.resolve("Organized/Other"))
    var access = true
    var writable = true
    var directorySync = true
    var bytesLimit = Long.MAX_VALUE
    var copyHook: () -> Unit = {}
    var publicationHook: (String) -> Unit = {}
    private val world = locks.computeIfAbsent(privateRoot.toString()) { Any() }

    init { if (!Files.exists(roots.downloads)) Files.createDirectory(roots.downloads) }
    private fun permitted(path: Path) {
        if (path != path.toAbsolutePath().normalize() || !path.startsWith(privateRoot)) throw OperationFailure(OperationError.INVALID_REQUEST)
        var part: Path? = path
        while (part != null && part.startsWith(privateRoot)) {
            if (Files.isSymbolicLink(part)) throw OperationFailure(OperationError.INVALID_REQUEST)
            part = part.parent
        }
    }
    private fun file(path: String): Path {
        val parsed = Paths.get(path)
        permitted(parsed)
        if (parsed.parent != roots.downloads && parsed.parent != roots.other) throw OperationFailure(OperationError.INVALID_REQUEST)
        return parsed
    }
    override fun requireWritableAccess() {
        if (!access) throw OperationFailure(OperationError.PERMISSION)
        if (!writable) throw OperationFailure(OperationError.STORAGE_UNAVAILABLE)
    }
    override fun isOccupied(path: String): Boolean = Files.exists(file(path), NOFOLLOW_LINKS)
    override fun prepare(path: String, type: OperationType): FileEvidence {
        requireWritableAccess()
        OperationPolicy.sourcePath(path, if (type == OperationType.MOVE) roots.downloads else roots.other)
        return evidence(path)
    }
    fun evidence(path: String, maximumBytes: Long = Long.MAX_VALUE, checkpoint: () -> Unit = {}): FileEvidence {
        val p = file(path)
        val before = attrs(p)
        if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null) throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
        val stamp = stamp(p)
        val hash = hash(p, maximumBytes, checkpoint)
        val after = attrs(p)
        if (before.fileKey() != after.fileKey() || before.size() != after.size() || stamp != stamp(p)) throw OperationFailure(OperationError.SOURCE_CHANGED)
        return FileEvidence(p.toString(), requireNotNull(before.fileKey()).toString(), before.size(), stamp, hash)
    }
    override fun ensureDestination(type: OperationType) {
        requireWritableAccess()
        if (type == OperationType.UNDO) {
            permitted(roots.downloads)
            if (!Files.isDirectory(roots.downloads, NOFOLLOW_LINKS)) throw OperationFailure(OperationError.INVALID_REQUEST)
            return
        }
        for (directory in listOf(roots.other.parent, roots.other)) {
            permitted(directory)
            try { Files.createDirectory(directory) } catch (_: FileAlreadyExistsException) { /* Validate, never replace. */ }
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) throw OperationFailure(OperationError.CONFLICT)
        }
    }
    override fun reserveStage(path: String): String {
        requireWritableAccess()
        val p = file(path)
        FileChannel.open(p, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        return requireNotNull(attrs(p).fileKey()).toString()
    }
    override fun copyAndSync(source: FileEvidence, stage: String, stageIdentity: String, checkpoint: () -> Unit): Long {
        requireWritableAccess()
        val target = file(stage)
        if (attrs(target).fileKey().toString() != stageIdentity || attrs(target).size() != 0L) throw OperationFailure(OperationError.CONFLICT)
        if (Files.getFileStore(target).usableSpace < source.size) throw java.io.IOException("fixture storage full")
        var count = 0L
        FileChannel.open(file(source.path), READ, NOFOLLOW_LINKS).use { input ->
            FileChannel.open(target, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    requireWritableAccess()
                    val read = input.read(buffer)
                    if (read == -1) break
                    count += read
                    if (count > bytesLimit) throw java.io.IOException("injected storage full/interrupted I/O")
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    buffer.clear()
                    copyHook()
                    checkpoint()
                }
                output.force(true)
            }
        }
        return count
    }
    override fun verify(path: String, identity: String): FileEvidence = evidence(path).also {
        if (it.identity != identity) throw OperationFailure(OperationError.CONFLICT)
    }
    override fun publishNoClobber(stage: String, stageIdentity: String, destination: String): String {
        requireWritableAccess()
        verify(stage, stageIdentity)
        publicationHook(destination)
        // POSIX link creation cannot replace an existing name. No move or rename is used.
        Files.createLink(file(destination), file(stage))
        return attrs(file(destination)).fileKey().toString()
    }
    override fun syncDirectory(path: String): Boolean {
        if (!directorySync) return false
        val directory = Paths.get(path)
        permitted(directory)
        FileChannel.open(directory, READ, NOFOLLOW_LINKS).use { it.force(true) }
        return true
    }
    override fun removeConditionally(source: FileEvidence, destination: FileEvidence): Removal {
        if (!canConditionallyRemove) return Removal.UNSUPPORTED
        return synchronized(world) {
            requireWritableAccess()
            if (evidence(source.path) != source || evidence(destination.path) != destination) Removal.CONFLICT
            else { Files.delete(file(source.path)); Removal.REMOVED }
        }
    }
    override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation = try {
        val p = file(path)
        if (!Files.exists(p, NOFOLLOW_LINKS)) Observation.Missing
        else if (attrs(p).size() > maximumBytes) Observation.Unverifiable
        else Observation.Present(evidence(path, maximumBytes, checkpoint))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (_: Exception) { Observation.Unverifiable }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        private fun stamp(path: Path) = "${attrs(path).lastModifiedTime()}|${Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS)}"
        fun hash(path: Path, maximumBytes: Long = Long.MAX_VALUE, checkpoint: () -> Unit = {}): String {
            val hash = MessageDigest.getInstance("SHA-256")
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                var bytes = 0L
                while (true) {
                    checkpoint()
                    buffer.limit(minOf(buffer.capacity().toLong(), maximumBytes - bytes + if (maximumBytes == Long.MAX_VALUE) 0 else 1).toInt())
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count.toLong() > maximumBytes - bytes) throw java.io.IOException("verification budget exceeded")
                    bytes += count
                    buffer.flip(); hash.update(buffer); buffer.clear()
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal class MemoryJournal : OperationJournal {
    private val mutex = Mutex()
    val records = linkedMapOf<String, OperationRecord>()
    val events = mutableListOf<OperationRecord>()
    private var cursorLast: String? = null
    private var cursorCeiling: String? = null
    var failBefore: (OperationRecord) -> Boolean = { false }
    var failAfter: (OperationRecord) -> Boolean = { false }
    override suspend fun get(id: String) = mutex.withLock { records[id] }
    override suspend fun undoFor(id: String) = mutex.withLock { records.values.firstOrNull { it.relatedId == id } }
    override suspend fun create(record: OperationRecord): Boolean = mutex.withLock {
        record.requireNewIntent()
        if (record.relatedId != null) require(records[record.relatedId]?.undoEligible == true)
        if (failBefore(record)) throw java.io.IOException("journal write failed")
        if (records.containsKey(record.id) || records.values.any { record.sourceToken != null && it.sourceToken == record.sourceToken || record.relatedId != null && it.relatedId == record.relatedId }) return@withLock false
        records[record.id] = record; events += record
        if (failAfter(record)) throw java.io.IOException("journal response lost after commit")
        true
    }
    override suspend fun save(record: OperationRecord) = mutex.withLock {
        record.requireSuccessorOf(records.getValue(record.id))
        if (failBefore(record)) throw java.io.IOException("journal write failed")
        check(records.getValue(record.id).revision + 1 == record.revision)
        records[record.id] = record; events += record
        if (failAfter(record)) throw java.io.IOException("journal response lost after commit")
    }
    override suspend fun incomplete(limit: Int) = mutex.withLock {
        records.values.filter { it.phase !in setOf(Phase.COMPLETED, Phase.FAILED_SAFE) }.sortedBy { it.updatedAt }.take(limit)
    }
    override suspend fun nextRecoveryPage(limit: Int): RecoveryPage = mutex.withLock {
        require(limit in 1..20)
        val pending = records.values.filter { it.phase !in setOf(Phase.COMPLETED, Phase.FAILED_SAFE) }.sortedBy { it.id }
        var upper = cursorCeiling ?: pending.lastOrNull()?.id ?: return@withLock RecoveryPage(emptyList(), null)
        var page = pending.filter { (cursorLast == null || it.id > cursorLast!!) && it.id <= upper }.take(limit)
        if (page.isEmpty()) { upper = pending.lastOrNull()?.id ?: return@withLock RecoveryPage(emptyList(), null); page = pending.filter { it.id <= upper }.take(limit) }
        RecoveryPage(page, upper)
    }
    override suspend fun advanceRecoveryCursor(lastId: String, ceilingId: String) = mutex.withLock {
        cursorLast = lastId; cursorCeiling = ceilingId
    }

}

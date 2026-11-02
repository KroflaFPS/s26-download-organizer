package com.kroflafps.s26downloadorganizer.storage

import com.kroflafps.s26downloadorganizer.operations.*
import java.io.Closeable
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/** Read-only descriptor port: no create, write, link, rename, unlink or truncation API. */
internal interface ReadHandle : Closeable {
    fun metadata(): ReadMetadata
    fun resolvedPath(): Path
    fun openDirectory(name: String): ReadHandle
    fun openFile(name: String): ReadHandle
    fun entryMetadata(name: String): ReadMetadata
    fun read(buffer: ByteArray, count: Int): Int
}

internal data class ReadMetadata(val identity: String, val size: Long, val stamp: String, val regular: Boolean, val directory: Boolean)
internal interface ReadHandleFactory {
    fun canonicalPrimary(path: Path): Path
    fun openPrimary(path: Path): ReadHandle
}

/** Only hashes immediate journaled entries beneath approved roots. Never follows a child symlink.
 * Metadata and hash belong to the same open descriptor. This is a bounded snapshot, NOT a removal receipt.
 */
internal class RootedReadInspector(
    private val primary: Path,
    private val factory: ReadHandleFactory,
    private val canRead: () -> Boolean,
) {
    val roots = OperationRoots(primary.resolve("Download"), primary.resolve("Organized/Other"))

    fun inspect(raw: String, maximumBytes: Long, checkpoint: () -> Unit = {}): Observation {
        require(maximumBytes >= 0)
        fun check() {
            checkpoint()
            if (!canRead()) throw OperationFailure(OperationError.PERMISSION)
        }
        check()
        val path = Paths.get(raw)
        if (!path.isAbsolute || path != path.normalize() || path.parent !in listOf(roots.downloads, roots.other) ||
            path.fileName.toString() in listOf("", ".", "..")) throw OperationFailure(OperationError.INVALID_REQUEST)
        val handles = mutableListOf<ReadHandle>()
        try {
            val canonical = factory.canonicalPrimary(primary)
            val root = factory.openPrimary(primary).also { handles.add(it) }
            if (!root.metadata().directory || root.resolvedPath() != canonical) throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
            var parent = root
            var expected = canonical
            val chain = if (path.parent == roots.downloads) listOf("Download") else listOf("Organized", "Other")
            try {
                for (name in chain) {
                    check()
                    parent = parent.openDirectory(name).also { handles.add(it) }
                    expected = expected.resolve(name)
                    if (!parent.metadata().directory || parent.resolvedPath() != expected) throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
                }
                val name = path.fileName.toString()
                val input = parent.openFile(name).also { handles.add(it) }
                val before = input.metadata()
                if (!before.regular || before.directory || before.identity.isBlank() || before.stamp.isBlank() || before.size < 0) {
                    throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
                }
                if (before.size > maximumBytes) return Observation.Unverifiable
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    check()
                    // At most the allowed bytes plus one EOF/growth sentinel; never hash to an unbounded EOF.
                    val room = maximumBytes - bytes
                    val count = minOf(buffer.size.toLong(), if (room == Long.MAX_VALUE) room else room + 1).toInt()
                    val read = input.read(buffer, count)
                    check()
                    if (read == -1) break
                    if (read <= 0 || read > count) throw IOException("No read progress")
                    if (read.toLong() > room) return Observation.Unverifiable
                    digest.update(buffer, 0, read)
                    bytes += read
                }
                check()
                if (bytes != before.size || before != input.metadata() || parent.entryMetadata(name) != before ||
                    input.resolvedPath() != expected.resolve(name) || parent.resolvedPath() != expected || root.resolvedPath() != canonical) {
                    return Observation.Unverifiable
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                return Observation.Present(FileEvidence(raw, before.identity, bytes, before.stamp, hash))
            } catch (_: NoSuchFileException) {
                check()
                // A missing child is only meaningful while the approved root anchor is still available.
                if (root.resolvedPath() != canonical) return Observation.Unverifiable
                return Observation.Missing
            }
        } finally {
            // A failed close makes this observation unusable instead of leaking a success result.
            var failure: Exception? = null
            for (handle in handles.asReversed()) try { handle.close() } catch (error: Exception) { failure = error }
            failure?.let { throw it }
        }
    }
}

package com.kroflafps.s26downloadorganizer.storage

import com.kroflafps.s26downloadorganizer.classification.*
import com.kroflafps.s26downloadorganizer.domain.EntryType
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/** Narrow positional extension; existing journal/hash consumers retain their sequential API. */
internal interface PositionalReadHandle : ReadHandle {
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int
}

/** Immediate Downloads children only. No path-only or symlink fallback on FUSE/procfs failure. */
internal class RootedPreviewReader(
    private val primary: Path,
    private val factory: ReadHandleFactory,
    private val canRead: () -> Boolean,
    private val modifiedMillis: (ReadMetadata) -> Long?,
) {
    fun classify(entry: InventoryEntry, checkpoint: () -> Unit = {}): Classification {
        val handles = mutableListOf<ReadHandle>()
        var closeFailed = false
        var result: Classification
        try {
            checkpoint()
            if (!canRead()) return FileClassifier.unavailable(Reason.PERMISSION, Safety.UNAVAILABLE)
            val path = Paths.get(entry.id)
            if (!path.isAbsolute || path != path.normalize() || path.parent != primary.resolve("Download") ||
                path.fileName?.toString() != entry.name || entry.type != EntryType.FILE ||
                entry.sizeBytes == null || entry.modifiedAtMillis == null) {
                return FileClassifier.unavailable(Reason.INVALID_SOURCE, Safety.UNKNOWN)
            }
            FileClassifier().preflight(SourceMetadata(entry.name, entry.sizeBytes, "metadata-only", "metadata-only"))?.let { return it }
            val canonical = factory.canonicalPrimary(primary)
            val root = factory.openPrimary(primary).also { handles += it }
            val rootBefore = root.metadata()
            if (!rootBefore.directory || root.resolvedPath() != canonical) throw SourceChangedException()
            val downloads = root.openDirectory("Download").also { handles += it }
            val downloadsBefore = downloads.metadata()
            val expected = canonical.resolve("Download")
            if (!downloadsBefore.directory || downloads.resolvedPath() != expected) throw SourceChangedException()
            val input = downloads.openFile(entry.name).also { handles += it }
            if (input !is PositionalReadHandle) throw IOException()
            val before = input.metadata()
            if (!before.regular || before.directory || before.identity.isBlank() || before.stamp.isBlank() ||
                before.size != entry.sizeBytes || modifiedMillis(before) != entry.modifiedAtMillis) throw SourceChangedException()
            fun validate() {
                checkpoint()
                if (!canRead()) throw SecurityException()
                if (input.metadata() != before || downloads.entryMetadata(entry.name) != before ||
                    input.resolvedPath() != expected.resolve(entry.name) || downloads.resolvedPath() != expected ||
                    root.resolvedPath() != canonical || root.metadata().identity != rootBefore.identity ||
                    root.entryMetadata("Download").identity != downloadsBefore.identity) throw SourceChangedException()
            }
            result = FileClassifier().classify(object : ProbeSource {
                override fun metadata(): SourceMetadata = readResult {
                    validate()
                    SourceMetadata(entry.name, before.size, before.identity, before.stamp)
                }
                override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int = readResult {
                    validate()
                    input.readAt(offset, buffer, bufferOffset, count)
                }
                // The outer owner closes all anchors and refuses successful results on close failure.
                override fun close() = Unit
            }, checkpoint)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            result = FileClassifier.unavailable(Reason.PERMISSION, Safety.UNAVAILABLE)
        } catch (_: SourceChangedException) {
            result = FileClassifier.unavailable(Reason.METADATA_CHANGED, Safety.METADATA_MISMATCH)
        } catch (_: Exception) {
            result = FileClassifier.unavailable(Reason.READ_ERROR, Safety.UNAVAILABLE)
        } finally {
            for (handle in handles.asReversed()) try { handle.close() } catch (_: Exception) { closeFailed = true }
        }
        return if (closeFailed) result.copy(safety = Safety.UNAVAILABLE, decision = Decision.REVIEW_HOLD,
            destination = null, reason = Reason.READ_ERROR) else result
    }

    private inline fun <T> readResult(block: () -> T): T = try { block()
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
    } catch (changed: SourceChangedException) { throw changed
    } catch (denied: SecurityException) { throw denied
    } catch (_: Exception) { throw IOException() }
}

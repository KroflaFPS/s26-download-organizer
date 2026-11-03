package com.kroflafps.s26downloadorganizer.storage

import com.kroflafps.s26downloadorganizer.classification.*
import com.kroflafps.s26downloadorganizer.domain.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Disposable fixture I/O and injected descriptor capabilities, not Android FUSE evidence. */
class RootedPreviewReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Handles : ReadHandleFactory {
        var active = 0
        var bytesRead = 0
        var opens = 0
        var onRead: () -> Unit = {}
        var relocated: Path? = null
        var closeFailure = false
        var metadataFailure = false
        override fun canonicalPrimary(path: Path) = path.toRealPath()
        override fun openPrimary(path: Path): ReadHandle = Node(path)
        private fun meta(path: Path): ReadMetadata {
            val attr = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            return ReadMetadata(attr.fileKey().toString(), attr.size(), "${attr.lastModifiedTime().toMillis()}|${Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS)}", attr.isRegularFile, attr.isDirectory)
        }
        inner class Node(private val path: Path) : PositionalReadHandle {
            private val channel: FileChannel?
            init {
                if (Files.isSymbolicLink(path)) throw IOException()
                channel = if (meta(path).regular) FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS) else null
                active++; opens++
            }
            override fun metadata(): ReadMetadata {
                if (metadataFailure) throw IllegalStateException("simulated FUSE anomaly")
                return meta(path)
            }
            override fun resolvedPath(): Path = if (path == relocated) path.resolveSibling("relocated") else path
            override fun openDirectory(name: String): ReadHandle = Node(path.resolve(name)).also { require(it.metadata().directory) }
            override fun openFile(name: String): ReadHandle = Node(path.resolve(name))
            override fun entryMetadata(name: String) = meta(path.resolve(name))
            override fun read(buffer: ByteArray, count: Int) = error("Preview must use positional reads")
            override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int {
                val n = requireNotNull(channel).read(ByteBuffer.wrap(buffer, bufferOffset, count), offset)
                if (n > 0) bytesRead += n
                onRead()
                return n
            }
            override fun close() { channel?.close(); active--; if (closeFailure) throw IOException() }
        }
    }
    private fun root() = temporary.newFolder().toPath().also { Files.createDirectory(it.resolve("Download")) }
    private fun file(root: Path, name: String = "file.pdf") = Files.write(root.resolve("Download").resolve(name), "%PDF-1.7\nsynthetic".toByteArray())
    private fun entry(path: Path) = InventoryEntry(path.toString(), path.fileName.toString(), EntryType.FILE, Files.size(path), Files.getLastModifiedTime(path).toMillis())
    private fun reader(root: Path, handles: Handles, permission: () -> Boolean = { true }) = RootedPreviewReader(root, handles, permission) { it.stamp.substringBefore('|').toLongOrNull() }

    @Test fun actualFixturePreviewLeavesAllBytesNamesMetadataAndDirectoriesUnchanged() {
        val root = root(); val source = file(root); val handles = Handles()
        val before = Files.readAllBytes(source); val inventory = entry(source)
        val result = reader(root, handles).classify(inventory)
        assertEquals(Destination.PDF, result.destination)
        assertEquals(handles.bytesRead, result.usage.totalBytes)
        assertArrayEquals(before, Files.readAllBytes(source))
        assertEquals(inventory, entry(source))
        assertFalse(Files.exists(root.resolve("Organized")))
        assertEquals(0, handles.active)
    }

    @Test fun traversalOutsideNestedAndMismatchedNamesNeverOpen() {
        val root = root(); val source = file(root); val handles = Handles(); val reader = reader(root, handles)
        val valid = entry(source)
        for (invalid in listOf(valid.copy(id = root.resolve("Download/../file.pdf").toString()), valid.copy(id = root.resolve("file.pdf").toString()), valid.copy(id = root.resolve("Download/nested/file.pdf").toString()), valid.copy(name = "different.pdf"))) {
            assertEquals(Reason.INVALID_SOURCE, reader.classify(invalid).reason)
        }
        assertEquals(0, handles.opens)
    }

    @Test fun symlinkFileAndSymlinkDownloadsAreRefused() {
        val root = root(); val source = file(root); val handles = Handles()
        val alias = Files.createSymbolicLink(root.resolve("Download/alias.pdf"), source)
        assertEquals(Decision.REVIEW_HOLD, reader(root, handles).classify(entry(source).copy(id = alias.toString(), name = "alias.pdf")).decision)
        Files.move(root.resolve("Download"), root.resolve("elsewhere"))
        Files.createSymbolicLink(root.resolve("Download"), root.resolve("elsewhere"))
        assertEquals(Decision.REVIEW_HOLD, reader(root, handles).classify(entry(root.resolve("Download/file.pdf"))).decision)
        assertEquals(0, handles.active)
    }

    @Test fun deniedAndNumberedActiveNamesGateWithoutDescriptors() {
        val root = root(); val source = file(root); val handles = Handles()
        assertEquals(Reason.PERMISSION, reader(root, handles) { false }.classify(entry(source)).reason)
        for (name in listOf("file.pdf.1", "file.apk.1", "file.PART")) {
            val fixture = file(root, name)
            assertEquals(Decision.REVIEW_HOLD, reader(root, handles).classify(entry(fixture)).decision)
        }
        assertEquals(0, handles.opens)
    }

    @Test fun staleInventorySizeOrTimestampNeverReadsContent() {
        val root = root(); val source = file(root); val handles = Handles(); val original = entry(source)
        val reader = reader(root, handles)
        assertEquals(Safety.METADATA_MISMATCH, reader.classify(original.copy(sizeBytes = 1)).safety)
        assertEquals(Safety.METADATA_MISMATCH, reader.classify(original.copy(modifiedAtMillis = 1)).safety)
        assertEquals(0, handles.bytesRead)
        assertEquals(0, handles.active)
    }

    @Test fun revokedGrantAfterReadDiscardsCandidateAndClosesAnchors() {
        val root = root(); val source = file(root); val handles = Handles(); var permission = true
        handles.onRead = { permission = false }
        val result = reader(root, handles) { permission }.classify(entry(source))
        assertEquals(Safety.UNAVAILABLE, result.safety)
        assertEquals(Reason.PERMISSION, result.reason)
        assertEquals(handles.bytesRead, result.usage.totalBytes)
        assertNull(result.destination)
        assertEquals(0, handles.active)
    }

    @Test fun replacementGrowthAndRelocationCannotYieldCandidate() {
        for (change in listOf("replace", "grow", "relocate")) {
            val root = root(); val source = file(root); val handles = Handles()
            handles.onRead = {
                when (change) {
                    "replace" -> { Files.move(source, source.resolveSibling("retained")); file(root) }
                    "grow" -> Files.write(source, ByteArray(9000))
                    else -> handles.relocated = root.resolve("Download")
                }
            }
            val result = reader(root, handles).classify(entry(source))
            assertEquals(result.toString(), Safety.METADATA_MISMATCH, result.safety)
            assertNull(result.destination)
            assertEquals(0, handles.active)
        }
    }

    @Test fun cancellationClosesEveryAnchorAndCloseFailureInvalidatesSuccess() {
        val root = root(); val source = file(root); val handles = Handles()
        handles.onRead = { throw CancellationException() }
        try { reader(root, handles).classify(entry(source)); fail() } catch (_: CancellationException) { }
        assertEquals(0, handles.active)
        handles.onRead = {}; handles.closeFailure = true
        val result = reader(root, handles).classify(entry(source))
        assertEquals(Reason.READ_ERROR, result.reason)
        assertNull(result.destination)
        assertEquals(handles.bytesRead / 2, result.usage.totalBytes)
        assertEquals(0, handles.active)
    }

    @Test fun metadataFailureAfterReadPreservesActualByteAccountingAndDiscardsSuccess() {
        val root = root(); val source = file(root); val handles = Handles()
        handles.onRead = { handles.metadataFailure = true }
        val result = reader(root, handles).classify(entry(source))
        assertEquals(Reason.READ_ERROR, result.reason)
        assertEquals(handles.bytesRead, result.usage.totalBytes)
        assertNull(result.destination)
        assertEquals(0, handles.active)
    }
}

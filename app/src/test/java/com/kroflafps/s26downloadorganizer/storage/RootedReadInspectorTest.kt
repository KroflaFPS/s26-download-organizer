package com.kroflafps.s26downloadorganizer.storage

import com.kroflafps.s26downloadorganizer.operations.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real disposable read I/O around an injected descriptor port; NOT an Android /proc/FUSE test. */
class RootedReadInspectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Handles : ReadHandleFactory {
        var active = 0
        var bytesRead = 0L
        var onRead: () -> Unit = {}
        var relocated: Path? = null
        override fun canonicalPrimary(path: Path) = path.toRealPath()
        override fun openPrimary(path: Path): ReadHandle = Node(path)
        private fun meta(path: Path): ReadMetadata {
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            return ReadMetadata(a.fileKey().toString(), a.size(), "${a.lastModifiedTime()}|${Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS)}", a.isRegularFile, a.isDirectory)
        }
        inner class Node(private val path: Path) : ReadHandle {
            private val channel: FileChannel?
            init {
                if (Files.isSymbolicLink(path)) throw java.io.IOException("symlink denied")
                val a = meta(path)
                channel = if (a.regular) FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS) else null
                active++
            }
            override fun metadata() = meta(path)
            override fun resolvedPath() = relocated?.let { if (path == it) path.resolveSibling("relocated") else path } ?: path
            override fun openDirectory(name: String): ReadHandle = Node(path.resolve(name)).also { require(it.metadata().directory) }
            override fun openFile(name: String): ReadHandle = Node(path.resolve(name))
            override fun entryMetadata(name: String) = meta(path.resolve(name))
            override fun read(buffer: ByteArray, count: Int): Int {
                val read = requireNotNull(channel).read(java.nio.ByteBuffer.wrap(buffer, 0, count))
                if (read > 0) bytesRead += read
                onRead()
                return read
            }
            override fun close() { channel?.close(); active-- }
        }
    }
    private fun root() = temporary.newFolder().toPath().also { Files.createDirectory(it.resolve("Download")) }

    @Test fun adapterReadsVerifiedMetadataAndHashWithoutCreatingDestinationsOnPrepare() {
        val root = root(); val handles = Handles()
        val source = Files.write(root.resolve("Download/你好 file.bin"), byteArrayOf(0, 1, -1))
        val adapter = AndroidOperationFiles(RootedReadInspector(root, handles, { true }), writable = { false })
        try { adapter.prepare(source.toString(), OperationType.MOVE); fail("Unwritable adapter must fail prepare") } catch (_: OperationFailure) { }
        val writableAdapter = AndroidOperationFiles(RootedReadInspector(root, handles, { true }))
        val result = writableAdapter.prepare(source.toString(), OperationType.MOVE)
        assertEquals(3L, result.size)
        assertEquals("26a66b061e8f48f39927c312f25293959729eee95978e2892d49d3512a5cc092", result.hash)
        assertFalse(Files.exists(root.resolve("Organized")))
        assertArrayEquals(byteArrayOf(0, 1, -1), Files.readAllBytes(source))
        assertEquals(0, handles.active)
        assertEquals(Observation.Missing, writableAdapter.observe(root.resolve("Download/missing").toString(), 4))
        assertTrue(writableAdapter.canConditionallyRemove)
        try { adapter.reserveStage(root.resolve("Download/not-created").toString()); fail() } catch (_: OperationFailure) { }
        assertFalse(Files.exists(root.resolve("Download/not-created")))
    }

    @Test fun linksTraversalAndRelocatedRootCannotYieldTrustedEvidence() {
        val root = root(); val handles = Handles(); val reader = RootedReadInspector(root, handles, { true })
        val source = Files.write(root.resolve("Download/data"), byteArrayOf(1))
        val link = Files.createSymbolicLink(root.resolve("Download/alias"), source)
        val adapter = AndroidOperationFiles(reader)
        assertEquals(Observation.Unverifiable, adapter.observe(link.toString(), 4))
        assertEquals(Observation.Unverifiable, adapter.observe(root.resolve("Download/../elsewhere").toString(), 4))
        handles.relocated = root.resolve("Download")
        assertEquals(Observation.Unverifiable, adapter.observe(source.toString(), 4))
        assertEquals(0, handles.active)
    }

    @Test fun growthCannotEscapeByteLimitAndCancelledReadsCloseAllDescriptors() {
        val root = root(); val source = Files.write(root.resolve("Download/data"), byteArrayOf(1))
        val handles = Handles()
        var changed = false
        handles.onRead = { if (!changed) { changed = true; Files.write(source, ByteArray(128 * 1024)) } }
        val adapter = AndroidOperationFiles(RootedReadInspector(root, handles, { true }))
        assertEquals(Observation.Unverifiable, adapter.observe(source.toString(), 1))
        assertTrue(handles.bytesRead <= 2)
        assertEquals(0, handles.active)
        handles.onRead = {}
        var checks = 0
        try {
            adapter.observe(source.toString(), 128L * 1024) { if (++checks >= 7) throw CancellationException("fixture") }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, handles.active)
    }

    @Test fun revocationAndSameSizeReplacementNeverProduceTrustedSuccess() {
        val root = root(); val source = Files.write(root.resolve("Download/data"), byteArrayOf(1, 2))
        val handles = Handles(); var permission = true
        val adapter = AndroidOperationFiles(RootedReadInspector(root, handles, { permission }))
        handles.onRead = { permission = false }
        try { adapter.observe(source.toString(), 2); fail() } catch (_: OperationFailure) { }
        assertEquals(0, handles.active)
        permission = true
        var changed = false
        handles.onRead = {
            if (!changed) { changed = true; Files.move(source, source.resolveSibling("old")); Files.write(source, byteArrayOf(3, 4)) }
        }
        assertEquals(Observation.Unverifiable, adapter.observe(source.toString(), 2))
        assertArrayEquals(byteArrayOf(3, 4), Files.readAllBytes(source))
        assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(source.resolveSibling("old")))
        assertEquals(0, handles.active)
    }
}

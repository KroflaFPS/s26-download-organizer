package com.kroflafps.s26downloadorganizer.diagnostic

import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual isolated fixture I/O around the shared engine. NOT an Android /proc/FUSE acceptance test. */
internal class FixtureDirectories(private val primary: Path) : TestDirectoryHandles {
    override val input = primary.resolve(DiagnosticPolicy.INPUT)
    override val output = primary.resolve(DiagnosticPolicy.OUTPUT)
    var writeHook: (Path) -> Unit = {}
    var createHook: (String) -> Unit = {}
    var syncHook: () -> Unit = {}
    var permission = true
    var bytesWritten = 0L
    private val roots = listOf(primary, input.parent, input, output.parent, output).associateWith { attrs(it).fileKey().toString() }
    override fun validate() {
        check(permission)
        roots.forEach { (path, key) -> require(attrs(path).isDirectory && !Files.isSymbolicLink(path) && attrs(path).fileKey().toString() == key) }
    }
    override fun source(name: String): TestFileHandle { DiagnosticPolicy.requireName(name); return Channel(input.resolve(name), false) }
    override fun outputRead(name: String): TestFileHandle = Channel(output.resolve(component(name)), false)
    override fun exclusiveOutput(name: String): TestFileHandle { validate(); createHook(name); return Channel(output.resolve(component(name)), true) }
    override fun requireEntry(name: String, identity: String, source: Boolean) {
        validate(); val path = (if (source) input else output).resolve(component(name)); val a = attrs(path)
        require(a.isRegularFile && a.fileKey().toString() == identity)
    }
    override fun syncOutput() { validate(); syncHook(); FileChannel.open(output, StandardOpenOption.READ).use { it.force(true) } }
    override fun close() { }
    inner class Channel(private val path: Path, fresh: Boolean) : TestFileHandle {
        private val channel = if (fresh) FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            else FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        init { require(attrs(path).isRegularFile) }
        override fun metadata(): TestMetadata {
            val a = attrs(path)
            return TestMetadata(a.fileKey().toString(), a.size(), "${a.lastModifiedTime()}|${Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS)}")
        }
        override fun rewind() { channel.position(0) }
        override fun read(buffer: ByteArray, count: Int) = channel.read(ByteBuffer.wrap(buffer, 0, count))
        override fun write(buffer: ByteArray, count: Int) {
            writeHook(path)
            val data = ByteBuffer.wrap(buffer, 0, count)
            while (data.hasRemaining()) { val n = channel.write(data); require(n > 0); bytesWritten += n }
        }
        override fun sync() { syncHook(); channel.force(true) }
        override fun close() = channel.close()
    }
    private fun component(name: String): String { require(name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name); return name }
    private fun attrs(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
}
class MemoryDiagnosticJournal : DiagnosticJournal {
    val rows = linkedMapOf<String, CopyRecord>()
    val events = mutableListOf<CopyRecord>()
    var beforeSave: (CopyRecord) -> Unit = {}
    var afterSave: (CopyRecord) -> Unit = {}
    var beforeCreate: () -> Unit = {}
    private var cursor: String? = null
    override suspend fun get(id: String): CopyRecord? = synchronized(this) { rows[id] }
    override suspend fun create(record: CopyRecord): Boolean = synchronized(this) {
        beforeCreate(); record.validate()
        if (rows.containsKey(record.id) || rows.values.any { it.phase != CopyPhase.COPY_ONLY && it.sourcePath == record.sourcePath }) false
        else { rows[record.id] = record; events.add(record); true }
    }
    override suspend fun save(record: CopyRecord) = synchronized(this) {
        beforeSave(record); record.successor(requireNotNull(rows[record.id])); rows[record.id] = record; events.add(record); afterSave(record)
    }
    override suspend fun page(): List<CopyRecord> = synchronized(this) {
        val pending = rows.values.filter { it.phase != CopyPhase.COPY_ONLY }.sortedBy { it.id }
        pending.filter { cursor == null || it.id > requireNotNull(cursor) }.take(20).ifEmpty { pending.take(20) }
    }
    override suspend fun advance(id: String) { cursor = id }
}
class DiagnosticCopyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun primary(): Path = temporary.newFolder().toPath().also {
        Files.createDirectories(it.resolve(DiagnosticPolicy.INPUT)); Files.createDirectories(it.resolve(DiagnosticPolicy.OUTPUT))
    }
    private fun fixture(root: Path, empty: Boolean = false): Path = Files.write(root.resolve(DiagnosticPolicy.INPUT).resolve(if (empty) "s26-test-empty.bin" else "s26-test-1MiB.bin"), if (empty) byteArrayOf() else ByteArray(1024 * 1024) { (it % 251).toByte() })
    private fun coordinator(root: Path, journal: DiagnosticJournal, configure: (FixtureDirectories) -> Unit = {}) = DiagnosticCopyCoordinator(
        { DiagnosticIo(FixtureDirectories(root).also(configure)) }, journal, { true }, { true })
    private suspend fun failure(block: suspend () -> Unit) { try { block(); fail("Expected refusal") } catch (_: Exception) { } }
    private fun outputs(root: Path) = Files.list(root.resolve(DiagnosticPolicy.OUTPUT)).use { it.toList() }

    @Test fun zeroAndMediumFixtureCopiesPreserveExactSourceAndDestinationBytes() = runBlocking {
        for (empty in listOf(false, true)) {
            val root = primary(); val source = fixture(root, empty); val original = Files.readAllBytes(source); val stamp = Files.getLastModifiedTime(source)
            val journal = MemoryDiagnosticJournal(); val engine = coordinator(root, journal)
            val row = engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true)
            assertEquals(CopyPhase.COPY_ONLY, row.phase)
            assertTrue(row.stageVerified && row.finalVerified && row.fileSynced && row.directorySynced)
            assertArrayEquals(original, Files.readAllBytes(source)); assertEquals(stamp, Files.getLastModifiedTime(source))
            assertArrayEquals(original, Files.readAllBytes(Path.of(row.stagePath)))
            assertArrayEquals(original, Files.readAllBytes(Path.of(requireNotNull(row.finalPath))))
            assertEquals(row, journal.rows[row.id]); assertFalse(OperationPolicy.MUTATIONS_ENABLED)
            assertEquals(row, engine.copy(row.id, row.name, true)); assertEquals(2, outputs(root).size)
            assertEquals(0, engine.recover()); assertEquals(2, outputs(root).size)
        }
    }
    @Test fun collisionsPreserveEveryExistingByteAndNumberTheNewFinal() = runBlocking {
        val root = primary(); val source = fixture(root)
        val output = root.resolve(DiagnosticPolicy.OUTPUT)
        val first = Files.write(output.resolve(source.fileName), byteArrayOf(17))
        val second = Files.write(output.resolve("s26-test-1MiB (1).bin"), byteArrayOf(19))
        val row = coordinator(root, MemoryDiagnosticJournal()).copy(UUID.randomUUID().toString(), source.fileName.toString(), true)
        assertEquals("s26-test-1MiB (2).bin", Path.of(requireNotNull(row.finalPath)).fileName.toString())
        assertArrayEquals(byteArrayOf(17), Files.readAllBytes(first)); assertArrayEquals(byteArrayOf(19), Files.readAllBytes(second))
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(Path.of(row.finalPath)))
    }
    @Test fun concurrentExclusiveFinalAllocationHasExactlyOneWinner() = runBlocking {
        val root = primary(); val name = "s26-test-empty.bin"
        val winners = (0..7).map { async(Dispatchers.IO) {
            DiagnosticIo(FixtureDirectories(root)).use { try { it.createFinal(name); true } catch (_: FileAlreadyExistsException) { false } }
        } }.awaitAll()
        assertEquals(1, winners.count { it }); assertEquals(1, outputs(root).size)
        assertEquals(0L, Files.size(outputs(root).single()))
    }
    @Test fun arbitraryNamesPathsContentAndMissingConsentCannotCreateOutputs() = runBlocking {
        val root = primary(); fixture(root)
        val engine = coordinator(root, MemoryDiagnosticJournal())
        for (name in listOf("", "../s26-test-1MiB.bin", "/tmp/private", "real-download.pdf", "S26-test-1MiB.bin")) failure { engine.copy(UUID.randomUUID().toString(), name, true) }
        failure { engine.copy(UUID.randomUUID().toString(), "s26-test-1MiB.bin", false) }
        Files.write(root.resolve(DiagnosticPolicy.INPUT).resolve("s26-test-1MiB.bin"), ByteArray(1024 * 1024))
        failure { engine.copy(UUID.randomUUID().toString(), "s26-test-1MiB.bin", true) }
        assertTrue(outputs(root).isEmpty())
    }
    @Test fun sourceAndParentSymlinksAndDestinationSymlinkCannotBeFollowed() = runBlocking {
        val root = primary(); val outside = Files.write(temporary.newFile().toPath(), byteArrayOf(7))
        val source = root.resolve(DiagnosticPolicy.INPUT).resolve("s26-test-empty.bin")
        Files.createSymbolicLink(source, outside)
        failure { coordinator(root, MemoryDiagnosticJournal()).copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
        assertTrue(outputs(root).isEmpty()); assertArrayEquals(byteArrayOf(7), Files.readAllBytes(outside))
        val root2 = primary(); val source2 = fixture(root2)
        val out = root2.resolve(DiagnosticPolicy.OUTPUT)
        val link = Files.createSymbolicLink(out.resolve(source2.fileName), outside)
        val row = coordinator(root2, MemoryDiagnosticJournal()).copy(UUID.randomUUID().toString(), source2.fileName.toString(), true)
        assertTrue(Files.isSymbolicLink(link)); assertArrayEquals(byteArrayOf(7), Files.readAllBytes(outside))
        assertEquals("s26-test-1MiB (1).bin", Path.of(requireNotNull(row.finalPath)).fileName.toString())
        val root3 = primary(); val parent = root3.resolve(DiagnosticPolicy.INPUT); Files.move(parent, parent.resolveSibling("moved")); Files.createSymbolicLink(parent, parent.resolveSibling("moved"))
        failure { coordinator(root3, MemoryDiagnosticJournal()).inspect() }; assertTrue(outputs(root3).isEmpty())
    }
    @Test fun stageAllocationFailureAndPartialWriteNeverReportSuccess() = runBlocking {
        for (kind in listOf("stage", "full", "permission", "cancel", "sync", "verify", "source")) {
            val root = primary(); val source = fixture(root); val original = Files.readAllBytes(source)
            val journal = MemoryDiagnosticJournal()
            val engine = coordinator(root, journal) { dirs ->
                if (kind == "stage") dirs.createHook = { throw java.io.IOException("exclusive staging unavailable") }
                if (kind == "sync") dirs.syncHook = { throw java.io.IOException("sync unsupported") }
                if (kind == "verify") dirs.syncHook = { Files.write(outputs(root).single(), byteArrayOf(100), StandardOpenOption.APPEND) }
                var once = false
                dirs.writeHook = { path ->
                    if (!once) { once = true
                        when (kind) {
                            "full" -> throw java.io.IOException("ENOSPC")
                            "permission" -> { dirs.permission = false; throw SecurityException("revoked") }
                            "cancel" -> throw CancellationException("fixture cancellation")
                            "source" -> Files.write(source, ByteArray(1024 * 1024) { 7 })
                        }
                    }
                }
            }
            failure { engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
            assertTrue(kind, journal.rows.values.none { it.phase == CopyPhase.COPY_ONLY })
            assertTrue(kind, Files.exists(source))
            if (kind != "source") assertArrayEquals(kind, original, Files.readAllBytes(source))
            else assertArrayEquals(ByteArray(1024 * 1024) { 7 }, Files.readAllBytes(source))
            assertEquals(CopyPhase.RECOVERY_REQUIRED, journal.rows.values.single().phase)
        }
    }
    @Test fun interruptedTransitionsAndAcknowledgementLossRemainRecoverableWithoutRetries() = runBlocking {
        for (phase in CopyPhase.entries.filter { it !in setOf(CopyPhase.STAGE_ALLOCATING, CopyPhase.RECOVERY_REQUIRED) }) {
            for (after in listOf(false, true)) {
                val root = primary(); val source = fixture(root); val original = Files.readAllBytes(source)
                val journal = MemoryDiagnosticJournal(); var failed = false
                val inject: (CopyRecord) -> Unit = { row -> if (!failed && row.phase == phase) { failed = true; throw java.io.IOException("journal fault") } }
                if (after) journal.afterSave = inject else journal.beforeSave = inject
                val engine = coordinator(root, journal)
                failure { engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
                val before = outputs(root).associateWith { Files.readAllBytes(it) }
                journal.beforeSave = {}; journal.afterSave = {}
                engine.recover(); val once = journal.rows.toMap(); engine.recover()
                assertEquals(once, journal.rows); assertEquals(before.keys, outputs(root).toSet())
                before.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(path)) }
                assertArrayEquals(original, Files.readAllBytes(source))
            }
        }
    }
    @Test fun journalCreationFailureAndDisabledCapabilityStopBeforeMutation() = runBlocking {
        val root = primary(); val source = fixture(root)
        val journal = MemoryDiagnosticJournal().apply { beforeCreate = { throw java.io.IOException("db unavailable") } }
        failure { coordinator(root, journal).copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
        assertTrue(outputs(root).isEmpty()); assertTrue(journal.rows.isEmpty())
        val disabled = DiagnosticCopyCoordinator({ error("Must not open") }, journal, { false }, { true })
        failure { disabled.copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
        assertTrue(outputs(root).isEmpty())
    }
    @Test fun occupiedStageIsNeverAdoptedAndPermissionDenialNeverOpensStorage() = runBlocking {
        val root = primary(); val source = fixture(root); val id = UUID.randomUUID().toString()
        val stage = Files.write(root.resolve(DiagnosticPolicy.OUTPUT).resolve(".s26-diagnostic-$id.part"), byteArrayOf(9, 8))
        val journal = MemoryDiagnosticJournal()
        failure { coordinator(root, journal).copy(id, source.fileName.toString(), true) }
        assertArrayEquals(byteArrayOf(9, 8), Files.readAllBytes(stage))
        assertTrue(Files.exists(source)); assertEquals(1, outputs(root).size)
        assertEquals(CopyPhase.RECOVERY_REQUIRED, journal.rows[id]?.phase)
        val denied = DiagnosticCopyCoordinator({ error("Must not open any file") }, journal, { true }, { false })
        failure { denied.inspect() }; failure { denied.copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
        assertArrayEquals(byteArrayOf(9, 8), Files.readAllBytes(stage))
    }

    @Test fun replacedStageOccupantIsNeverWrittenThroughItsPath() = runBlocking {
        val root = primary(); val source = fixture(root); val original = Files.readAllBytes(source)
        var changed: Path? = null
        val journal = MemoryDiagnosticJournal()
        val engine = coordinator(root, journal) { dirs ->
            dirs.writeHook = { path -> if (changed == null) {
                changed = path
                Files.move(path, path.resolveSibling("retained-original-stage"))
                Files.write(path, byteArrayOf(77))
            } }
        }
        failure { engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true) }
        assertArrayEquals(byteArrayOf(77), Files.readAllBytes(requireNotNull(changed)))
        assertArrayEquals(original, Files.readAllBytes(source))
        assertEquals(CopyPhase.RECOVERY_REQUIRED, journal.rows.values.single().phase)
    }

    @Test fun abruptTerminationBypassesFailureHandlerAndRecoveryNeverInfersSuccess() = runBlocking {
        class Terminated : Error()
        for (phase in CopyPhase.entries.filter { it !in setOf(CopyPhase.STAGE_ALLOCATING, CopyPhase.RECOVERY_REQUIRED) }) {
            val root = primary(); val source = fixture(root); val original = Files.readAllBytes(source)
            val journal = MemoryDiagnosticJournal()
            journal.afterSave = { if (it.phase == phase) throw Terminated() }
            val engine = coordinator(root, journal)
            try { engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true); fail() } catch (_: Terminated) { }
            val persisted = journal.rows.values.single()
            assertEquals(phase, persisted.phase) // No catch-handler transition ran after the termination.
            val before = outputs(root).associateWith { Files.readAllBytes(it) }
            journal.afterSave = {}
            engine.recover(); val once = journal.rows.toMap(); engine.recover()
            assertEquals(once, journal.rows)
            if (phase != CopyPhase.COPY_ONLY) assertEquals(CopyPhase.RECOVERY_REQUIRED, journal.rows.values.single().phase)
            before.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(path)) }
            assertEquals(before.keys, outputs(root).toSet()); assertArrayEquals(original, Files.readAllBytes(source))
        }
        val root = primary(); val source = fixture(root); val original = Files.readAllBytes(source)
        val journal = MemoryDiagnosticJournal(); var chunks = 0
        val engine = coordinator(root, journal) { dirs -> dirs.writeHook = { if (++chunks == 2) throw Terminated() } }
        try { engine.copy(UUID.randomUUID().toString(), source.fileName.toString(), true); fail() } catch (_: Terminated) { }
        assertEquals(CopyPhase.STAGE_COPYING, journal.rows.values.single().phase)
        val stage = Path.of(journal.rows.values.single().stagePath)
        assertEquals(64L * 1024, Files.size(stage))
        engine.recover(); engine.recover()
        assertEquals(64L * 1024, Files.size(stage)); assertArrayEquals(original, Files.readAllBytes(source))
        assertEquals(CopyPhase.RECOVERY_REQUIRED, journal.rows.values.single().phase)
    }

}

package com.kroflafps.s26downloadorganizer.automation

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.kroflafps.s26downloadorganizer.operations.MemoryJournal
import com.kroflafps.s26downloadorganizer.operations.OperationCoordinator
import com.kroflafps.s26downloadorganizer.operations.OperationResult
import com.kroflafps.s26downloadorganizer.operations.Phase
import com.kroflafps.s26downloadorganizer.domain.FileCategory
import com.kroflafps.s26downloadorganizer.storage.AndroidOperationFiles
import com.kroflafps.s26downloadorganizer.storage.ReadHandle
import com.kroflafps.s26downloadorganizer.storage.ReadHandleFactory
import com.kroflafps.s26downloadorganizer.storage.ReadMetadata
import com.kroflafps.s26downloadorganizer.storage.RootedReadInspector
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class SortWorkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
    }

    @Test fun schedulerEnqueuesPeriodicAndImmediateWorkAndCancelsWhenDisabled() {
        val workManager = WorkManager.getInstance(context)
        SortScheduler.sync(context, enabled = true, hasStoragePermission = true)
        val periodicInfos = workManager.getWorkInfosForUniqueWork("s26-auto-sort-periodic").get()
        assertEquals(1, periodicInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, periodicInfos.single().state)

        SortScheduler.enqueueSoon(context, delaySeconds = 15L)
        val soonInfos = workManager.getWorkInfosForUniqueWork("s26-auto-sort-soon").get()
        assertEquals(1, soonInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, soonInfos.single().state)

        SortScheduler.sync(context, enabled = false, hasStoragePermission = true)
        val afterDisable = workManager.getWorkInfosForUniqueWork("s26-auto-sort-periodic").get()
        assertEquals(WorkInfo.State.CANCELLED, afterDisable.single().state)
    }

    @Test fun workerFailsClosedCleanlyWhenNonOrganizerApplicationContext() = runBlocking {
        val worker = TestListenableWorkerBuilder<SortWorker>(context).build()
        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    private class LocalHandles : ReadHandleFactory {
        override fun canonicalPrimary(path: Path) = path.toRealPath()
        override fun openPrimary(path: Path): ReadHandle = Node(path)
        private fun meta(path: Path): ReadMetadata {
            val dev = (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number).toLong()
            val ino = (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number).toLong()
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return ReadMetadata(
                "$dev:$ino",
                a.size(),
                "${a.lastModifiedTime()}|${Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS)}",
                a.isRegularFile,
                a.isDirectory,
            )
        }
        inner class Node(private val path: Path) : ReadHandle {
            private val channel: FileChannel? = if (meta(path).regular) FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS) else null
            override fun metadata() = meta(path)
            override fun resolvedPath() = path
            override fun openDirectory(name: String): ReadHandle = Node(path.resolve(name))
            override fun openFile(name: String): ReadHandle = Node(path.resolve(name))
            override fun entryMetadata(name: String) = meta(path.resolve(name))
            override fun read(buffer: ByteArray, count: Int): Int = requireNotNull(channel).read(ByteBuffer.wrap(buffer, 0, count))
            override fun close() { channel?.close() }
        }
    }

    @Test fun androidOperationFilesPerformsRealMoveAndUndoInsideRobolectric() = runBlocking {
        val root = temporary.newFolder().toPath()
        val downloads = Files.createDirectory(root.resolve("Download"))
        val organized = root.resolve("Organized")
        val source = Files.write(downloads.resolve("report.pdf"), "%PDF-1.7\nreal-bytes".toByteArray())

        val inspector = RootedReadInspector(
            primary = root,
            factory = LocalHandles(),
            canRead = { true },
            downloadsDirectory = downloads,
            organizedDirectory = organized,
        )
        val files = AndroidOperationFiles(inspector) { true }
        val journal = MemoryJournal()
        val coordinator = OperationCoordinator(files, journal, { true })

        val moveResult = coordinator.move(UUID.randomUUID().toString(), source.toString(), true, FileCategory.DOCUMENTS)
        assertTrue("Expected Recorded move, got $moveResult", moveResult is OperationResult.Recorded)
        val moved = (moveResult as OperationResult.Recorded).record
        assertEquals("Move did not complete: $moved", Phase.COMPLETED, moved.phase)
        val destPath = Paths.get(requireNotNull(moved.destinationPath))
        assertEquals(organized.resolve("Documents/report.pdf"), destPath)
        assertFalse(Files.exists(source))
        assertFalse(Files.exists(Paths.get(moved.stagingPath)))
        assertArrayEquals("%PDF-1.7\nreal-bytes".toByteArray(), Files.readAllBytes(destPath))

        val undoResult = coordinator.undo(UUID.randomUUID().toString(), moved.id, acceptUniqueName = true)
        assertTrue("Expected Recorded undo, got $undoResult", undoResult is OperationResult.Recorded)
        assertEquals(Phase.COMPLETED, (undoResult as OperationResult.Recorded).record.phase)
        assertTrue(Files.exists(source))
        assertFalse(Files.exists(destPath))
    }
}

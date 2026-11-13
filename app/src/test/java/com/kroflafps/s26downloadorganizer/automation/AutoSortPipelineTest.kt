package com.kroflafps.s26downloadorganizer.automation

import com.kroflafps.s26downloadorganizer.classification.FileClassifier
import com.kroflafps.s26downloadorganizer.domain.EntryType
import com.kroflafps.s26downloadorganizer.domain.FileCategory
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.domain.InventoryResult
import com.kroflafps.s26downloadorganizer.domain.InventorySnapshot
import com.kroflafps.s26downloadorganizer.domain.DownloadsRepository
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.history.CandidateStore
import com.kroflafps.s26downloadorganizer.history.ObservedCandidate
import com.kroflafps.s26downloadorganizer.operations.FixtureFiles
import com.kroflafps.s26downloadorganizer.operations.MemoryJournal
import com.kroflafps.s26downloadorganizer.operations.OperationCoordinator
import com.kroflafps.s26downloadorganizer.operations.OperationResult
import com.kroflafps.s26downloadorganizer.operations.Phase
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoSortPipelineTest {
    @get:Rule val temporary = TemporaryFolder()

    private class MemoryCandidateStore : CandidateStore {
        val map = linkedMapOf<String, ObservedCandidate>()
        override suspend fun get(path: String) = map[path]
        override suspend fun all() = map.values.toList()
        override suspend fun upsert(candidate: ObservedCandidate) { map[candidate.path] = candidate }
        override suspend fun delete(path: String) { map.remove(path) }
    }

    private fun entry(name: String, path: String, size: Long?, mtime: Long?, type: EntryType = EntryType.FILE) =
        InventoryEntry(path, name, type, size, mtime)

    @Test fun readinessTrackerDefersChangingZeroByteAndInProgressFiles() = runBlocking {
        var now = 100_000L
        val store = MemoryCandidateStore()
        val tracker = DownloadReadinessTracker(
            store = store,
            settleDelayMillis = 15_000L,
            preexistingQuietMillis = 60_000L,
            zeroByteGraceMillis = 30_000L,
            clock = { now },
        )

        val settling = entry("recent.pdf", "/d/recent.pdf", 1024L, 99_000L)
        val zeroByte = entry("empty.txt", "/d/empty.txt", 0L, 10_000L)
        val oldReady = entry("old.jpg", "/d/old.jpg", 2048L, 20_000L)
        val tempPart = entry("video.mp4.crdownload", "/d/video.mp4.crdownload", 5000L, 10_000L)
        val folder = entry("Folder", "/d/Folder", null, 10_000L, EntryType.DIRECTORY)

        val first = tracker.evaluate(listOf(settling, zeroByte, oldReady, tempPart, folder))
        assertEquals(listOf(oldReady), first.ready)
        assertEquals(2, first.settlingCount)

        // Advance 10s; recent.pdf grows so its timer resets.
        now += 10_000L
        val grown = settling.copy(sizeBytes = 2048L, modifiedAtMillis = now)
        val second = tracker.evaluate(listOf(grown, zeroByte))
        assertTrue(second.ready.isEmpty())
        assertEquals(2, second.settlingCount)

        // Advance another 15s (now = 125_000): grown was stable for 15s -> ready; empty.txt stable for 25s (< 30s) -> still settling.
        now += 15_000L
        val third = tracker.evaluate(listOf(grown, zeroByte))
        assertEquals(listOf(grown), third.ready)
        assertEquals(1, third.settlingCount)

        // Advance 5s (now = 130_000): empty.txt stable for 30s -> ready.
        now += 5_000L
        val fourth = tracker.evaluate(listOf(zeroByte))
        assertEquals(listOf(zeroByte), fourth.ready)
        assertEquals(0, fourth.settlingCount)

        // Stale entries disappear from store when no longer in Downloads.
        val fifth = tracker.evaluate(emptyList())
        assertTrue(fifth.ready.isEmpty())
        assertTrue(store.map.isEmpty())
    }

    @Test fun autoSortEngineClassifiesCreatesCategorySubfoldersHandlesCollisionsAndUndo() = runBlocking {
        val root = temporary.newFolder().toPath()
        val files = FixtureFiles(root)
        val journal = MemoryJournal()
        val store = MemoryCandidateStore()
        val coordinator = OperationCoordinator(files, journal, { true })

        val pdf = Files.write(files.roots.downloads.resolve("invoice.pdf"), "%PDF-1.7\ncontent".toByteArray())
        val png = Files.write(files.roots.downloads.resolve("screenshot.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val mp3 = Files.write(files.roots.downloads.resolve("song.mp3"), "ID3\u0003\u0000audio".toByteArray())
        val apkBytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val apk = Files.write(files.roots.downloads.resolve("app.apk"), apkBytes)
        val zip = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("readme.txt"))
                z.write("hello".toByteArray())
                z.closeEntry()
            }
            out.toByteArray()
        }
        val archive = Files.write(files.roots.downloads.resolve("bundle.zip"), zip)
        val unknown = Files.write(files.roots.downloads.resolve("blob.xyz"), byteArrayOf(9, 8, 7))
        val partial = Files.write(files.roots.downloads.resolve("partial.crdownload"), byteArrayOf(1, 2, 3))

        // Pre-seed Organized/Documents/invoice.pdf to verify no-clobber collision renaming.
        val docsDir = Files.createDirectories(files.roots.organized.resolve("Documents"))
        val existingInvoice = Files.write(docsDir.resolve("invoice.pdf"), "existing".toByteArray())

        // Make all test files look older than preexistingQuietMillis.
        val oldTime = FileTime.fromMillis(1_000L)
        listOf(pdf, png, mp3, apk, archive, unknown, partial).forEach { Files.setLastModifiedTime(it, oldTime) }

        val repo = object : DownloadsRepository {
            override fun access() = StorageAccess.GRANTED
            override suspend fun scan(): InventoryResult {
                val entries = Files.list(files.roots.downloads).use { stream ->
                    stream.map { p ->
                        val name = p.fileName.toString()
                        InventoryEntry(p.toString(), name, EntryType.FILE, Files.size(p), Files.getLastModifiedTime(p).toMillis())
                    }.toList()
                }
                return InventoryResult.Success(InventorySnapshot(entries, 0))
            }
        }

        val notified = mutableListOf<AutoSortSummary>()
        val scanned = mutableListOf<Pair<String, String>>()
        val engine = AutoSortEngine(
            repository = repo,
            readiness = DownloadReadinessTracker(store, clock = { 200_000L }),
            coordinator = coordinator,
            classifyFile = { FileClassifier.classify(it) },
            onMovedPaths = { s, d -> scanned += s to d },
            onSummaryNotification = { notified += it },
        )

        val summary = engine.runOnce(forceImmediate = false)
        assertNull(summary.skippedReason)
        assertEquals(6, summary.movedCount)
        assertEquals(0, summary.failedCount)
        assertEquals(1, notified.size)
        assertEquals(6, scanned.size)

        // Verify category folder layout under Organized/.
        assertTrue(Files.exists(files.roots.organized.resolve("Documents/invoice (1).pdf")))
        assertArrayEquals("existing".toByteArray(), Files.readAllBytes(existingInvoice))
        assertTrue(Files.exists(files.roots.organized.resolve("Images/screenshot.png")))
        assertTrue(Files.exists(files.roots.organized.resolve("Audio/song.mp3")))
        assertTrue(Files.exists(files.roots.organized.resolve("Installers/app.apk")))
        assertTrue(Files.exists(files.roots.organized.resolve("Archives/bundle.zip")))
        assertTrue(Files.exists(files.roots.organized.resolve("Other/blob.xyz")))

        // Original files moved out of Downloads, while .crdownload stayed untouched.
        assertFalse(Files.exists(pdf))
        assertFalse(Files.exists(png))
        assertTrue(Files.exists(partial))

        // Running again should find nothing new and send no notification.
        val secondRun = engine.runOnce(forceImmediate = false)
        assertEquals(0, secondRun.movedCount)
        assertEquals(1, notified.size)

        // Undo one organized file from Organized/Images/ back to Download/.
        val pngRecord = journal.records.values.single { it.destinationPath?.endsWith("screenshot.png") == true }
        val undoResult = coordinator.undo(UUID.randomUUID().toString(), pngRecord.id, acceptUniqueName = true)
        assertTrue(undoResult is OperationResult.Recorded)
        assertEquals(Phase.COMPLETED, (undoResult as OperationResult.Recorded).record.phase)
        assertTrue(Files.exists(files.roots.downloads.resolve("screenshot.png")))
        assertFalse(Files.exists(files.roots.organized.resolve("Images/screenshot.png")))
    }

    @Test fun concurrentRunOnceIsSerializedWithoutDoubleMovesOrFailures() = runBlocking {
        val root = temporary.newFolder().toPath()
        val files = FixtureFiles(root)
        val journal = MemoryJournal()
        val store = MemoryCandidateStore()
        val coordinator = OperationCoordinator(files, journal, { true })
        val pdf = Files.write(files.roots.downloads.resolve("manual.pdf"), "%PDF-1.7\nmanual-content".toByteArray())
        Files.setLastModifiedTime(pdf, FileTime.fromMillis(1_000L))

        val repo = object : DownloadsRepository {
            override fun access() = StorageAccess.GRANTED
            override suspend fun scan(): InventoryResult {
                val entries = Files.list(files.roots.downloads).use { stream ->
                    stream.map { p ->
                        InventoryEntry(p.toString(), p.fileName.toString(), EntryType.FILE, Files.size(p), Files.getLastModifiedTime(p).toMillis())
                    }.toList()
                }
                return InventoryResult.Success(InventorySnapshot(entries, 0))
            }
        }
        val engine = AutoSortEngine(
            repository = repo,
            readiness = DownloadReadinessTracker(store, clock = { 200_000L }),
            coordinator = coordinator,
        )

        val first = async(Dispatchers.Default) { engine.runOnce(forceImmediate = false) }
        val second = async(Dispatchers.Default) { engine.runOnce(forceImmediate = false) }
        val summaries = listOf(first.await(), second.await())
        assertEquals(1, summaries.sumOf { it.movedCount })
        assertEquals(0, summaries.sumOf { it.failedCount })
        assertTrue(Files.exists(files.roots.organized.resolve("Documents/manual.pdf")))
        assertFalse(Files.exists(pdf))
    }
}

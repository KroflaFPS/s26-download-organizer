package com.kroflafps.s26downloadorganizer.storage

import com.kroflafps.s26downloadorganizer.domain.*
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadOnlyInventoryScannerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun source() = temporary.newFolder("Download").toPath()
    private fun exclusion(root: Path) = SourceExclusionPolicy(root.parent.resolve("Organized"))
    private fun snapshot(result: InventoryResult) = (result as InventoryResult.Success).snapshot

    @Test fun emptyDirectoryStaysEmptyAndDestinationIsNeverCreated() {
        val root = source()
        val before = attributes(root).lastModifiedTime()
        val result = snapshot(ReadOnlyInventoryScanner().scan(root, exclusion(root)))
        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.unavailableCount)
        assertEquals(before, attributes(root).lastModifiedTime())
        assertFalse(Files.exists(root.parent.resolve("Organized")))
    }

    @Test fun convertsOnlyImmediateMetadataIncludingHiddenUnicodeZeroByteAndDirectory() {
        val root = source()
        val file = Files.write(root.resolve("račun 你好 #1.PDF"), byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(file, FileTime.fromMillis(2000))
        val zero = Files.createFile(root.resolve(".hidden"))
        Files.setLastModifiedTime(zero, FileTime.fromMillis(1000))
        val folder = Files.createDirectory(root.resolve("folder.zip"))
        Files.createFile(folder.resolve("nested.txt"))
        val folderBefore = attributes(folder)
        val before = attributes(file)
        val entries = snapshot(ReadOnlyInventoryScanner().scan(root, exclusion(root))).entries
        assertEquals(setOf("račun 你好 #1.PDF", ".hidden", "folder.zip"), entries.map { it.name }.toSet())
        val regular = entries.single { it.id == file.toString() }
        assertEquals(EntryType.FILE, regular.type)
        assertEquals("PDF", regular.extension)
        assertEquals(3L, regular.sizeBytes)
        assertEquals(2000L, regular.modifiedAtMillis)
        assertEquals(0L, entries.single { it.name == ".hidden" }.sizeBytes)
        assertNull(entries.single { it.name == ".hidden" }.extension)
        val directory = entries.single { it.name == "folder.zip" }
        assertEquals(EntryType.DIRECTORY, directory.type)
        assertNull(directory.sizeBytes)
        assertNull(directory.extension)
        assertEquals(entries.sortedWith(inventoryOrder), entries)
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(file))
        assertEquals(before.lastModifiedTime(), attributes(file).lastModifiedTime())
        assertEquals(folderBefore.lastModifiedTime(), attributes(folder).lastModifiedTime())
    }

    @Test fun vanishedAndUnreadableEntriesDoNotCrashOrPretendDirectoryIsEmpty() {
        val root = source()
        val vanished = Files.createFile(root.resolve("vanished"))
        val denied = Files.createFile(root.resolve("denied.pdf"))
        val failed = Files.createFile(root.resolve("failed"))
        Files.createFile(root.resolve("good"))
        val scanner = ReadOnlyInventoryScanner(attributes = { path ->
            when (path) {
                vanished -> throw NoSuchFileException(path.toString())
                denied -> throw SecurityException("fixture denial")
                failed -> throw IOException("fixture metadata unavailable")
                else -> attributes(path)
            }
        })
        val result = snapshot(scanner.scan(root, exclusion(root)))
        assertEquals(3, result.unavailableCount)
        assertEquals(setOf("denied.pdf", "failed", "good"), result.entries.map { it.name }.toSet())
        val unknown = result.entries.single { it.name == "denied.pdf" }
        assertEquals(EntryType.UNAVAILABLE, unknown.type)
        assertNull(unknown.sizeBytes)
        assertNull(unknown.modifiedAtMillis)
        assertNull(unknown.extension)
    }

    @Test fun onlyDisappearingEntriesAreReportedAsPartialNotAsAnEmptySuccess() {
        val root = source()
        val gone = Files.createFile(root.resolve("gone"))
        val result = snapshot(ReadOnlyInventoryScanner(attributes = {
            if (it == gone) throw NoSuchFileException(it.toString()) else attributes(it)
        }).scan(root, exclusion(root)))
        assertTrue(result.entries.isEmpty())
        assertEquals(1, result.unavailableCount)
    }

    @Test fun rootPermissionAndListingErrorsAreExplicitFailures() {
        val root = source()
        for (exception in listOf(SecurityException("denied"), AccessDeniedException(root.toString()))) {
            val scanner = ReadOnlyInventoryScanner(openDirectory = { throw exception })
            assertEquals(InventoryResult.Failure(InventoryError.ACCESS_DENIED), scanner.scan(root, exclusion(root)))
        }
        assertEquals(
            InventoryResult.Failure(InventoryError.CANNOT_READ),
            ReadOnlyInventoryScanner(openDirectory = { throw IOException("I/O") }).scan(root, exclusion(root)),
        )
        assertEquals(
            InventoryResult.Failure(InventoryError.DOWNLOADS_MISSING),
            ReadOnlyInventoryScanner().scan(root.resolve("not-created"), exclusion(root)),
        )
    }

    @Test fun iterationFailureClosesStreamAndDoesNotPublishPartialSuccess() {
        val root = source()
        var closed = false
        val scanner = ReadOnlyInventoryScanner(openDirectory = {
            object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
                    override fun hasNext(): Boolean = throw DirectoryIteratorException(IOException("fixture"))
                    override fun next(): Path = error("not reached")
                    override fun remove(): Unit = error("no mutations")
                }
                override fun close() { closed = true }
            }
        })
        assertEquals(InventoryResult.Failure(InventoryError.CANNOT_READ), scanner.scan(root, exclusion(root)))
        assertTrue(closed)
    }

    @Test fun excludedRootAndDescendantsNeverBecomeCandidates() {
        val root = source()
        val destination = Files.createDirectory(root.resolve("Organized"))
        Files.createFile(destination.resolve("not-a-candidate"))
        Files.createFile(root.resolve("Organized-backup"))
        val policy = SourceExclusionPolicy(destination)
        assertEquals(listOf("Organized-backup"), snapshot(ReadOnlyInventoryScanner().scan(root, policy)).entries.map { it.name })
        val scanner = ReadOnlyInventoryScanner(attributes = { error("Excluded root must not be inspected") })
        assertTrue(snapshot(scanner.scan(destination.resolve("child"), policy)).entries.isEmpty())
    }

    @Test fun symbolicLinksAreListedWithoutFollowingTheirTargets() {
        val root = source()
        val outside = temporary.newFolder("outside").toPath()
        Files.createFile(outside.resolve("private.txt"))
        val link = Files.createSymbolicLink(root.resolve("alias"), outside)
        val read = mutableListOf<Path>()
        val result = snapshot(ReadOnlyInventoryScanner(attributes = { read.add(it); attributes(it) }).scan(root, exclusion(root)))
        assertEquals(listOf(root, link), read)
        assertEquals(EntryType.SYMBOLIC_LINK, result.entries.single().type)
        assertNull(result.entries.single().sizeBytes)
        assertEquals(InventoryResult.Failure(InventoryError.CANNOT_READ), ReadOnlyInventoryScanner().scan(link, exclusion(root)))
    }

    @Test fun cancellationIsPropagatedRatherThanConvertedToSuccessOrAnError() {
        val root = source()
        var cancelled = false
        try {
            ReadOnlyInventoryScanner().scan(root, exclusion(root)) { throw CancellationException("fixture") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun largeInventoryIncludesAllEntriesInDeterministicOrder() {
        val root = source()
        val names = (0 until 512).map { "file-${it.toString().padStart(4, '0')}.bin" }
        names.reversed().forEach {
            Files.setLastModifiedTime(Files.createFile(root.resolve(it)), FileTime.fromMillis(1000))
        }
        val scanner = ReadOnlyInventoryScanner()
        val first = snapshot(scanner.scan(root, exclusion(root)))
        assertEquals(names, first.entries.map { it.name })
        assertEquals(first, snapshot(scanner.scan(root, exclusion(root))))
    }

    @Test fun cancellationDuringEnumerationClosesDirectoryStream() {
        val root = source()
        Files.createFile(root.resolve("fixture"))
        var closed = false
        var checkpoints = 0
        val scanner = ReadOnlyInventoryScanner(openDirectory = {
            val delegate = Files.newDirectoryStream(it)
            object : DirectoryStream<Path> {
                override fun iterator() = delegate.iterator()
                override fun close() { closed = true; delegate.close() }
            }
        })
        try {
            scanner.scan(root, exclusion(root)) {
                if (++checkpoints == 2) throw CancellationException("fixture")
            }
            fail("Cancellation must escape the scanner")
        } catch (_: CancellationException) {
            assertTrue(closed)
        }
    }
}

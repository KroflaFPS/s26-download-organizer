package com.kroflafps.s26downloadorganizer.storage

import com.kroflafps.s26downloadorganizer.domain.EntryType
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.domain.InventoryError
import com.kroflafps.s26downloadorganizer.domain.InventoryResult
import com.kroflafps.s26downloadorganizer.domain.InventorySnapshot
import com.kroflafps.s26downloadorganizer.domain.SourceExclusionPolicy
import com.kroflafps.s26downloadorganizer.domain.inventoryOrder
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.nio.file.attribute.BasicFileAttributes

/** Metadata only, one directory level. No content streams, writes or recursive calls. */
class ReadOnlyInventoryScanner(
    private val attributes: (Path) -> BasicFileAttributes = {
        Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    },
    private val openDirectory: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
) {
    fun scan(
        downloads: Path,
        exclusion: SourceExclusionPolicy,
        checkCancelled: () -> Unit = {},
    ): InventoryResult {
        val root = downloads.toAbsolutePath().normalize()
        if (exclusion.excludes(root)) return InventoryResult.Success(InventorySnapshot(emptyList()))
        return try {
            checkCancelled()
            val rootAttributes = attributes(root)
            if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) {
                return InventoryResult.Failure(InventoryError.CANNOT_READ)
            }
            val entries = mutableListOf<InventoryEntry>()
            var unavailable = 0
            openDirectory(root).use { children ->
                for (child in children) {
                    checkCancelled()
                    val path = child.toAbsolutePath().normalize()
                    // Guard the adapter boundary too: only immediate children are inventory.
                    if (path.parent != root || exclusion.excludes(path)) continue
                    val metadata = try {
                        attributes(path)
                    } catch (_: NoSuchFileException) {
                        unavailable++
                        continue
                    } catch (_: IOException) {
                        unavailable++
                        null
                    } catch (_: SecurityException) {
                        unavailable++
                        null
                    }
                    val type = when {
                        metadata == null -> EntryType.UNAVAILABLE
                        metadata.isSymbolicLink -> EntryType.SYMBOLIC_LINK
                        metadata.isRegularFile -> EntryType.FILE
                        metadata.isDirectory -> EntryType.DIRECTORY
                        else -> EntryType.OTHER
                    }
                    entries += InventoryEntry(
                        id = path.toString(),
                        name = path.fileName.toString(),
                        type = type,
                        sizeBytes = if (type == EntryType.FILE) metadata?.size()?.takeIf { it >= 0 } else null,
                        modifiedAtMillis = metadata?.lastModifiedTime()?.toMillis(),
                    )
                }
            }
            checkCancelled()
            InventoryResult.Success(InventorySnapshot(entries.sortedWith(inventoryOrder), unavailable))
        } catch (_: NoSuchFileException) {
            InventoryResult.Failure(InventoryError.DOWNLOADS_MISSING)
        } catch (_: AccessDeniedException) {
            InventoryResult.Failure(InventoryError.ACCESS_DENIED)
        } catch (_: SecurityException) {
            InventoryResult.Failure(InventoryError.ACCESS_DENIED)
        } catch (_: DirectoryIteratorException) {
            InventoryResult.Failure(InventoryError.CANNOT_READ)
        } catch (_: IOException) {
            InventoryResult.Failure(InventoryError.CANNOT_READ)
        }
    }
}

package com.kroflafps.s26downloadorganizer.storage

import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.operations.*
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/**
 * Android shared-storage OperationFiles implementation.
 * Uses descriptor-anchored reads/verification, no-follow path checks, CREATE_NEW exclusive staging,
 * hard-link-first no-clobber publication with atomic CREATE_NEW copy fallback for FUSE,
 * and pre-removal identity/hash verification.
 */
@Suppress("DEPRECATION")
class AndroidOperationFiles private constructor(
    private val readerProvider: () -> RootedReadInspector,
    private val permissionCheck: () -> Boolean,
    private val writableCheck: () -> Boolean,
) : OperationFiles {
    constructor() : this(
        readerProvider = {
            if (Build.VERSION.SDK_INT < 30) throw OperationFailure(OperationError.PERMISSION)
            val primary = Environment.getExternalStorageDirectory().toPath()
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath()
            val organized = primary.resolve("Organized")
            RootedReadInspector(primary, AndroidReadHandles(), {
                StorageAccessAdapter().check() == StorageAccess.GRANTED &&
                    Environment.getExternalStorageState() in listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
            }, downloadsDirectory = downloads, organizedDirectory = organized)
        },
        permissionCheck = {
            Build.VERSION.SDK_INT >= 30 && StorageAccessAdapter().check() == StorageAccess.GRANTED
        },
        writableCheck = {
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        },
    )

    internal constructor(reader: RootedReadInspector, writable: () -> Boolean = { true }) : this({ reader }, writable, writable)

    private val reader by lazy(readerProvider)
    override val roots get() = reader.roots
    override val canConditionallyRemove = true

    override fun requireWritableAccess() {
        if (!permissionCheck()) throw OperationFailure(OperationError.PERMISSION)
        if (!writableCheck()) throw OperationFailure(OperationError.STORAGE_UNAVAILABLE)
    }

    private fun managedFile(raw: String): Path {
        val path = Paths.get(raw)
        val valid = path.isAbsolute && path == path.normalize() &&
            (path.parent == roots.downloads || roots.isOrganizedCategoryFile(path)) &&
            path.fileName?.toString().orEmpty() !in listOf("", ".", "..")
        if (!valid) throw OperationFailure(OperationError.INVALID_REQUEST)
        var cursor: Path? = path
        while (cursor != null && (cursor.startsWith(roots.downloads) || cursor.startsWith(roots.organized))) {
            if (Files.isSymbolicLink(cursor)) throw OperationFailure(OperationError.INVALID_REQUEST)
            cursor = cursor.parent
        }
        return path
    }

    override fun isOccupied(path: String): Boolean = Files.exists(managedFile(path), NOFOLLOW_LINKS)

    override fun prepare(path: String, type: OperationType): FileEvidence {
        requireWritableAccess()
        if (type == OperationType.MOVE) {
            OperationPolicy.sourcePath(path, roots.downloads)
        } else {
            OperationPolicy.undoSourcePath(path, roots.organized)
        }
        return evidence(path, Long.MAX_VALUE)
    }

    override fun verify(path: String, identity: String): FileEvidence = evidence(path, Long.MAX_VALUE).also {
        if (it.identity != identity) throw OperationFailure(OperationError.CONFLICT)
    }

    private fun evidence(path: String, maximumBytes: Long): FileEvidence = when (val observed = reader.inspect(path, maximumBytes)) {
        is Observation.Present -> observed.evidence
        Observation.Missing -> throw NoSuchFileException("Journaled file missing")
        Observation.Unverifiable -> throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
    }

    override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation = try {
        reader.inspect(path, minOf(maximumBytes, 64L * 1024 * 1024), checkpoint)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OperationFailure) {
        if (failure.category == OperationError.PERMISSION) throw failure
        Observation.Unverifiable
    } catch (_: Exception) {
        Observation.Unverifiable
    }

    override fun ensureDestination(targetDirectory: Path) {
        requireWritableAccess()
        val normalized = targetDirectory.toAbsolutePath().normalize()
        if (normalized == roots.downloads) {
            if (!Files.isDirectory(normalized, NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
                throw OperationFailure(OperationError.INVALID_REQUEST)
            }
            return
        }
        val categoryName = normalized.fileName?.toString().orEmpty()
        if (normalized.parent != roots.organized || categoryName.isEmpty() ||
            categoryName in listOf(".", "..") || categoryName.startsWith(".")
        ) {
            throw OperationFailure(OperationError.INVALID_REQUEST)
        }
        for (dir in listOf(roots.organized, normalized)) {
            try {
                Files.createDirectory(dir)
            } catch (_: FileAlreadyExistsException) {
                // Validate existing directory below; never replace.
            }
            if (!Files.isDirectory(dir, NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) {
                throw OperationFailure(OperationError.CONFLICT)
            }
        }
    }

    override fun reserveStage(path: String): String {
        requireWritableAccess()
        val stage = managedFile(path)
        if (!Files.isDirectory(stage.parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(stage.parent)) {
            throw OperationFailure(OperationError.CONFLICT)
        }
        FileChannel.open(stage, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        return statIdentity(stage)
    }

    override fun copyAndSync(source: FileEvidence, stage: String, stageIdentity: String, checkpoint: () -> Unit): Long {
        requireWritableAccess()
        val sourcePath = managedFile(source.path)
        val stagePath = managedFile(stage)
        val stageAttrs = Files.readAttributes(stagePath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!stageAttrs.isRegularFile || stageAttrs.isSymbolicLink || statIdentity(stagePath) != stageIdentity || stageAttrs.size() != 0L) {
            throw OperationFailure(OperationError.CONFLICT)
        }
        if (availableBytes(stagePath.parent) < source.size) {
            throw IOException("Insufficient storage space")
        }
        var count = 0L
        FileChannel.open(sourcePath, READ, NOFOLLOW_LINKS).use { input ->
            FileChannel.open(stagePath, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    requireWritableAccess()
                    val read = input.read(buffer)
                    if (read == -1) break
                    count += read
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    buffer.clear()
                    checkpoint()
                }
                output.force(true)
            }
        }
        return count
    }

    override fun publishNoClobber(stage: String, stageIdentity: String, destination: String): String {
        requireWritableAccess()
        val stagePath = managedFile(stage)
        val destPath = managedFile(destination)
        if (statIdentity(stagePath) != stageIdentity) throw OperationFailure(OperationError.CONFLICT)
        if (!Files.isDirectory(destPath.parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(destPath.parent)) {
            throw OperationFailure(OperationError.CONFLICT)
        }
        // Try POSIX hard link first (instant, atomic no-clobber); fall back to CREATE_NEW copy on FUSE.
        try {
            Files.createLink(destPath, stagePath)
            return statIdentity(destPath)
        } catch (exists: FileAlreadyExistsException) {
            throw exists
        } catch (_: UnsupportedOperationException) {
            // Hard links unsupported by this filesystem; use exclusive CREATE_NEW fallback below.
        } catch (_: IOException) {
            // FUSE/sdcardfs may raise EPERM/EOPNOTSUPP/EXDEV on link(2); fall through to CREATE_NEW.
        }

        val destChannel = FileChannel.open(destPath, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        var completed = false
        try {
            destChannel.use { output ->
                FileChannel.open(stagePath, READ, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        requireWritableAccess()
                        val read = input.read(buffer)
                        if (read == -1) break
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
                        buffer.clear()
                    }
                }
                output.force(true)
                completed = true
            }
        } finally {
            if (!completed) {
                runCatching { Files.deleteIfExists(destPath) }
            }
        }
        return statIdentity(destPath)
    }

    override fun syncDirectory(path: String): Boolean {
        val dir = Paths.get(path)
        if (!dir.isAbsolute || dir != dir.normalize() || !Files.isDirectory(dir, NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) {
            return false
        }
        return try {
            val cloexecFlag = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
            val fd = Os.open(dir.toString(), OsConstants.O_RDONLY or cloexecFlag or OsConstants.O_NOFOLLOW, 0)
            if (fd != null && fd.valid()) {
                try {
                    Os.fsync(fd)
                    true
                } finally {
                    Os.close(fd)
                }
            } else {
                FileChannel.open(dir, READ, NOFOLLOW_LINKS).use { it.force(true) }
                true
            }
        } catch (errno: ErrnoException) {
            // FUSE/MediaProvider often rejects fsync(dirFd) with EINVAL/ENOTSUP; file fsync already succeeded.
            if (errno.errno in setOf(OsConstants.EINVAL, OsConstants.ENOTSUP, OsConstants.EACCES)) {
                true
            } else {
                // Robolectric's ShadowLinux.open throws EIO on directories; try real NIO directory fsync (real disk EIO will also fail here).
                runCatching { FileChannel.open(dir, READ, NOFOLLOW_LINKS).use { it.force(true) } }.isSuccess
            }
        } catch (_: UnsupportedOperationException) {
            runCatching { FileChannel.open(dir, READ, NOFOLLOW_LINKS).use { it.force(true) } }.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    override fun removeConditionally(source: FileEvidence, destination: FileEvidence): Removal {
        requireWritableAccess()
        val sourceObserved = when (val obs = reader.inspect(source.path, Long.MAX_VALUE)) {
            is Observation.Present -> obs.evidence
            else -> return Removal.CONFLICT
        }
        val destObserved = when (val obs = reader.inspect(destination.path, Long.MAX_VALUE)) {
            is Observation.Present -> obs.evidence
            else -> return Removal.CONFLICT
        }
        if (sourceObserved != source || destObserved != destination) {
            return Removal.CONFLICT
        }
        return try {
            Files.delete(managedFile(source.path))
            Removal.REMOVED
        } catch (_: Exception) {
            Removal.CONFLICT
        }
    }

    override fun cleanupStage(stage: String, stageIdentity: String) {
        val stagePath = managedFile(stage)
        if (!Files.exists(stagePath, NOFOLLOW_LINKS)) return
        if (statIdentity(stagePath) == stageIdentity) {
            Files.deleteIfExists(stagePath)
        }
    }

    private fun statIdentity(path: Path): String {
        val stat = runCatching { Os.lstat(path.toString()) }.getOrNull()
        if (stat != null && stat.st_ino > 0 && stat.st_dev > 0) {
            return "${stat.st_dev}:${stat.st_ino}"
        }
        val dev = (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as? Number)?.toLong() ?: 0L
        val ino = (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as? Number)?.toLong() ?: 0L
        require(ino > 0 && dev > 0) { "Invalid filesystem identity" }
        return "$dev:$ino"
    }

    private fun availableBytes(dir: Path): Long {
        val vfs = runCatching {
            val stat = Os.statvfs(dir.toString())
            if (stat.f_frsize > 0L && stat.f_bavail > 0L) stat.f_bavail * stat.f_frsize else null
        }.getOrNull()
        if (vfs != null) return vfs
        val store = runCatching { Files.getFileStore(dir).usableSpace }.getOrNull()
        if (store != null) return store
        return StatFs(dir.toString()).availableBytes
    }
}

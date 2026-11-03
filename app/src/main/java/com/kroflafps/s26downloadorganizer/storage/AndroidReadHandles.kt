package com.kroflafps.s26downloadorganizer.storage

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.annotation.RequiresApi
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.io.IOException
import java.io.InterruptedIOException

/** Public Android APIs only. /proc/self/fd availability is checked at runtime, never assumed.
 * No path-only fallback and no writable open modes. FUSE/SELinux behavior needs S26 validation.
 */
@RequiresApi(30)
internal class AndroidReadHandles : ReadHandleFactory {
    override fun canonicalPrimary(path: Path): Path = path.toRealPath()
    override fun openPrimary(path: Path): ReadHandle = open(path.toString(), directory = true)

    private class Handle(private val descriptor: ParcelFileDescriptor) : PositionalReadHandle {
        private val anchor get() = "/proc/self/fd/${descriptor.fd}"
        override fun metadata(): ReadMetadata = metadata(Os.fstat(descriptor.fileDescriptor))
        override fun resolvedPath(): Path = Paths.get(Os.readlink(anchor))
        override fun openDirectory(name: String): ReadHandle = open(child(name), directory = true)
        override fun openFile(name: String): ReadHandle = open(child(name), directory = false)
        override fun entryMetadata(name: String): ReadMetadata = translated { metadata(Os.lstat(child(name))) }
        override fun read(buffer: ByteArray, count: Int): Int {
            val bytes = Os.read(descriptor.fileDescriptor, buffer, 0, count)
            return if (bytes == 0) -1 else bytes
        }
        override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int = try {
            Os.pread(descriptor.fileDescriptor, buffer, bufferOffset, count, offset)
        } catch (partial: InterruptedIOException) {
            // Preserve actual-byte accounting; the caller checks cancellation after every return.
            if (partial.bytesTransferred in 1..count) partial.bytesTransferred else throw IOException()
        } catch (error: ErrnoException) {
            if (error.errno in listOf(OsConstants.EACCES, OsConstants.EPERM)) throw SecurityException()
            throw IOException()
        }
        override fun close() = descriptor.close()
        private fun child(name: String): String {
            require(name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name && '\u0000' !in name)
            return "$anchor/$name"
        }
    }

    companion object {
        private fun metadata(stat: StructStat): ReadMetadata {
            // Refuse placeholders; unstable/reused identities still require all remaining evidence checks.
            require(stat.st_ino > 0 && stat.st_dev > 0)
            return ReadMetadata("${stat.st_dev}:${stat.st_ino}", stat.st_size,
                "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}|${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}",
                OsConstants.S_ISREG(stat.st_mode), OsConstants.S_ISDIR(stat.st_mode))
        }
        private fun open(path: String, directory: Boolean): ReadHandle = translated {
            val flags = OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK
            val raw = Os.open(path, flags, 0)
            try {
                val stat = Os.fstat(raw)
                require(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode))
                Handle(ParcelFileDescriptor.dup(raw))
            } finally { Os.close(raw) }
        }
        private fun <T> translated(block: () -> T): T = try { block() } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) throw NoSuchFileException("Journaled child unavailable")
            throw error
        }
    }
}

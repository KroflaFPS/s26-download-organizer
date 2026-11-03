package com.kroflafps.s26downloadorganizer.storage

import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.system.*
import androidx.annotation.RequiresApi
import com.kroflafps.s26downloadorganizer.BuildConfig
import com.kroflafps.s26downloadorganizer.diagnostic.*
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/** Diagnostic-only public API implementation. Never linked to the MOVE/UNDO adapter. */
object AndroidDiagnosticCopies {
    fun allowed(): Boolean = BuildConfig.DEBUG && BuildConfig.DIAGNOSTIC_COPY_ENABLED &&
        BuildConfig.APPLICATION_ID == DiagnosticPolicy.APPLICATION_ID && Build.VERSION.SDK_INT >= 30
    fun permission(): Boolean = Build.VERSION.SDK_INT >= 30 && StorageAccessAdapter().check() == StorageAccess.GRANTED &&
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    @Suppress("DEPRECATION")
    fun open(): DiagnosticSession {
        if (Build.VERSION.SDK_INT < 30) error("Android 11 or newer required")
        check(allowed()) { "Diagnostic copy is disabled on this build or Android version" }
        check(permission()) { "All Files Access and writable primary storage required" }
        return DiagnosticIo(Anchors(Environment.getExternalStorageDirectory().toPath()))
    }

    @RequiresApi(30)
    private class Descriptor(val pfd: ParcelFileDescriptor) : TestFileHandle {
        val fd get() = pfd.fileDescriptor
        val anchor get() = "/proc/self/fd/${pfd.fd}"
        fun stat(): StructStat = Os.fstat(fd)
        override fun metadata(): TestMetadata {
            val s = stat(); check(OsConstants.S_ISREG(s.st_mode)); return TestMetadata(identity(s), s.st_size, stamp(s))
        }
        override fun rewind() { Os.lseek(fd, 0, OsConstants.SEEK_SET) }
        override fun read(buffer: ByteArray, count: Int): Int {
            val countRead = try { Os.read(fd, buffer, 0, count) } catch (e: ErrnoException) { throw DiagnosticUnavailable("Descriptor read", e.errno) }
            return if (countRead == 0) -1 else countRead
        }
        override fun write(buffer: ByteArray, count: Int) {
            var offset = 0
            while (offset < count) {
                val n = try { Os.write(fd, buffer, offset, count - offset) } catch (e: ErrnoException) {
                    throw DiagnosticUnavailable(if (e.errno == OsConstants.ENOSPC) "Available storage space" else "Descriptor write", e.errno)
                }
                check(n > 0); offset += n
            }
        }
        override fun sync() {
            try { Os.fsync(fd) } catch (e: ErrnoException) {
                throw DiagnosticUnavailable(if (OsConstants.S_ISDIR(stat().st_mode)) "Directory fsync" else "File fsync", e.errno)
            }
        }
        override fun close() = pfd.close()
    }

    @RequiresApi(30)
    private class Anchors(primary: Path) : TestDirectoryHandles {
        private data class Directory(val handle: Descriptor, val path: Path, val identity: String, val parent: Descriptor?, val name: String?)
        private val directories = mutableListOf<Directory>()
        private val canonical = primary.toRealPath()
        override val input = primary.resolve(DiagnosticPolicy.INPUT)
        override val output = primary.resolve(DiagnosticPolicy.OUTPUT)
        private val inputDirectory: Descriptor
        private val outputDirectory: Descriptor
        init {
            try {
                check(primary.isAbsolute && primary == primary.normalize())
                val root = directory(canonical.toString(), canonical, null, null)
                val download = directory("${root.anchor}/Download", canonical.resolve("Download"), root, "Download")
                inputDirectory = directory("${download.anchor}/S26_Organizer_Test_Input", canonical.resolve(DiagnosticPolicy.INPUT), download, "S26_Organizer_Test_Input")
                val organized = directory("${root.anchor}/Organized", canonical.resolve("Organized"), root, "Organized")
                outputDirectory = directory("${organized.anchor}/S26_Organizer_Test_Output", canonical.resolve(DiagnosticPolicy.OUTPUT), organized, "S26_Organizer_Test_Output")
                validate()
                // Read-only feasibility preflight: fail BEFORE any file creation when directory fsync is unsupported.
                syncOutput()
            } catch (error: Exception) { close(); throw error }
        }
        private fun directory(raw: String, path: Path, parent: Descriptor?, name: String?): Descriptor {
            val handle = openDescriptor(raw, writableNew = false, directory = true)
            try { directories.add(Directory(handle, path, identity(handle.stat()), parent, name)) }
            catch (e: Exception) { handle.close(); throw e }
            return handle
        }
        override fun validate() {
            check(allowed() && permission()) { "Permission or storage unavailable" }
            check(canonical == canonical.toRealPath())
            for (directory in directories) {
                val resolved = try { Paths.get(Os.readlink(directory.handle.anchor)) } catch (e: ErrnoException) { throw DiagnosticUnavailable("/proc descriptor lookup", e.errno) }
                check(resolved == directory.path) { "Directory moved or /proc unavailable" }
                check(identity(directory.handle.stat()) == directory.identity)
                if (directory.parent != null) {
                    val entry = Os.lstat("${directory.parent.anchor}/${directory.name}")
                    check(OsConstants.S_ISDIR(entry.st_mode) && identity(entry) == directory.identity) { "Directory replaced" }
                }
            }
        }
        override fun source(name: String): TestFileHandle { DiagnosticPolicy.requireName(name); validate(); return openDescriptor(child(inputDirectory, name), false, false) }
        override fun outputRead(name: String): TestFileHandle { validate(); return openDescriptor(child(outputDirectory, name), false, false) }
        override fun exclusiveOutput(name: String): TestFileHandle {
            validate()
            return openDescriptor(child(outputDirectory, name), writableNew = true, directory = false)
        }
        override fun requireEntry(name: String, identity: String, source: Boolean) {
            validate()
            val directory = if (source) inputDirectory else outputDirectory
            val stat = Os.lstat(child(directory, name))
            check(OsConstants.S_ISREG(stat.st_mode) && AndroidDiagnosticCopies.identity(stat) == identity) { "File identity changed" }
        }
        override fun syncOutput() { validate(); outputDirectory.sync(); validate() }
        override fun close() {
            var error: Exception? = null
            for (directory in directories.asReversed()) try { directory.handle.close() } catch (e: Exception) { error = e }
            directories.clear(); error?.let { throw it }
        }
    }
    @RequiresApi(30)
    private fun child(directory: Descriptor, name: String): String {
        require(name.isNotEmpty() && name !in listOf(".", "..") && '/' !in name && '\u0000' !in name)
        return "${directory.anchor}/$name"
    }
    private fun identity(s: StructStat): String { check(s.st_dev > 0 && s.st_ino > 0); return "${s.st_dev}:${s.st_ino}" }
    @RequiresApi(30)
    private fun stamp(s: StructStat) = "${s.st_mtim.tv_sec}:${s.st_mtim.tv_nsec}|${s.st_ctim.tv_sec}:${s.st_ctim.tv_nsec}"
    @RequiresApi(30)
    private fun openDescriptor(path: String, writableNew: Boolean, directory: Boolean): Descriptor {
        val flags = OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or
            if (writableNew) OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL else OsConstants.O_RDONLY
        val raw = try { Os.open(path, flags, 0x180 /* owner read/write; 0600, not a hidden platform flag */) }
        catch (e: ErrnoException) {
            if (e.errno == OsConstants.EEXIST) throw FileAlreadyExistsException(path)
            if (e.errno == OsConstants.ENOENT) throw NoSuchFileException(path)
            throw DiagnosticUnavailable(if (writableNew) "Exclusive no-clobber creation" else "No-follow descriptor open", e.errno)
        }
        try {
            val s = Os.fstat(raw)
            check(if (directory) OsConstants.S_ISDIR(s.st_mode) else OsConstants.S_ISREG(s.st_mode)) { "Regular file or approved directory required" }
            if (writableNew) check(s.st_size == 0L)
            return Descriptor(ParcelFileDescriptor.dup(raw))
        } finally { Os.close(raw) }
    }
}

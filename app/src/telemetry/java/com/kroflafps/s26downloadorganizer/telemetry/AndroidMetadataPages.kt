package com.kroflafps.s26downloadorganizer.telemetry

import android.content.Context
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.system.*
import androidx.annotation.RequiresApi
import com.kroflafps.s26downloadorganizer.readiness.*
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/** No file content open/read/hash, no writes. All user paths stay local and out of event/export data. */
@RequiresApi(30)
class AndroidMetadataPages(private val context: Context, private val salt: String) : MetadataPages {
    @Suppress("DEPRECATION")
    override fun scan(task: DirectoryTask, check: () -> Unit): MetadataPage {
        require(task.depth in 0..4 && task.relative.length <= 1024)
        val parts = if (task.relative.isEmpty()) emptyList() else task.relative.split('/')
        require(parts.size == task.depth && parts.all { it.isNotEmpty() && it !in listOf(".", "..") && '\u0000' !in it })
        val handles = mutableListOf<ParcelFileDescriptor>()
        val primary = Environment.getExternalStorageDirectory().toPath().toRealPath()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        fun checkpoint() { check(); check(SystemClock.elapsedRealtime() < deadline) { "Page deadline" } }
        fun anchor(fd: ParcelFileDescriptor) = "/proc/self/fd/${fd.fd}"
        fun directory(path: String, expected: java.nio.file.Path): ParcelFileDescriptor {
            checkpoint()
            val raw = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            val pfd = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
            handles.add(pfd)
            require(OsConstants.S_ISDIR(Os.fstat(pfd.fileDescriptor).st_mode))
            require(Paths.get(Os.readlink(anchor(pfd))) == expected)
            return pfd
        }
        try {
            var expected = primary
            var parent = directory(primary.toString(), expected)
            for (name in listOf("Download") + parts) { expected = expected.resolve(name); parent = directory("${anchor(parent)}/$name", expected) }
            val identity = Os.fstat(parent.fileDescriptor).let { "${it.st_dev}:${it.st_ino}" }
            val names = mutableListOf<String>(); var warnings = 0
            Files.newDirectoryStream(Paths.get(anchor(parent))).use { stream ->
                for (entry in stream) {
                    checkpoint()
                    if (names.size == ScanWindow.ENUMERATION_LIMIT) { warnings++; break }
                    names.add(entry.fileName.toString())
                }
            }
            val window = ScanWindow.select(names, task.after)
            val selected = window.names
            val values = mutableListOf<MetadataEvidence>(); val children = mutableListOf<DirectoryTask>()
            for (name in selected) {
                checkpoint()
                require(name !in listOf(".", "..") && '/' !in name && '\u0000' !in name)
                val relative = (parts + name).joinToString("/")
                val key = MessageDigest.getInstance("SHA-256").digest((salt + relative).toByteArray()).joinToString("") { "%02x".format(it) }
                try {
                    val stat = Os.lstat("${anchor(parent)}/$name")
                    when {
                        OsConstants.S_ISLNK(stat.st_mode) -> warnings++ // Never follow a directory/file symlink.
                        OsConstants.S_ISDIR(stat.st_mode) -> if (task.depth < 4 && relative.length <= 1024) children.add(DirectoryTask(relative, task.depth + 1)) else warnings++
                        OsConstants.S_ISREG(stat.st_mode) -> values.add(MetadataEvidence(key,
                            if (stat.st_ino > 0 && stat.st_dev > 0) "${stat.st_dev}:${stat.st_ino}" else null,
                            stat.st_size.takeIf { it >= 0 }, stat.st_mtime * 1000,
                            ReadinessPolicy.temporary(name), if (values.size < 32) pending(parts, name, ::checkpoint) else null))
                        else -> warnings++
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: Exception) { values.add(MetadataEvidence(key, null, null, null, ReadinessPolicy.temporary(name), null)); warnings++ }
            }
            checkpoint()
            require(Paths.get(Os.readlink(anchor(parent))) == expected)
            require(Os.fstat(parent.fileDescriptor).let { "${it.st_dev}:${it.st_ino}" } == identity)
            val next = window.nextAfter?.let { task.copy(after = it) }
            // Overflows are explicit coverage gaps; no claim that capped/changing directories were fully observed.
            return MetadataPage(values, children, next, warnings)
        } finally { handles.asReversed().forEach { it.close() } }
    }
    private fun pending(parts: List<String>, name: String, check: () -> Unit): Boolean? {
        check()
        return try {
            val relative = "Download/" + if (parts.isEmpty()) "" else parts.joinToString("/", postfix = "/")
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND _display_name = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relative, name))
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 2)
            }
            val cancellation = CancellationSignal()
            val handler = Handler(Looper.getMainLooper()); val timeout = Runnable { cancellation.cancel() }; handler.postDelayed(timeout, 1000)
            try { context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.IS_PENDING), args, cancellation)?.use { cursor ->
                check()
                if (cursor.count != 1 || !cursor.moveToFirst() || cursor.isNull(0)) null else when (cursor.getInt(0)) { 0 -> false; 1 -> true; else -> null }
            } } finally { handler.removeCallbacks(timeout) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    fun hint(uris: Array<Uri>?): String {
        if (uris.isNullOrEmpty() || uris.size > 16) return "COALESCED_OR_UNKNOWN_URI_HINT"
        var outside = 0
        val deadline = SystemClock.elapsedRealtime() + 3000
        for (uri in uris) {
            if (SystemClock.elapsedRealtime() > deadline || !TelemetryGate.canRead() || uri.lastPathSegment?.toLongOrNull() == null || uri.authority != MediaStore.AUTHORITY) return "UNKNOWN_URI_HINT"
            try {
                val signal = CancellationSignal(); val handler = Handler(Looper.getMainLooper()); val timeout = Runnable { signal.cancel() }; handler.postDelayed(timeout, 1000)
                try { context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), Bundle().apply { putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1) }, signal)?.use {
                    if (!it.moveToFirst() || it.isNull(0)) return "UNKNOWN_URI_HINT"
                    if (it.getString(0).startsWith("Download/")) return "POSSIBLE_DOWNLOADS_URI_HINT"
                    outside++
                } ?: return "UNKNOWN_URI_HINT" } finally { handler.removeCallbacks(timeout) }
            } catch (_: Exception) { return "UNKNOWN_URI_HINT" }
        }
        return if (outside == uris.size) "IRRELEVANT_URI_HINT_SCAN_STILL_REQUIRED" else "UNKNOWN_URI_HINT"
    }
}

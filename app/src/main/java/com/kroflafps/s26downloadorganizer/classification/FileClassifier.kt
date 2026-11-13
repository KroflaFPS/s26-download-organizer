package com.kroflafps.s26downloadorganizer.classification

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kroflafps.s26downloadorganizer.domain.FileCategory
import com.kroflafps.s26downloadorganizer.domain.fileExtension
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.zip.ZipFile

object FileClassifier {
    private val installerExtensions = setOf("apk", "apks", "apkm", "xapk", "aab")
    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "epub", "mobi", "azw3",
        "txt", "md", "markdown", "csv", "tsv", "rtf",
        "json", "xml", "yaml", "yml", "html", "htm", "log", "tex", "ics", "vcf",
    )
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
        "avif", "svg", "ico", "tiff", "tif", "dng", "raw", "cr2", "nef", "arw", "psd",
    )
    private val audioExtensions = setOf(
        "mp3", "wav", "flac", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wma", "mid", "midi", "amr", "aiff", "alac",
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "3g2", "flv", "ts", "m2ts", "wmv", "mpg", "mpeg", "ogv",
    )
    private val archiveExtensions = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz4", "cab", "iso", "jar", "deb", "rpm",
    )

    fun classifyByName(fileName: String): FileCategory {
        val ext = fileExtension(fileName)?.lowercase(Locale.ROOT) ?: return FileCategory.OTHER
        return when (ext) {
            in installerExtensions -> FileCategory.INSTALLERS
            in documentExtensions -> FileCategory.DOCUMENTS
            in imageExtensions -> FileCategory.IMAGES
            in audioExtensions -> FileCategory.AUDIO
            in videoExtensions -> FileCategory.VIDEOS
            in archiveExtensions -> FileCategory.ARCHIVES
            else -> FileCategory.OTHER
        }
    }

    fun classify(path: Path, isAndroidApk: (Path) -> Boolean = { false }): FileCategory {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return classifyByName(path.fileName?.toString().orEmpty())
        }
        if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileCategory.OTHER

        val name = path.fileName?.toString().orEmpty()
        val ext = fileExtension(name)?.lowercase(Locale.ROOT)

        if (ext == "apk" && runCatching { isAndroidApk(path) }.getOrDefault(false)) {
            return FileCategory.INSTALLERS
        }

        val header = readHeader(path, 64)
        if (header.isNotEmpty()) {
            if (isZipHeader(header)) {
                return classifyZipContainer(path, ext, isAndroidApk)
            }
            classifyHeader(header)?.let { return it }
        }
        return classifyByName(name)
    }

    private fun readHeader(path: Path, maxBytes: Int): ByteArray = try {
        FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val buf = ByteBuffer.allocate(maxBytes)
            while (buf.hasRemaining()) {
                val n = channel.read(buf)
                if (n <= 0) break
            }
            buf.flip()
            ByteArray(buf.remaining()).also { buf.get(it) }
        }
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun classifyZipContainer(path: Path, ext: String?, isAndroidApk: (Path) -> Boolean): FileCategory {
        if (ext in installerExtensions && (ext != "apk" || runCatching { isAndroidApk(path) }.getOrDefault(true))) {
            return FileCategory.INSTALLERS
        }
        try {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements() && count < 256) {
                    count++
                    val entryName = entries.nextElement().name
                    when {
                        entryName == "AndroidManifest.xml" -> return FileCategory.INSTALLERS
                        entryName == "word/document.xml" ||
                            entryName == "xl/workbook.xml" ||
                            entryName == "ppt/presentation.xml" ||
                            entryName == "META-INF/container.xml" ||
                            entryName == "content.xml" -> return FileCategory.DOCUMENTS
                        entryName == "mimetype" -> {
                            val mime = zip.getEntry("mimetype")?.let { e ->
                                zip.getInputStream(e).use { s ->
                                    val buf = ByteArray(128)
                                    var total = 0
                                    while (total < buf.size) {
                                        val read = s.read(buf, total, buf.size - total)
                                        if (read <= 0) break
                                        total += read
                                    }
                                    buf.copyOf(total).toString(Charsets.UTF_8).trim()
                                }
                            }.orEmpty()
                            if (mime.startsWith("application/epub+zip") ||
                                mime.startsWith("application/vnd.oasis.opendocument.")
                            ) {
                                return FileCategory.DOCUMENTS
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to extension check / Archives fallback.
        }
        return when (ext) {
            in installerExtensions -> FileCategory.INSTALLERS
            in documentExtensions -> FileCategory.DOCUMENTS
            else -> FileCategory.ARCHIVES
        }
    }

    private fun classifyHeader(b: ByteArray): FileCategory? {
        if (startsWith(b, 0x25, 0x50, 0x44, 0x46, 0x2D)) return FileCategory.DOCUMENTS // %PDF-
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) return FileCategory.IMAGES // JPEG
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return FileCategory.IMAGES // PNG
        if (startsWithAscii(b, "GIF87a") || startsWithAscii(b, "GIF89a")) return FileCategory.IMAGES
        if (startsWithAscii(b, "BM") && b.size >= 6) return FileCategory.IMAGES
        if (startsWith(b, 0x49, 0x49, 0x2A, 0x00) || startsWith(b, 0x4D, 0x4D, 0x00, 0x2A)) return FileCategory.IMAGES // TIFF

        if (startsWithAscii(b, "RIFF") && b.size >= 12) {
            val fourCc = String(b, 8, 4, Charsets.US_ASCII)
            return when (fourCc) {
                "WEBP" -> FileCategory.IMAGES
                "WAVE" -> FileCategory.AUDIO
                "AVI " -> FileCategory.VIDEOS
                else -> null
            }
        }

        if (b.size >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        ) {
            val brand = String(b, 8, 4, Charsets.US_ASCII)
            return when (brand) {
                "heic", "heix", "mif1", "msf1", "avif", "avis" -> FileCategory.IMAGES
                "M4A ", "M4B ", "F4A " -> FileCategory.AUDIO
                "mp41", "mp42", "isom", "iso2", "M4V ", "qt  ", "3gp4", "3gp5", "3g2a" -> FileCategory.VIDEOS
                else -> FileCategory.VIDEOS
            }
        }

        if (startsWithAscii(b, "ID3")) return FileCategory.AUDIO
        if (b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) {
            // Distinguish MP3 frame sync (0xFF 0xFB/0xF3/0xF2/0xFA) vs AAC ADTS (0xFF 0xF1/0xF9)
            val second = b[1].toInt() and 0xFF
            if (second in setOf(0xFB, 0xFA, 0xF3, 0xF2, 0xF1, 0xF9)) return FileCategory.AUDIO
        }
        if (startsWithAscii(b, "fLaC") || startsWithAscii(b, "OggS")) return FileCategory.AUDIO
        if (startsWith(b, 0x1A, 0x45, 0xDF, 0xA3)) return FileCategory.VIDEOS // MKV / WebM
        if (startsWithAscii(b, "FLV\u0001")) return FileCategory.VIDEOS

        if (startsWith(b, 0x1F, 0x8B) || // gzip
            startsWith(b, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || // 7z
            startsWithAscii(b, "Rar!") ||
            startsWith(b, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) || // xz
            startsWithAscii(b, "BZh") ||
            startsWith(b, 0x28, 0xB5, 0x2F, 0xFD) // zstd
        ) {
            return FileCategory.ARCHIVES
        }
        return null
    }

    private fun isZipHeader(b: ByteArray): Boolean =
        startsWith(b, 0x50, 0x4B, 0x03, 0x04) || startsWith(b, 0x50, 0x4B, 0x05, 0x06)

    // Override classifyHeader cleanly for ZIP detection without sentinel ambiguity:
    internal fun isZip(path: Path): Boolean = isZipHeader(readHeader(path, 4))

    private fun startsWith(b: ByteArray, vararg bytes: Int): Boolean {
        if (b.size < bytes.size) return false
        for (i in bytes.indices) {
            if (b[i] != bytes[i].toByte()) return false
        }
        return true
    }

    private fun startsWithAscii(b: ByteArray, text: String): Boolean {
        if (b.size < text.length) return false
        for (i in text.indices) {
            if (b[i] != text[i].code.toByte()) return false
        }
        return true
    }
}

fun androidApkDetector(context: Context): (Path) -> Boolean = { path ->
    try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path.toString(), PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path.toString(), 0)
        }
        info != null
    } catch (_: Exception) {
        false
    }
}

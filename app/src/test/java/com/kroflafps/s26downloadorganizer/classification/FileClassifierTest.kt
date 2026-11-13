package com.kroflafps.s26downloadorganizer.classification

import com.kroflafps.s26downloadorganizer.domain.FileCategory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileClassifierTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun root(): Path = temporary.newFolder().toPath()

    private fun writeZip(path: Path, entries: Map<String, ByteArray>): Path {
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
        return Files.write(path, bytes)
    }

    @Test fun classifiesMagicHeadersEvenWhenExtensionIsMissingOrGeneric() {
        val dir = root()
        val cases = listOf(
            "doc.bin" to "%PDF-1.7\n".toByteArray() to FileCategory.DOCUMENTS,
            "photo" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) to FileCategory.IMAGES,
            "img.dat" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) to FileCategory.IMAGES,
            "anim" to "GIF89a...".toByteArray() to FileCategory.IMAGES,
            "pic.bin" to "RIFF\u0010\u0000\u0000\u0000WEBP".toByteArray() to FileCategory.IMAGES,
            "heic.bin" to byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte()) to FileCategory.IMAGES,
            "sound.dat" to "RIFF\u0010\u0000\u0000\u0000WAVE".toByteArray() to FileCategory.AUDIO,
            "track.bin" to "ID3\u0003\u0000".toByteArray() to FileCategory.AUDIO,
            "lossless" to "fLaC\u0000".toByteArray() to FileCategory.AUDIO,
            "ogg.bin" to "OggS\u0000".toByteArray() to FileCategory.AUDIO,
            "m4a.bin" to byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte()) to FileCategory.AUDIO,
            "clip.dat" to byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()) to FileCategory.VIDEOS,
            "mkv.bin" to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) to FileCategory.VIDEOS,
            "avi.bin" to "RIFF\u0010\u0000\u0000\u0000AVI ".toByteArray() to FileCategory.VIDEOS,
            "gz.bin" to byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00) to FileCategory.ARCHIVES,
            "7z.bin" to byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) to FileCategory.ARCHIVES,
            "rar.bin" to "Rar!\u001A\u0007\u0000".toByteArray() to FileCategory.ARCHIVES,
            "zst.bin" to byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) to FileCategory.ARCHIVES,
        )
        for ((pair, expected) in cases) {
            val (name, bytes) = pair
            val file = Files.write(dir.resolve(name), bytes)
            assertEquals("Failed for $name", expected, FileClassifier.classify(file))
        }
    }

    @Test fun inspectsZipContainersForApksDocumentsAndArchives() {
        val dir = root()
        val apkInsideZip = writeZip(dir.resolve("package.zip"), mapOf("AndroidManifest.xml" to byteArrayOf(1, 2, 3)))
        assertEquals(FileCategory.INSTALLERS, FileClassifier.classify(apkInsideZip))

        val docx = writeZip(dir.resolve("report.docx"), mapOf("word/document.xml" to "<w:document/>".toByteArray()))
        assertEquals(FileCategory.DOCUMENTS, FileClassifier.classify(docx))

        val xlsx = writeZip(dir.resolve("sheet.zip"), mapOf("xl/workbook.xml" to "<workbook/>".toByteArray()))
        assertEquals(FileCategory.DOCUMENTS, FileClassifier.classify(xlsx))

        val epub = writeZip(dir.resolve("book.bin"), mapOf("mimetype" to "application/epub+zip".toByteArray()))
        assertEquals(FileCategory.DOCUMENTS, FileClassifier.classify(epub))

        val plainZip = writeZip(dir.resolve("bundle.zip"), mapOf("notes.txt" to "hello".toByteArray()))
        assertEquals(FileCategory.ARCHIVES, FileClassifier.classify(plainZip))

        val customApk = Files.write(dir.resolve("app.apk"), byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        assertEquals(FileCategory.INSTALLERS, FileClassifier.classify(customApk, isAndroidApk = { true }))
    }

    @Test fun usesExtensionFallbackAndSafelyDefaultsUnknownOrEmptyToOther() {
        val dir = root()
        assertEquals(FileCategory.DOCUMENTS, FileClassifier.classify(Files.write(dir.resolve("notes.md"), "# hi".toByteArray())))
        assertEquals(FileCategory.DOCUMENTS, FileClassifier.classify(Files.write(dir.resolve("data.json"), "{}".toByteArray())))
        assertEquals(FileCategory.IMAGES, FileClassifier.classify(Files.write(dir.resolve("icon.svg"), "<svg/>".toByteArray())))
        assertEquals(FileCategory.ARCHIVES, FileClassifier.classify(Files.write(dir.resolve("disk.iso"), byteArrayOf(1, 2, 3))))
        assertEquals(FileCategory.OTHER, FileClassifier.classify(Files.write(dir.resolve("empty.bin"), byteArrayOf())))
        assertEquals(FileCategory.OTHER, FileClassifier.classify(Files.write(dir.resolve("random.xyz"), "opaque".toByteArray())))

        val target = Files.write(dir.resolve("real.pdf"), "%PDF-1.4".toByteArray())
        val link = Files.createSymbolicLink(dir.resolve("alias.pdf"), target)
        assertEquals(FileCategory.OTHER, FileClassifier.classify(link))
    }
}

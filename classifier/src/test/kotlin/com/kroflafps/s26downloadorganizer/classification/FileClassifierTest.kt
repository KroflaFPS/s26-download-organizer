package com.kroflafps.s26downloadorganizer.classification

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FileClassifierTest {
    private fun classify(name: String, bytes: ByteArray, policy: ClassificationPolicy = ClassificationPolicy()): Classification {
        val source = BytesSource(bytes, name)
        return FileClassifier(policy).classify(source).also {
            assertTrue(source.closed)
            assertEquals(source.actualBytes, it.usage.totalBytes)
            assertTrue(it.usage.headerBytes <= 4096)
            assertTrue(it.usage.containerBytes <= 131072)
        }
    }
    private fun candidate(name: String, bytes: ByteArray, destination: Destination) {
        val result = classify(name, bytes)
        assertEquals(result.toString(), Decision.CANDIDATE, result.decision)
        assertEquals(destination, result.destination)
        assertEquals(Safety.PROVISIONAL_ONLY, result.safety)
    }
    private fun held(name: String, bytes: ByteArray) = classify(name, bytes).also {
        assertEquals(it.toString(), Decision.REVIEW_HOLD, it.decision)
        assertNull(it.destination)
    }

    @Test fun allSevenCategoryFamiliesHaveUsefulSyntheticCandidates() {
        candidate("app.apk", zipFixture("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to byteArrayOf(2)), Destination.APK)
        candidate("report.pdf", pdfFixture(), Destination.PDF)
        candidate("photo.png", pngFixture(), Destination.PHOTOS)
        candidate("track.flac", "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + ByteArray(34), Destination.LOSSLESS)
        candidate("movie.avi", riffFixture("AVI "), Destination.VIDEO)
        candidate("archive.zip", zipFixture("notes.txt" to "hello".toByteArray()), Destination.ARCHIVE)
        candidate("ordinary.dat", byteArrayOf(0, 0x91.toByte(), 0, 7), Destination.OTHER)
    }

    @Test fun pdfAndApkDisguisesHaveVisibleConflicts() {
        val html = "<!DOCTYPE html><html>Error</html>".toByteArray()
        for (name in listOf("invoice.pdf", "download.apk")) {
            assertEquals(Confidence.INCOMPATIBLE, held(name, html).confidence)
            assertEquals(Reason.EXTENSION_MISMATCH, held(name, zipFixture("notes.txt" to byteArrayOf(1))).reason)
        }
        held("app.apk", pdfFixture())
        held("picture.jpg", riffFixture("WEBP", "VP8 "))
        held("image.png", pdfFixture())
        held("data.bin", zipFixture("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to byteArrayOf(2)))
    }

    @Test fun officeEpubAndApkRequireTheirMarkersAndDoNotAuthenticatePayloads() {
        val empty = ByteArray(0)
        candidate("word.docx", zipFixture("[Content_Types].xml" to empty, "word/document.xml" to empty), Destination.TEXT)
        candidate("sheet.xlsx", zipFixture("[Content_Types].xml" to empty, "xl/workbook.xml" to empty), Destination.SHEETS)
        candidate("slides.pptx", zipFixture("[Content_Types].xml" to empty, "ppt/presentation.xml" to empty), Destination.SLIDES)
        candidate("book.epub", zipFixture("mimetype" to "application/epub+zip".toByteArray(), "META-INF/container.xml" to empty, stored = true), Destination.EBOOKS)
        for (name in listOf("word.docx", "book.epub", "app.apk", "bundle.aab", "bundle.apks", "bundle.xapk", "bundle.apkm")) held(name, zipFixture("notes.txt" to empty))
        held("word.docx", zipFixture("word/document.xml" to empty))
        held("book.epub", zipFixture("META-INF/container.xml" to empty))
        held("app.apk", zipFixture("AndroidManifest.xml" to empty))
        held("bundle.aab", zipFixture("BundleConfig.pb" to empty, "base/manifest/AndroidManifest.xml" to empty))
        held("word.xlsx", byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))
    }

    @Test fun competingContainerMarkersNeverChooseOneCategory() {
        val bytes = zipFixture("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to byteArrayOf(1), "[Content_Types].xml" to byteArrayOf(1), "word/document.xml" to byteArrayOf(1))
        assertEquals(Reason.CONTAINER_AMBIGUOUS, held("app.apk", bytes).reason)
    }

    @Test fun tinyEmptyZipAndTraditionalDataDescriptorsAreSupported() {
        candidate("empty.zip", zipFixture(), Destination.ARCHIVE)
        candidate("descriptor.zip", zipFixture("a" to byteArrayOf(1, 2)), Destination.ARCHIVE)
        candidate("stored.zip", zipFixture("a" to byteArrayOf(1), stored = true), Destination.ARCHIVE)
        candidate("comment.zip", zipFixture("a" to byteArrayOf(1), comment = "x".repeat(65535)), Destination.ARCHIVE)
    }

    @Test fun zip64EncryptionAndUnsupportedVariantsRemainReviewable() {
        val bytes = zipFixture("a" to byteArrayOf(1), stored = true)
        val end = bytes.size - 22
        held("zip64.zip", bytes.copyOf().also { it.set16(end + 10, 65535) })
        held("multi.zip", bytes.copyOf().also { it.set16(end + 4, 1) })
        held("encrypted.zip", bytes.copyOf().also { it.set16(bytes.indexOfCentral() + 8, 1) })
        held("zip64.zip", bytes.copyOf().also { it.set32(end + 16, 0xffffffffL) })
        held("truncated.zip", bytes.copyOf(bytes.size - 1))
    }

    @Test fun maliciousOffsetsCountsLocalNamesAndTraversalCannotPass() {
        val bytes = zipFixture("a" to byteArrayOf(1), stored = true)
        val end = bytes.size - 22
        held("offset.zip", bytes.copyOf().also { it.set32(end + 16, 0xfffffff0L) })
        held("count.zip", bytes.copyOf().also { it.set16(end + 10, 2) })
        held("fake-local.zip", bytes.copyOf().also { it.set32(bytes.indexOfCentral() + 42, 1) })
        held("name.zip", bytes.copyOf().also { it[30] = 'b'.code.toByte() })
        held("traversal.zip", zipFixture("../escape" to byteArrayOf(1)))
        held("absolute.zip", zipFixture("/escape" to byteArrayOf(1)))
        held("separator.zip", zipFixture("a\\b" to byteArrayOf(1)))
        held("prepended.zip", byteArrayOf(80, 75, 3, 4) + bytes)
    }

    @Test fun directoryAndEntryLimitsNeverBecomeGenericZipOrCorrupt() {
        val many = (1..129).map { "entry$it" to byteArrayOf(1) }.toTypedArray()
        val result = held("many.zip", zipFixture(*many))
        assertEquals(Reason.LIMIT, result.reason)
        assertEquals(BudgetOutcome.EXCEEDED, result.usage.outcome)
        val huge = zipFixture("x".repeat(400) to byteArrayOf(1), comment = "a".repeat(65535))
        val source = BytesSource(huge, "large.zip").apply { chunk = 1 }
        val limited = FileClassifier().classify(source, nanoTime = { 0L })
        assertEquals(Reason.LIMIT, limited.reason)
        assertTrue(source.reads <= 2048)
        assertTrue(source.closed)
        val longNames = (1..90).map { "entry$it-" + "x".repeat(390) to byteArrayOf(1) }.toTypedArray()
        assertEquals(Reason.LIMIT, held("huge-directory.zip", zipFixture(*longNames)).reason)
    }

    @Test fun requestedHeaderAndContainerBytesCannotExceedIndependentBudgets() {
        val source = BytesSource(ByteArray(200_000))
        val probe = BoundedProbe(source, source.meta.size, {}, { 0L })
        probe.read(0, 4096, header = true)
        try { probe.read(0, 1, header = true); fail() } catch (limit: ProbeFailure) { assertEquals(Reason.LIMIT, limit.reason) }
        probe.read(0, 131072)
        try { probe.read(0, 1); fail() } catch (limit: ProbeFailure) { assertEquals(Reason.LIMIT, limit.reason) }
        assertEquals(4096 + 131072, source.actualBytes)
        assertEquals(source.actualBytes, probe.usage(BudgetOutcome.EXCEEDED).totalBytes)
    }

    @Test fun nfcComparisonCaseCompoundAndIntentionalNumberingPreserveNames() {
        val a = analyzeName("poroc\u030cilo (1).PDF", ClassificationPolicy())
        val b = analyzeName("poročilo (1).pdf", ClassificationPolicy())
        assertEquals(a.comparison, b.comparison)
        assertEquals("pdf", a.extension)
        candidate("report (1).PDF", pdfFixture(), Destination.PDF)
        for (ext in listOf("tar.gz", "tar.xz", "tar.zst")) assertEquals(ext, analyzeName("backup.$ext", ClassificationPolicy()).extension)
        candidate("backup.tar.gz", byteArrayOf(31, 139.toByte(), 8, 0, 0, 0, 0, 0, 0, 0), Destination.ARCHIVE)
        for (name in listOf("app.apk.1", "report.pdf.1", "report.PDF.123")) {
            val result = held(name, pdfFixture())
            assertEquals(Reason.NUMBERED_SUFFIX, result.reason)
            assertEquals(0, result.usage.totalBytes)
        }
    }

    @Test fun pathAndBidiAndCaseTricksGateBeforeReads() {
        for (name in listOf("../a.pdf", "a/b.pdf", "a\\b.pdf", "a\u202epdf", "a\u2066.pdf", "nul\u0000.pdf", "report.pdf ", "report.pdf.", ".", "..")) {
            assertEquals(0, held(name, pdfFixture()).usage.totalBytes)
        }
        assertEquals(Safety.POSSIBLY_ACTIVE_DOWNLOAD, held("a.PART", pdfFixture()).safety)
        assertEquals(Safety.POSSIBLY_ACTIVE_DOWNLOAD, held(".pending-a", pdfFixture()).safety)
    }

    @Test fun emptyPartialUnreadableUnknownAndExemptionRemainDistinct() {
        val empty = classify("empty.txt", ByteArray(0))
        assertEquals(Reason.EMPTY, empty.reason)
        assertEquals(Decision.UNDETERMINED, empty.decision)
        held("partial.png", byteArrayOf(0x89.toByte(), 80, 78))
        val source = BytesSource(pdfFixture(), "a.pdf").apply { failure = IOException("private path must never be surfaced") }
        val result = FileClassifier().classify(source)
        assertEquals(Safety.UNAVAILABLE, result.safety)
        assertFalse(result.toString().contains("private path"))
        assertTrue(source.closed)
        for (meta in listOf(source.meta.copy(exempt = true), source.meta.copy(permission = false), source.meta.copy(regular = false))) {
            val gated = BytesSource(pdfFixture()).apply { this.meta = meta }
            assertEquals(0, FileClassifier().classify(gated).usage.totalBytes)
            assertEquals(0, gated.reads)
        }
    }

    @Test fun changedSizeStampIdentityOrGrantDiscardsEvidence() {
        val changes: List<(SourceMetadata) -> SourceMetadata> = listOf({ it.copy(size = it.size + 1) }, { it.copy(identity = "replaced") }, { it.copy(stamp = "changed") }, { it.copy(permission = false) })
        for (change in changes) {
            val source = BytesSource(pdfFixture(), "a.pdf")
            source.onRead = { source.meta = change(source.meta) }
            val result = FileClassifier().classify(source)
            assertEquals(Decision.REVIEW_HOLD, result.decision)
            assertNull(result.destination)
            assertTrue(source.closed)
            assertEquals(source.actualBytes, result.usage.totalBytes)
        }
    }

    @Test fun shortReadsSparseSizeAndErrorsStayBounded() {
        val source = BytesSource(ByteArray(4096) { 0 }, "sparse.dat").apply { meta = meta.copy(size = 4L * 1024 * 1024 * 1024); chunk = 13 }
        val result = FileClassifier().classify(source)
        assertEquals(4096, result.usage.headerBytes)
        assertEquals(4096, source.actualBytes)
        val partial = BytesSource(pdfFixture(), "a.pdf").apply { meta = meta.copy(size = 4096) }
        assertEquals(Reason.READ_ERROR, FileClassifier().classify(partial).reason)
        assertTrue(partial.closed)
    }

    @Test fun textDecodingAndStructuredHeuristicsNeverClaimSyntaxValidity() {
        for ((name, text) in listOf("a.json" to "{broken but plausible", "a.xml" to "<?xml version=\"1.0\"?><root>", "a.html" to "<html>hello")) {
            val result = classify(name, text.toByteArray())
            assertEquals(Confidence.PROBABLE_TEXT, result.confidence)
            assertEquals(Destination.DATA, result.destination)
        }
        candidate("a.tsv", "a\tb\n1\t2\n".toByteArray(), Destination.SHEETS)
        candidate("a.txt", byteArrayOf(255.toByte(), 254.toByte()) + "hello".toByteArray(Charsets.UTF_16LE), Destination.TEXT)
        held("bad.txt", byteArrayOf(0xc3.toByte(), 0x28))
        held("binary.pdf", "plain text".toByteArray())
        held("a.csv", "a,b\n1,2,3".toByteArray())
        candidate("boundary.txt", ("a".repeat(4095) + "č").toByteArray(), Destination.TEXT)
    }

    @Test fun subtitlesComicsAndPolicyDefaultsAreReversible() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nfixture\n".toByteArray()
        val subtitle = classify("a.srt", srt)
        assertEquals(Destination.VIDEO, subtitle.destination)
        assertEquals(setOf("subtitle_sidecar"), subtitle.tags)
        assertEquals(Destination.TEXT, classify("a.srt", srt, ClassificationPolicy(subtitlesInVideos = false)).destination)
        candidate("a.vtt", "WEBVTT\n\n00:01.000 --> 00:02.000\nfixture".toByteArray(), Destination.VIDEO)
        candidate("a.ass", "[Script Info]\n[Events]\nDialogue: fixture".toByteArray(), Destination.VIDEO)
        held("a.srt", "ordinary prose".toByteArray())
        val comic = zipFixture("page.png" to pngFixture())
        candidate("comic.cbz", comic, Destination.EBOOKS)
        assertEquals(Destination.ARCHIVE, classify("comic.cbz", comic, ClassificationPolicy(comicsAsEbooks = false)).destination)
        held("comic.cbz", zipFixture())
        held("comic.cbr", byteArrayOf(82, 97, 114, 33, 26, 7, 0))
    }

    @Test fun mediaContainersDoNotAssertCodecsOrAudioCompression() {
        candidate("audio.wav", riffFixture("WAVE"), Destination.AUDIO)
        held("track.mp3", "ID3".toByteArray())
        held("video.mp4", ByteArray(4) + "ftypisom".toByteArray())
        held("video.mkv", byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        held("video.webm", byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        held("disk.iso", ByteArray(4096))
    }

    @Test fun cancellationAndElapsedBudgetCloseResources() {
        val source = BytesSource(zipFixture("a" to byteArrayOf(1)), "a.zip")
        var checks = 0
        try { FileClassifier().classify(source, checkpoint = { if (++checks > 5) throw CancellationException() }); fail() } catch (_: CancellationException) { }
        assertTrue(source.closed)
        var now = 0L
        val timed = BytesSource(pdfFixture(), "a.pdf")
        val result = FileClassifier().classify(timed, nanoTime = { now.also { now += 300_000_000 } })
        assertEquals(Reason.LIMIT, result.reason)
        assertTrue(timed.closed)
    }

    @Test fun deterministicTruncationAndRandomInputsNeverEscapeBudgetsOrThrow() {
        val zip = zipFixture("[Content_Types].xml" to byteArrayOf(1), "word/document.xml" to byteArrayOf(2))
        for (length in zip.indices) held("a.docx", zip.copyOf(length).ifEmptyBytes())
        val random = java.util.Random(26)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(6000)).also(random::nextBytes)
            val result = classify("a.dat", bytes)
            assertTrue(result.usage.totalBytes <= 4096 + 131072)
        }
    }

    private fun ByteArray.indexOfCentral(): Int = indices.first { matchesAt(it, 80, 75, 1, 2) }
    private fun ByteArray.ifEmptyBytes() = if (isEmpty()) byteArrayOf(80) else this
}

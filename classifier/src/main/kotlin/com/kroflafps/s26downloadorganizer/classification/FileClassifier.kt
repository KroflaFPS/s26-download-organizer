package com.kroflafps.s26downloadorganizer.classification

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

/** Offline advisory engine. Owns/closes its source, even on cancellation; no Android imports. */
class FileClassifier(private val policy: ClassificationPolicy = ClassificationPolicy()) {
    fun preflight(metadata: SourceMetadata): Classification? = gate(metadata, analyzeName(metadata.name, policy))
    fun classify(source: ProbeSource, checkpoint: () -> Unit = {}, nanoTime: () -> Long = System::nanoTime): Classification {
        var probe: BoundedProbe? = null
        return try {
            source.use {
                checkpoint()
                val before = source.metadata()
                val name = analyzeName(before.name, policy)
                val gated = gate(before, name)
                if (gated != null) return@use gated
                val reader = BoundedProbe(source, before.size, checkpoint, nanoTime).also { probe = it }
                val header = reader.read(0, minOf(before.size, BoundedProbe.HEADER_LIMIT.toLong()).toInt(), header = true)
                val result = detect(header, name.extension, reader)
                reader.check()
                val after = source.metadata()
                if (!after.permission) return@use unavailable(Reason.PERMISSION, Safety.UNAVAILABLE)
                if (before != after) return@use unavailable(Reason.METADATA_CHANGED, Safety.METADATA_MISMATCH)
                result
            }.copy(usage = probe?.usage(BudgetOutcome.WITHIN_LIMIT) ?: ReadUsage())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProbeFailure) {
            val result = when (failure.reason) {
                Reason.METADATA_CHANGED -> unavailable(failure.reason, Safety.METADATA_MISMATCH)
                Reason.READ_ERROR -> unavailable(failure.reason, Safety.UNAVAILABLE)
                else -> hold(failure.reason)
            }
            result.copy(usage = probe?.usage(if (failure.reason == Reason.LIMIT) BudgetOutcome.EXCEEDED else BudgetOutcome.READ_FAILED) ?: ReadUsage())
        } catch (_: SecurityException) {
            unavailable(Reason.PERMISSION, Safety.UNAVAILABLE).copy(usage = probe?.usage(BudgetOutcome.READ_FAILED) ?: ReadUsage())
        } catch (_: SourceChangedException) {
            unavailable(Reason.METADATA_CHANGED, Safety.METADATA_MISMATCH).copy(usage = probe?.usage(BudgetOutcome.READ_FAILED) ?: ReadUsage())
        } catch (_: IOException) {
            unavailable(Reason.READ_ERROR, Safety.UNAVAILABLE).copy(usage = probe?.usage(BudgetOutcome.READ_FAILED) ?: ReadUsage())
        }
    }

    private fun gate(meta: SourceMetadata, name: NameEvidence): Classification? = when {
        !meta.permission -> unavailable(Reason.PERMISSION, Safety.UNAVAILABLE)
        meta.exempt -> unavailable(Reason.EXEMPTION, Safety.EXEMPT)
        !meta.regular || meta.size < 0 || meta.identity.isBlank() || meta.stamp.isBlank() -> unavailable(Reason.INVALID_SOURCE, Safety.UNKNOWN)
        name.issue == Reason.ACTIVE_NAME -> unavailable(Reason.ACTIVE_NAME, Safety.POSSIBLY_ACTIVE_DOWNLOAD)
        name.issue != null -> hold(name.issue)
        else -> null
    }

    private fun detect(h: ByteArray, ext: String?, probe: BoundedProbe): Classification {
        if (h.isEmpty()) return Classification(reason = Reason.EMPTY)
        if (h.matchesAt(0, 0x50, 0x4b, 3, 4) || h.matchesAt(0, 0x50, 0x4b, 5, 6)) return zip(ZipProbe(probe).inspect(), ext)
        if (h.asciiAt(0, "%PDF-") && h.size >= 9 && (h.asciiAt(5, "1.") && h[7].toInt() in 48..55 || h.asciiAt(5, "2.0")) && h[8].toInt() in listOf(10, 13, 32)) return recognized("PDF header", Destination.PDF, ext, setOf("pdf"))
        if (h.matchesAt(0, 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10) && h.size >= 33 && h.matchesAt(8, 0, 0, 0, 13) && h.asciiAt(12, "IHDR")) return recognized("PNG header", Destination.PHOTOS, ext, setOf("png"))
        if (h.matchesAt(0, 255, 216, 255) && h.size >= 4 && (h[3].toInt() and 255) in 0xc0..0xfe) return recognized("JPEG signature", Destination.PHOTOS, ext, setOf("jpg", "jpeg"))
        if ((h.asciiAt(0, "GIF87a") || h.asciiAt(0, "GIF89a")) && h.size >= 13 && h.u16(6) > 0 && h.u16(8) > 0) return recognized("GIF header (animation unknown)", Destination.GIF, ext, setOf("gif"))
        if (h.asciiAt(0, "RIFF")) {
            if (h.size < 12 || h.u32(4) + 8 > probe.size || h.u32(4) < 4) return hold(Reason.MALFORMED_OR_PARTIAL, "RIFF")
            return when {
                h.asciiAt(8, "WEBP") && h.size >= 20 && listOf("VP8 ", "VP8L", "VP8X").any { h.asciiAt(12, it) } -> recognized("WebP container (animation unknown)", Destination.PHOTOS, ext, setOf("webp"))
                h.asciiAt(8, "WAVE") -> recognized("WAVE container (codec unknown)", Destination.AUDIO, ext, setOf("wav", "wave"))
                h.asciiAt(8, "AVI ") -> recognized("AVI container (codec unknown)", Destination.VIDEO, ext, setOf("avi"))
                else -> hold(Reason.UNSUPPORTED, "RIFF")
            }
        }
        if (h.size >= 8 && h.asciiAt(4, "ftyp")) return hold(Reason.UNSUPPORTED, "ISO-BMFF container (tracks/codecs uninspected)")
        if (h.matchesAt(0, 0x1a, 0x45, 0xdf, 0xa3)) return hold(Reason.UNSUPPORTED, "EBML container (DocType/tracks uninspected)")
        if (h.matchesAt(0, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return hold(Reason.UNSUPPORTED, "OLE compound container (including possible encrypted Office)")
        if (h.asciiAt(0, "fLaC") && h.size >= 42 && (h[4].toInt() and 127) == 0 && h.matchesAt(5, 0, 0, 34)) return recognized("FLAC STREAMINFO header", Destination.LOSSLESS, ext, setOf("flac"))
        if (h.asciiAt(0, "OggS") || h.asciiAt(0, "ID3")) return hold(Reason.UNSUPPORTED, "Audio-related container/tag (codec uninspected)")
        if (h.matchesAt(0, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)) return hold(Reason.UNSUPPORTED, "7z outer signature (encryption/structure uninspected)")
        if (h.matchesAt(0, 0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0) || h.matchesAt(0, 0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1, 0)) return hold(Reason.UNSUPPORTED, "RAR outer signature (encryption/structure uninspected)")
        if (h.matchesAt(0, 31, 139, 8) && h.size >= 10 && (h[3].toInt() and 0xe0) == 0) return recognized("GZIP outer header (inner TAR unproven)", Destination.ARCHIVE, ext, setOf("gz", "tgz", "tar.gz"))
        if (h.matchesAt(0, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0) && h.size >= 12) return recognized("XZ outer header (inner TAR unproven)", Destination.ARCHIVE, ext, setOf("xz", "tar.xz"))
        if (h.matchesAt(0, 0x28, 0xb5, 0x2f, 0xfd) && h.size >= 6) return recognized("Zstandard outer header (inner TAR unproven)", Destination.ARCHIVE, ext, setOf("zst", "tar.zst"))
        if (h.asciiAt(0, "BZh") && h.size >= 10 && h[3].toInt() in 49..57) return recognized("Bzip2 outer header", Destination.ARCHIVE, ext, setOf("bz2"))
        if (h.asciiAt(257, "ustar")) return hold(Reason.UNSUPPORTED, "TAR marker (checksum uninspected)")
        if (h.matchesAt(0, 0x7f, 69, 76, 70) || h.asciiAt(0, "MZ")) return hold(Reason.UNSUPPORTED, "Executable signature")
        val text = textSample(h, probe.size > h.size) ?: return unknown(ext)
        val trimmed = text.trimStart('\ufeff', ' ', '\t', '\r', '\n')
        val structured = when {
            ext == "ass" -> null // ASS section brackets are not JSON evidence.
            trimmed.startsWith("<!DOCTYPE html", true) || trimmed.startsWith("<html", true) -> "HTML-like text" to setOf("html", "htm")
            trimmed.startsWith("<?xml") || Regex("^<[A-Za-z_][A-Za-z0-9_.:-]*(?:\\s|>|/)").containsMatchIn(trimmed) -> "XML-like text" to setOf("xml")
            trimmed.startsWith('{') || trimmed.startsWith('[') -> "Structured-data-like text" to setOf("json", "jsonl")
            else -> null
        }
        if (ext in setOf("srt", "vtt", "ass")) {
            val plausible = when (ext) {
                "vtt" -> trimmed.startsWith("WEBVTT") && "-->" in text
                "ass" -> "[Script Info]" in text && "[Events]" in text && "Dialogue:" in text
                else -> Regex("\\d{2}:\\d{2}:\\d{2},\\d{3} --> \\d{2}:\\d{2}:\\d{2},\\d{3}").containsMatchIn(text)
            }
            return if (plausible && structured == null) Classification("Probable subtitle sidecar", Confidence.PROBABLE_TEXT,
                decision = Decision.CANDIDATE, destination = if (policy.subtitlesInVideos) Destination.VIDEO else Destination.TEXT,
                reason = Reason.SUBTITLE, tags = setOf("subtitle_sidecar")) else hold(Reason.EXTENSION_MISMATCH, "Text", Confidence.INCOMPATIBLE)
        }
        if (structured != null) {
            if (ext != null && ext !in structured.second) return hold(Reason.EXTENSION_MISMATCH, structured.first, Confidence.INCOMPATIBLE)
            return Classification(structured.first, Confidence.PROBABLE_TEXT, decision = Decision.CANDIDATE, destination = Destination.DATA, reason = Reason.TEXT_HEURISTIC)
        }
        if (ext in setOf("csv", "tsv")) {
            val delimiter = if (ext == "csv") ',' else '\t'
            val rows = text.lineSequence().filter { it.isNotBlank() }.take(8).toList()
            val counts = rows.map { row -> row.count { it == delimiter } }
            if (rows.size >= 2 && counts.first() > 0 && counts.distinct().size == 1 && '"' !in text) return Classification("Probable delimited text", Confidence.PROBABLE_TEXT, decision = Decision.CANDIDATE, destination = Destination.SHEETS, reason = Reason.TEXT_HEURISTIC)
            return hold(Reason.UNSUPPORTED, "Text")
        }
        if (ext == null || ext in setOf("txt", "md", "log")) return Classification("Probable text", Confidence.PROBABLE_TEXT, decision = Decision.CANDIDATE, destination = Destination.TEXT, reason = Reason.TEXT_HEURISTIC)
        return if (ext in claimedExtensions) hold(Reason.EXTENSION_MISMATCH, "Text", Confidence.INCOMPATIBLE) else unknown(ext)
    }

    private fun zip(zip: ZipProbe.Evidence, ext: String?): Classification {
        val names = zip.names
        val matches = buildList {
            if ("AndroidManifest.xml" in names && ("classes.dex" in names || "resources.arsc" in names)) add(Triple("APK-like ZIP", Destination.APK, setOf("apk")))
            if ("[Content_Types].xml" in names) {
                if ("word/document.xml" in names) add(Triple("Word OOXML-like ZIP", Destination.TEXT, setOf("docx", "docm")))
                if ("xl/workbook.xml" in names) add(Triple("Spreadsheet OOXML-like ZIP", Destination.SHEETS, setOf("xlsx", "xlsm")))
                if ("ppt/presentation.xml" in names) add(Triple("Presentation OOXML-like ZIP", Destination.SLIDES, setOf("pptx", "pptm")))
            }
            if (zip.epubMimetype && "META-INF/container.xml" in names) add(Triple("EPUB-like ZIP", Destination.EBOOKS, setOf("epub")))
        }
        if (matches.size > 1) return hold(Reason.CONTAINER_AMBIGUOUS, "ZIP with competing markers")
        matches.singleOrNull()?.let { return recognized(it.first, it.second, ext, it.third).let { result ->
            if (result.decision == Decision.CANDIDATE) result.copy(reason = Reason.CONTAINER_MARKERS) else result
        } }
        // Markers missing from this bounded, fully parsed directory still do not establish payload type.
        if (names.any { it == "AndroidManifest.xml" || it == "BundleConfig.pb" || it.endsWith(".apk") || it == "META-INF/container.xml" || it == "[Content_Types].xml" || it == "mimetype" }) return hold(Reason.CONTAINER_AMBIGUOUS, "ZIP")
        if (ext == "cbz") {
            val comicNames = names.filterNot { it.endsWith('/') || it == "ComicInfo.xml" }
            if (comicNames.isEmpty() || comicNames.any { it.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png", "gif", "webp") }) return hold(Reason.CONTAINER_AMBIGUOUS, "ZIP")
            return Classification("Comic ZIP candidate", Confidence.AMBIGUOUS, decision = Decision.CANDIDATE,
                destination = if (policy.comicsAsEbooks) Destination.EBOOKS else Destination.ARCHIVE, reason = Reason.COMIC)
        }
        return recognized("Traditional ZIP headers", Destination.ARCHIVE, ext, setOf("zip"))
    }

    private fun recognized(format: String, destination: Destination, extension: String?, compatible: Set<String>): Classification =
        if (extension != null && extension !in compatible) hold(Reason.EXTENSION_MISMATCH, format, Confidence.INCOMPATIBLE)
        else if (destination.category == Category.Installers && extension == null) hold(Reason.EXTENSION_MISMATCH, format, Confidence.INCOMPATIBLE)
        else Classification(format, Confidence.VERIFIED, decision = Decision.CANDIDATE, destination = destination, reason = Reason.RECOGNIZED)

    private fun unknown(ext: String?): Classification = if (ext in claimedExtensions) hold(Reason.UNSUPPORTED, confidence = Confidence.EXTENSION_ONLY)
        else Classification(decision = Decision.CANDIDATE, destination = Destination.OTHER, reason = Reason.NO_SIGNATURE)

    companion object {
        private val claimedExtensions = setOf("pdf", "apk", "aab", "apks", "apkm", "xapk", "doc", "docx", "docm", "xls", "xlsx", "xlsm", "ppt", "pptx", "pptm", "epub", "cbz", "cbr", "odt", "ods", "odp", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "svgz", "dng", "cr2", "cr3", "nef", "arw", "heic", "heif", "avif", "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "mp4", "mkv", "mov", "webm", "avi", "zip", "rar", "7z", "tar", "gz", "tgz", "tar.gz", "xz", "tar.xz", "zst", "tar.zst", "bz2", "iso", "img", "dmg", "txt", "md", "log", "json", "jsonl", "xml", "csv", "tsv", "html", "htm", "srt", "vtt", "ass")
        internal fun hold(reason: Reason, format: String = "Unknown", confidence: Confidence = Confidence.AMBIGUOUS) = Classification(format, confidence, decision = Decision.REVIEW_HOLD, reason = reason)
        fun unavailable(reason: Reason, safety: Safety) = Classification(safety = safety, decision = Decision.REVIEW_HOLD, reason = reason)
    }
}

private fun textSample(bytes: ByteArray, partial: Boolean): String? {
    val charset = when {
        bytes.matchesAt(0, 255, 254) -> Charsets.UTF_16LE
        bytes.matchesAt(0, 254, 255) -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val output = CharBuffer.allocate(bytes.size)
    val result = decoder.decode(ByteBuffer.wrap(bytes), output, !partial)
    if (result.isError) return null
    output.flip()
    val text = output.toString()
    if (text.isEmpty() || text.any { it.isISOControl() && it !in "\r\n\t" }) return null
    return text
}

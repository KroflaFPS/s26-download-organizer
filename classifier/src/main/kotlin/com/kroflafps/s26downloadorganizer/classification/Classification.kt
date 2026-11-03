package com.kroflafps.s26downloadorganizer.classification

import java.io.Closeable
import java.util.Locale
import java.text.Normalizer

enum class Category { Installers, Documents, Images, Audio, Videos, Archives, Other }

/** Curated values only. No source name or arbitrary target path enters a proposal. */
enum class Destination(val category: Category, val folder: String? = null) {
    APK(Category.Installers, "APK"), APP_BUNDLES(Category.Installers, "App Bundles"),
    PDF(Category.Documents, "PDF"), TEXT(Category.Documents, "Word & Text"),
    SHEETS(Category.Documents, "Spreadsheets"), SLIDES(Category.Documents, "Presentations"),
    EBOOKS(Category.Documents, "Ebooks"), DATA(Category.Documents, "Data & Code"),
    PHOTOS(Category.Images, "Photos & Web"), GIF(Category.Images, "Animated"),
    AUDIO(Category.Audio), LOSSLESS(Category.Audio, "Lossless"), VIDEO(Category.Videos),
    ARCHIVE(Category.Archives, "Standard"), OTHER(Category.Other);
    val label: String get() = category.name + (folder?.let { "/$it" } ?: "")
}

enum class Confidence { VERIFIED, PROBABLE_TEXT, AMBIGUOUS, EXTENSION_ONLY, UNKNOWN, INCOMPATIBLE }
enum class Safety { PROVISIONAL_ONLY, UNKNOWN, UNAVAILABLE, POSSIBLY_ACTIVE_DOWNLOAD, EXEMPT, METADATA_MISMATCH }
enum class Decision { CANDIDATE, REVIEW_HOLD, UNDETERMINED }
enum class Reason(val explanation: String) {
    RECOGNIZED("Bounded signature/structure recognition; validity and security are unproven."),
    TEXT_HEURISTIC("Text sample is plausible; full syntax and content are unvalidated."),
    NO_SIGNATURE("No supported signature; ordinary unknown candidate only."),
    EXTENSION_MISMATCH("Filename and content evidence disagree; review the original extension."),
    UNSUPPORTED("Container or claimed format needs unsupported inspection; review required."),
    CONTAINER_MARKERS("ZIP headers and format markers agree; payloads were not decompressed or validated."),
    CONTAINER_AMBIGUOUS("Container evidence is inconclusive or contains competing format markers."),
    MALFORMED_OR_PARTIAL("Structure is inconsistent or partial; neither corruption nor download activity is proven."),
    LIMIT("Inspection limit reached; no conclusion about missing markers or corruption."),
    READ_ERROR("Content unavailable or read failed; no format conclusion."),
    PERMISSION("Read permission unavailable or revoked."),
    METADATA_CHANGED("Source metadata, identity or anchored location changed; discard evidence."),
    INVALID_SOURCE("Only regular immediate Downloads files with safe names can be inspected."),
    ACTIVE_NAME("Name suggests an active download; no content read."),
    EXEMPTION("Explicit exemption; no content read."),
    NUMBERED_SUFFIX("Trailing numbered extension needs review; original name preserved."),
    UNSAFE_NAME("Name contains separators, controls or bidirectional formatting; review required."),
    EMPTY("Zero bytes may be legitimate; format undetermined."),
    SUBTITLE("Probable subtitle_sidecar from extension and text cues; not a video payload."),
    COMIC("Archive evidence supports provisional comic placement; contents unvalidated.");
}

enum class BudgetOutcome { WITHIN_LIMIT, NOT_READ, EXCEEDED, READ_FAILED }
data class ReadUsage(val headerBytes: Int = 0, val containerBytes: Int = 0, val outcome: BudgetOutcome = BudgetOutcome.NOT_READ) {
    val totalBytes: Int get() = headerBytes + containerBytes
}
data class Classification(
    val format: String = "Unknown", val confidence: Confidence = Confidence.UNKNOWN,
    val safety: Safety = Safety.PROVISIONAL_ONLY, val decision: Decision = Decision.UNDETERMINED,
    val destination: Destination? = null, val reason: Reason, val usage: ReadUsage = ReadUsage(),
    val tags: Set<String> = emptySet(),
) {
    // Deliberately no operation request, source/destination path, or mutation authorization.
    val explanation: String get() = reason.explanation
}

/** These recommendations are provisional owner choices and can be replaced independently. */
data class ClassificationPolicy(
    val holdNumberedExtensions: Boolean = true,
    val comicsAsEbooks: Boolean = true,
    val subtitlesInVideos: Boolean = true,
)

data class SourceMetadata(
    val name: String, val size: Long, val identity: String, val stamp: String,
    val regular: Boolean = true, val permission: Boolean = true, val exempt: Boolean = false,
)

/** The adapter must use one read-only descriptor, reject links, and revalidate the anchor/permission.
 * No write, path selection, decompression, network or Android capability is exposed here.
 */
interface ProbeSource : Closeable {
    fun metadata(): SourceMetadata
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int
}

/** Adapter detected a descriptor/anchor race. Carries no private source details. */
class SourceChangedException : java.io.IOException()

internal data class NameEvidence(val comparison: String, val extension: String?, val issue: Reason?)
internal fun analyzeName(name: String, policy: ClassificationPolicy): NameEvidence {
    if (name.length > 255 || name.isEmpty() || name in listOf(".", "..") || name.any {
            it == '/' || it == '\\' || it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it == '\u200e' || it == '\u200f'
        }) return NameEvidence("", null, Reason.UNSAFE_NAME)
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    val compound = listOf("tar.gz", "tar.xz", "tar.zst").firstOrNull { normalized.endsWith(".$it") }
    val extension = compound ?: normalized.lastIndexOf('.').takeIf { it > 0 && it < normalized.lastIndex }?.let { normalized.substring(it + 1) }
    val issue = when {
        normalized.startsWith(".pending-") || listOf("part", "partial", "crdownload", "download", "tmp", "temp", "opdownload", "filepart").contains(extension) -> Reason.ACTIVE_NAME
        policy.holdNumberedExtensions && Regex(".*\\.(apk|pdf)\\.[0-9]+$").matches(normalized) -> Reason.NUMBERED_SUFFIX
        normalized.endsWith('.') || normalized != normalized.trim() -> Reason.UNSAFE_NAME
        else -> null
    }
    return NameEvidence(normalized, extension, issue)
}

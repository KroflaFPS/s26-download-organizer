# Build 5: read-only classification preview

Build 5 proposes destinations; it never authorizes an operation. `OperationPolicy.MUTATIONS_ENABLED`
remains false, the operation engine still targets Other only, and the classifier has no operation,
journal, write, directory-creation, network, Android or PackageManager API. Main's inventory stays
metadata-only. Content reading starts only after an explicit **Preview type** tap on one file.

## Sources and precedence

- The owner's implementation assignment dated 2026-11-03 governs this isolated branch's scope.
- [Jinx research](https://docs.google.com/document/d/12-EBIdB9Ct6DmTEk9jByggPfEMAUu7C60o05jgtnLSM/edit)
  supplies proposals, not verified performance or platform guarantees.
- [Ada required corrections](https://docs.google.com/document/d/1KcRKS-1eQTcandR6kT0pgTMK5tbR92ryXTR4Feoee0M/edit)
  and the implementation assignment take precedence: no automatic authorization, no inferred
  writer closure, no safe-alias shortcut, no zero-byte corruption label.
- [Build 4 repair re-audit](https://drive.google.com/file/d/1mYeGO0M4Vo4QQAyBL48rym5CzIuNfVqe/view)
  concerns separate PR #6. The owner's reported successful core functional phone campaign
  does not establish full coverage, long Doze behavior or production reliability. No Build 4
  source or observer is integrated here.
- [Android Os API](https://developer.android.com/reference/android/system/Os): `pread` with a
  byte array and explicit offset/count, `open`, `fstat`, `lstat`, and `readlink` are public APIs
  added in API 21. Runtime FUSE/procfs support is still an acceptance gate.

## Three independent dimensions

`Classification` contains format/confidence, source safety/readiness, and a routing decision.
`VERIFIED` means a recognizable signature and bounded structural fields/markers agree; it does
not mean every byte was checked, semantic validity, authenticity, absence of malicious content,
or permission to move. ZIP payloads are never decompressed. `PROBABLE_TEXT` is deliberately
heuristic. `INCOMPATIBLE`, `EXTENSION_ONLY`, `AMBIGUOUS`, and `UNKNOWN` remain distinct.

Every readable source stays `PROVISIONAL_ONLY`: no validated writer-closure evidence exists.
Unknown/unavailable, active-name, exemption, and metadata-mismatch gates are independent.
Stable metadata, `IS_PENDING=0`, file locks, or successful parsing never become readiness proof.
Core metadata accepts an explicit exemption. Main currently has no persistent per-file exemption
or override store; adding that store or importing Build 4/Undo exemptions is deferred to integration.

`CANDIDATE` carries a curated `Destination` enum only. `REVIEW_HOLD` and `UNDETERMINED` have
no destination. No source bytes enter a target path. The seven fixed categories are Installers,
Documents, Images, Audio, Videos, Archives and Other, with shallow enum-selected subfolders.
`ReadUsage` reports actual returned bytes including repeated ranges and a budget outcome.
Human-readable reasons never contain adapter exceptions, private paths, or extracted marker names.

## Bounded stages

1. Preflight validates metadata, permission, exemption and lexical names before content reads.
   Invalid or unavailable inventory metadata fails closed. NFC and locale-independent lowercase
   apply only to comparisons; original filenames are never modified. Compound `.tar.gz`,
   `.tar.xz`, `.tar.zst` remain tokens; `report (1).pdf` is intentional text, not stripped.
   Separators, controls, BiDi formatting, trailing spaces/dots, and numbered PDF/APK suffixes hold.
2. At most **4,096 header bytes**, strict UTF-8 or BOM-selected UTF-16 text sampling, and fixed
   signatures. A sample ending mid-codepoint can be partial if the source is longer. No permissive
   XML parser, entity expansion, renderer or syntax-validity assertion is used.
3. Traditional ZIP only: at most **131,072 additional bytes**, **2,048 read calls** total,
   **128 entries**, **32 KiB central directory**, **512 bytes per entry name**, and **4 KiB local
   extra fields**. The tail window is at most **65,557 bytes**, separate from central-directory
   work. Limits include repeated/overlapping reads. No decompression or CRC recomputation.
   EOCD must end at EOF, be unique in the tail, single-disk, and agree with directory size/count.
   Entry bounds use nonoverflowing Long arithmetic. All entry local headers must match central
   names, flags, methods, and applicable sizes/CRC; traditional data descriptors are checked.
   Duplicate/traversal names, overlapping entries, bad offsets, encrypted entries, ZIP64, unknown
   methods/flags and unsupported names hold. The entire supported directory must be scanned
   before subtype absence is interpreted. Exceeding a budget means Review, never corruption
   or an unsupported inference of generic ZIP.

All loops checkpoint cancellation, and the core applies a **250 ms cooperative elapsed limit**
to its probe/parser work. This is a limit, not measured performance or a hard wall-clock timeout:
kernel/FUSE calls, descriptor open/metadata/close and scheduling can block beyond it. Main runs
I/O on `Dispatchers.IO`, serializes actual reads with a mutex, cancels on pause/new selection,
and suppresses stale completion with a generation check. It never batches the inventory or
reads on entry to the screen. A source is closed even on cancellation; close failure discards success.

## Supported recognition versus review

| Evidence | Candidate destination | Limits/holds |
| --- | --- | --- |
| PDF version/header at offset zero | Documents/PDF | Not full PDF parsing; leading junk unsupported |
| JPEG signature, PNG signature/IHDR, GIF header | Images/Photos & Web or Animated | GIF animation not proven; PNG animation not inspected |
| RIFF + WEBP + supported chunk tag | Images/Photos & Web | Animation uninspected; incompatible `.jpg` holds |
| FLAC STREAMINFO header | Audio/Lossless | Not audio decode/CRC validation |
| RIFF WAVE or AVI | Audio flat / Videos flat | Codec, tracks, duration and media validity unknown |
| Traditional ZIP headers | Archives/Standard | Complete bounded header scan; payloads unvalidated |
| ZIP AndroidManifest + classes.dex/resources.arsc | Installers/APK | APK-like only, matching `.apk` required; not authenticated |
| ZIP Content_Types + Word/Excel/PowerPoint marker | Documents/Word & Text, Spreadsheets, Presentations | XML bodies and macros uninspected |
| ZIP first stored exact EPUB mimetype + container marker | Documents/Ebooks | Container XML and book bodies unparsed |
| GZIP, XZ, Zstandard, Bzip2 outer header | Archives/Standard | Inner TAR claim unproven; no inflate |
| Strict text sample with extension/text cues | Documents text/data/spreadsheets | Heuristics only; limited delimiter logic, quoted CSV holds |
| Ordinary unknown extension and unrecognized binary | Other | UNKNOWN confidence, provisional source; no mutation authorization |

Held/deferred: RAR/CBR and 7z structure/encryption, TAR checksum, legacy/encrypted OLE Office,
AAB/APKS/APKM/XAPK subtype validation, ISO-BMFF track disambiguation (including MP4/M4A/HEIC/AVIF),
EBML, Ogg codecs, ID3/MP3, AAC, RAW/TIFF, SVG, disk images and unrecognized claimed formats.
This intentionally trades coverage for explainable uncertainty. No PackageManager second opinion
is called: its internal I/O cannot satisfy this classifier's explicit byte accounting. A future
adapter can add separately budgeted platform evidence; null would only mean parsing unavailable.

Known polyglots/competing ZIP markers hold; bounded probes cannot discover every polyglot or
malicious payload. Matching marker names, local headers and declared CRC fields can be forged.
No archive bomb, malware, integrity or authenticity guarantee is claimed.

## Provisional policies and examples

`ClassificationPolicy` carries replaceable defaults, with no persistent settings/UI workflow:

- `.pdf.1` / `.apk.1`: Review before I/O; no suffix stripping or restore/rename action.
- `.cbz`: Documents/Ebooks only for a supported ZIP whose non-directory names suggest images
  (optional ComicInfo.xml). Confidence AMBIGUOUS; images/content unvalidated. Switchable to Archives.
  CBR remains Review because RAR structure/encryption inspection is deferred.
- `.srt` / `.vtt` / `.ass`: Videos flat with `subtitle_sidecar`, after strict text/cue checks;
  PROBABLE_TEXT, never a claim of video content. Switchable to Documents/Word & Text.

| Synthetic input | Visible output |
| --- | --- |
| `report (1).PDF` + PDF header | Documents/PDF, VERIFIED recognition, PROVISIONAL_ONLY |
| `.pdf` or `.apk` + HTML text | REVIEW_HOLD, INCOMPATIBLE, extension mismatch |
| `.jpg` + WebP | REVIEW_HOLD, INCOMPATIBLE despite same broad category |
| `.docx` + marker ZIP | Documents/Word & Text; marker evidence, bodies unparsed |
| `.zip` with too many entries | REVIEW_HOLD, LIMIT, EXCEEDED; bytes displayed |
| zero-byte file | UNDETERMINED, EMPTY; no corruption claim |
| `.srt` + plausible timestamp cue | Videos, PROBABLE_TEXT, subtitle_sidecar |
| `.aab` + ZIP markers | REVIEW_HOLD; not verified split APK |

## Android boundary and Build 6 handoff

The small positional extension to existing read handles uses `Os.pread`; legacy sequential reads
are unchanged. `RootedPreviewReader` accepts only the exact absolute normalized immediate path
under the platform primary `Download` root. It compares the inventory name/size/mtime, rejects
links and nonregular nodes through read-only `O_NOFOLLOW`/`O_NONBLOCK` opens, and keeps primary,
Downloads and source descriptors open. Descriptor identities/stamps, final name binding and
resolved anchors/permission are checked before reads and before evidence can be accepted.
Any FUSE/procfs anomaly has no path-only fallback. Metadata cannot detect every same-size write,
timestamp restoration or inode reuse; even success remains a provisional bounded snapshot.

Core tests can run with `./gradlew --no-daemon --configure-on-demand :classifier:test` using
JDK 21 and no Android SDK. No cache is used. The single UI result is ephemeral, cleared on refresh/pause and replaced on
new selection. No schema, migration, manifest, permission, background component, network access,
existing operation root, signing script or owner credentials change. Main's package is preserved;
version is 8 / 0.5.0-preview. The Kotlin/JVM plugin uses the existing Kotlin version/JUnit library.

Build 6 must independently integrate accepted readiness evidence, exemptions, approved anchored
destination policy, revalidation, collision/durability/Undo safeguards and an audited operation
adapter. Never pass these proposals straight to the current Other-only coordinator or use preview
confidence to enable mutation. PRs #4 and #6 need explicit integration approval first.

See [phone-only acceptance](BUILD_5_DEVICE_TEST.md) and [review handoff](BUILD_5_HANDOFF.md).

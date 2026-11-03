# Build 5 phone-only read-only acceptance

For KroflaFPS/Ada/Jinx after independent draft review. Codex does not install or deploy this build.
Use only disposable files. A passing JVM suite is not Samsung/FUSE or long-term background evidence.

1. On GitHub, open the Build 5 draft PR and its successful **Build debug APK** run for the intended
   head. Download **s26-build-5-readonly-preview**, extract in Samsung My Files, and retain
   `certificate.txt`. Confirm package `com.kroflafps.s26downloadorganizer`, version **8 / 0.5.0-preview**,
   the run/head relationship, APK digest and signer digest. PR run checkout SHA may be GitHub's
   tested merge ref; it is not a merge of the draft. Compare with the accepted source/head.
2. This is the **main package**, not `.diagnostic` or `.telemetry`. Ordinary CI uses an ephemeral
   debug signer. An already-installed main package requires a matching certificate for an in-place
   update. Stop on mismatch: preserve installed data/journal, never uninstall or Clear data to
   bypass it. Retain the prior accepted artifact/certificate; arrange signing continuity separately.
   No owner keys or signing changes are part of Build 5. Do not replace the diagnostic/telemetry apps.
3. After owner-approved installation, confirm **Build 5 · Read-only preview** and
   **PREVIEW ONLY • NO FILES MOVED**. Deny All Files Access; inventory/preview must be unavailable.
   Grant the app's existing access through Settings, return, and compare inventory with My Files.
   Do not grant additional permissions or battery exemptions.
4. Before previewing, record disposable filenames, sizes and modification dates in My Files.
   Keep copies outside Downloads for comparison. Confirm whether Organized already exists;
   this build must neither create it nor change its contents. Do not delete an existing folder
   or touch personal files to establish a clean test.
5. Select **Preview type** on one known PDF, JPEG/PNG, plain text/CSV, FLAC/WAV, AVI, ZIP and
   ordinary unknown file if available. Confirm each result comes from an explicit tap, carries
   format evidence, provisional readiness, suggestion or Review reason, and bytes read.
   Unrecognized formats may hold. Do not interpret VERIFIED as authenticity or valid content.
6. With a phone text editor, create disposable `.txt`, `.csv`, `.srt`, and zero-byte files where
   supported. Example SRT: `1`, newline `00:00:01,000 --> 00:00:02,000`, newline `fixture`.
   A subtitle should suggest Videos with `subtitle_sidecar`, not claim to be video. Zero bytes
   should be undetermined, not corrupt. Use My Files to make disposable **copies** with
   `.pdf`/`.apk` disguises, `.pdf.1`/`.apk.1` suffixes, intentional `(1)` numbering and Slovenian
   characters. Numbered suffixes must show Review with zero content bytes; text disguises hold.
   Never rename the only copy of a real owner file.
7. If available, use a disposable DOCX/EPUB/APK and CBZ created from your own content.
   Check marker-based recognition and explicit uncertainty. A generic ZIP copied as `.apk`
   or `.docx` must hold. AAB, CBR/RAR/7z, MP4/M4A/HEIC/AVIF and MKV/WebM may correctly hold
   because their deeper parsing is deferred. Do not execute/install an APK to test classification.
8. While an item is reading, switch apps, rotate, return, refresh, and select another item.
   No old result may reappear after pause/refresh; no automatic content scan should start on entry.
   Large inventory scrolling remains lazy. Revoke access in Settings and return: inventory and
   preview clear. Regrant and explicitly refresh/reselect. Denied reads must never use stale results.
9. On a disposable large file only, compare the header/container byte counters with limits
   **4,096 + 131,072**, and confirm Review on unsupported/limited parsing. A wall-time limit cannot
   interrupt a blocked FUSE call; report pauses or hangs with approximate duration. No device
   performance numbers are promised. Symlink/race boundary tests remain engineering acceptance
   if phone tools cannot create controlled cases safely; do not fabricate a pass.
10. Return to My Files: names, bytes (compare retained copies where practical), sizes and mtimes
    must be unchanged; no destination/staging folder should appear. Legacy Review move/Undo
    confirmation stays disabled. History/journal remains preserved. Stop and report any unexpected
    file/data change, wrong app identity, stale authorized result, or unreadable safety explanation.

Record device Android/One UI version, exact tested head/run/artifact/certificate, permission state,
fixture types, expected versus observed outcomes, UI responsiveness and which cases were unavailable.
No exported filename diagnostics, online classification, telemetry wake, observer, Doze, auto-move,
copy/delete/Undo execution, complete format coverage or writer-closure claim belongs to this test.

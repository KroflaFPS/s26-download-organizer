# Build 3.2 controlled synthetic-copy experiment

Baseline: main `08dddedf9241b403de738787e5c6ec5297429e30` (Build 3.1 / PR #3 merged).
New branch `feat/build-3-2-diagnostic-copy`; its PR must stay draft and unmerged.
Android 17 / S26 Ultra acceptance is **UNVERIFIED**. Review source and the exact
artifact before trying the experiment. No Build 4 design is included.

## Boundary and publication review

The independent diagnostic coordinator accepts a fixture name, not a path. It
opens only these primary-shared-storage directories, both created deliberately
by the owner with My Files before use:

- `/storage/emulated/0/Download/S26_Organizer_Test_Input/`
- `/storage/emulated/0/Organized/S26_Organizer_Test_Output/`

Environment resolves the actual primary root; literals above describe the
expected S26 location. API 26–29 cannot use diagnostic I/O. Source selection
checks only two exact names, never recursively lists folders, and verifies size
and SHA-256 against a fixed synthetic fixture allowlist before journal intent:

| Name | Bytes | SHA-256 |
| --- | ---: | --- |
| s26-test-empty.bin | 0 | e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 |
| s26-test-1MiB.bin | 1048576 | 631b84027d6b9e52b539c4e8373622d23032dfadc64d60af87339c9037e4f769 |

These are not arbitrary owner downloads renamed to look like fixtures. Files
with any other bytes/name/size fail before creating output. Maximum is **1 MiB**.
Source descriptors are O_RDONLY. Reads may cause OS-managed access-time
updates; the app never requests source metadata changes. No delete, move, rename, truncate or mkdir API
exists in the diagnostic port. Generic MOVE/UNDO authorization stays false, and
the generic Android writer/removal methods remain unavailable.

The Android backend uses public Os.open/fstat/lstat/readlink/lseek/read/write/fsync
and ParcelFileDescriptor APIs. It opens canonical primary storage and each
component through retained directory descriptors using /proc/self/fd/N/name,
O_NOFOLLOW and type/identity validation. All ancestors must resolve to the
expected canonical paths and retain their dev/ino identities. Runtime rejects
missing roots, aliases it cannot resolve consistently, symlinks, replaced/moved
directories, inaccessible /proc, unavailable identity, revoked permission,
read-only/unavailable storage, or unsupported directory fsync. No weaker path
fallback exists. There is no filesystem lock preventing an external app moving
an approved directory or altering its files between checks. Anchors bind access
to the originally validated objects; detected changes stop the experiment and
leave journaled artifacts for inspection. This is not an adversarial-storage or
power-loss safety certification.

Both stage and final files are opened with O_CREAT | O_EXCL | O_NOFOLLOW | O_RDWR,
**without O_TRUNC**, and retained as descriptors. Existing destinations are
never opened writable. Stage names are UUID-based; final collisions exclusively
allocate `name (1).bin`, etc., up to 999. Symlink collisions are never followed.
Only the output directory is eligible. Permission/anchor checks run between
bounded 64 KiB chunks. Reads have a maximum plus one growth/EOF sentinel.

**Intentional publication choice:** hard links and replacing renames are not
used. After verifying and syncing the stage, the coordinator journals a final
candidate, exclusively creates a fresh final file, then streams the verified
stage into that new descriptor. This has a defensible no-clobber allocation
contract, but **publication is non-atomic**: an empty/partial final name is
visible during writing. All ambiguous stage/final files remain tracked and are
never automatically cleaned up. O_EXCL failures other than ordinary name
collisions abort, with no fallback. Success requires matching SHA-256/size,
unchanged source identity/stamp/content, file fsync, directory fsync, successful
descriptor closure and final journal persistence. The exact result is:

> Copy only — original kept in Downloads.

A successful sync request is not a guarantee against sudden power loss. No
Android hardware behavior is inferred from JVM tests. Hard-link feasibility
remains unprobed because this implementation does not use it.

Public references reviewed for this implementation:
[Os](https://developer.android.com/reference/android/system/Os),
[open(2) contract linked by Os documentation](https://man7.org/linux/man-pages/man2/open.2.html),
[O_EXCL/O_NOFOLLOW](https://developer.android.com/reference/android/system/OsConstants),
[CREATE_NEW atomic creation contract](https://developer.android.com/reference/java/nio/file/StandardOpenOption#CREATE_NEW),
[SecureDirectoryStream move replacement caveat](https://developer.android.com/reference/java/nio/file/SecureDirectoryStream).

## Journal and crash behavior

Schema 3 adds separate diagnostic records/events, preserving schemas 1 and 2
and their operations/events/cursor. Explicit migrations 1→2→3 are tested. No
destructive fallback or legacy MOVE state reuse exists. Diagnostic history has
no Undo. Short transactions enforce phase order, revisions, immutable source
proof/path fields, event consistency and a unique unresolved source claim.
Duplicate request IDs do not repeat I/O. Successful test copies may be repeated
only through another explicit action, producing another uniquely named output.

```text
STAGE_ALLOCATING -> STAGE_COPYING -> STAGE_VERIFIED
-> FINAL_ALLOCATING (collision retries journal each candidate)
-> FINAL_COPYING -> FINAL_VERIFIED -> COPY_ONLY
Any interrupted phase -> RECOVERY_REQUIRED
```

Intent precedes stage creation; phase intent precedes copying and each final
allocation. No DB transaction spans copying. A failure before the next journal
write can leave the filesystem ahead of the DB; recovery treats that as unknown
output, not completion. Recovery is manual, evidence-only and bounded to 20 rows
per invocation / at most 1 MiB per observed file plus EOF probes. A separate
cursor in slot 1 advances fairly with a fixed cycle ceiling; legacy slot 0 is
unchanged. Repeated equivalent observations do not add duplicate transitions.
Recovery never retries copying, deletes outputs or promotes interrupted records
to success, even if all remaining bytes match. Missing/replaced/inaccessible
paths are visible unresolved outcomes. Cancel/backgrounding checks stop work;
process death leaves intent for the next explicit recovery check.

## Artifact and signing matrix

| Artifact | Generic MOVE/UNDO | Diagnostic copying | Signing |
| --- | --- | --- | --- |
| build-3-2-synthetic-copy-debug | Disabled | Explicit synthetic fixtures only; runtime preflight | Ephemeral debug |
| Optional stable-signed-diagnostic | Disabled | Same narrow capability | Protected main/manual + pinned owner certificate |
| Release configuration | Disabled | Disabled independently by DEBUG and capability flag | No release publication added |
| JVM fixtures | Test model only | Private temporary directories | Not packaged |

The debug APK ZIP includes `certificate.txt`. Ephemeral keys may differ from
installed Build 3.1. **Do not uninstall or clear app data to bypass a mismatch.**
Compare the new certificate fingerprint with the exact APK previously installed.
If retained APKs are available in a trusted cloud workspace with JDK/Android
build-tools, run:

```sh
python3 tools/report_apk.py new.apk --previous previously-installed.apk --output comparison.txt
```

Mismatch returns failure and instructions to obtain a matching signed build.
Matching certificate reports establish certificate compatibility for those APKs;
they do not prove which APK is currently installed, installer acceptance or data
retention. An actual device in-place upgrade remains an owner check. If the old
key is unavailable, stop this device experiment and resolve signing continuity
with the owner. No key creation/upload or bypass is part of Build 3.2. Existing
protected signing setup remains documented in README; it cannot enable MOVE or
delete. Its report explicitly labels the narrow diagnostic capability.

Uninstall, Clear data and device loss erase the app-private journal; stable
signatures cannot prevent that. Journal backups/device transfer remain excluded.
Keep journals and ambiguous files intact. No remote backup or user content upload.

## Phone-only owner checklist

Only proceed after Ada/owner review of the source and exact artifact. Use
synthetic files only and stop on any unsupported/failure message.

1. Record the commit, Android/One UI version, installed build and journal state.
   Verify certificate continuity as above **before upgrade**. A mismatch stops
   testing. No device upgrade compatibility has yet been demonstrated by Codex.
2. In GitHub Actions, select the successful Build 3.2 PR run. Download
   `s26-download-organizer-build-3-2-synthetic-copy-debug` and
   `s26-build-3-2-synthetic-fixtures`. Extract both ZIPs in Samsung My Files.
   Review `certificate.txt`; install `app-debug.apk` only if continuity is resolved.
   Review install-source permission/Auto Blocker if prompted.
3. In Internal storage → Download, deliberately create `S26_Organizer_Test_Input`.
   Wait for downloads/extraction to finish. Place only the two provided `.bin`
   fixtures there, preserving exact names.
   Deliberately create Internal storage → Organized → `S26_Organizer_Test_Output`.
   The app itself does not create these folders. Keep personal data out of both.
4. Grant All Files Access through the existing explicit setup flow. Verify normal
   top-level Downloads inventory remains unchanged. Tap **Inspect test fixtures**.
   This checks approved roots, /proc anchors, types/identity, directory fsync and
   known fixture hashes. Missing folders/unsupported behavior must refuse.
5. Tap **Review test copy** for the empty fixture. Check exact source/destination,
   1 MiB bound, source-retention and partial-final warnings; check the deliberate
   fixture acknowledgement and tap **Copy test fixture**. No action is automatic.
   On success, verify the exact copy-only message and journal evidence flags.
6. In My Files confirm the original remains and an equally sized final file is
   in the dedicated output folder. A `.s26-diagnostic-<UUID>.part` staging file is
   deliberately retained (enable hidden files if needed). Repeat with the 1 MiB
   fixture. App hashing verifies exact bytes; file sizes alone do not prove equality.
7. Copy the same fixture again deliberately. Existing output must remain intact;
   expect a numbered final name. Place a disposable sentinel under an occupied
   candidate name if testing collisions; preserve its bytes and check afterward.
   Never place personal files in the diagnostic output folder for this test.
8. Revoke All Files Access or background/force-stop during a disposable copy.
   No success should be reported for the interrupted operation. Reopen and tap
   **Check diagnostic recovery** twice: inspect tracked paths/status, no repeated
   copies or cleanup, original retained. The file is small; a completed operation
   before interruption is possible and must be distinguished from an interrupted one.
9. Wrong synthetic content/name/size, missing folders, ordinary folders and links
   (if a trusted tool can create them) must refuse without copying arbitrary data.
   Permission denial/cancellation and repeated taps must not launch parallel work.
10. Report exact messages/phase/flags and compare My Files entries before/after.
    No source removal or Undo is available. Stop after any failure; preserve stage,
    partial final and journal. Full-storage, concurrent replacement and sync/crash
    fault experiments require a separately reviewed controlled setup; do not fill
    personal storage or simulate power loss as routine acceptance.

Capabilities still unverified until demonstrated on the S26: primary alias and
/proc anchoring, stable identity/change metadata, O_EXCL collision behavior,
file/directory fsync, permission revocation timing, process-death recovery,
installer signature continuity and journal retention. No readiness, automation,
routing, classification or Build 4 work is included.

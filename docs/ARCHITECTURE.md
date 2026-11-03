# S26 Download Organizer architecture

## Build 4 read-only telemetry status

Build 4 extends the preserved Build 3.1 baseline with an isolated read-only
telemetry build type. Generic MOVE/Undo and diagnostic copy remain disabled.
The new package is `com.kroflafps.s26downloadorganizer.telemetry`; its private
telemetry schema 1 does not migrate the main journal v2. Normal builds preserve
the main ID and do not include telemetry sources or WorkManager runtime.

See [Build 4 architecture](BUILD_4_ARCHITECTURE.md) for reviewed official/AOSP
semantics, the one-shot rearm lifecycle, fenced bounded metadata checkpoints,
readiness evidence, retention/redaction and the exact PR #4 integration plan.
The [phone-only checklist](BUILD_4_DEVICE_TEST.md) is pending owner acceptance.
Native Jobs plus unique WorkManager reconciliation gather evidence only. They
never authorize file mutation or imply a delivery SLA. Organized remains excluded.

The sections below describe the retained operation foundation; their Android
writer/deletion gates remain unresolved. PR #3 is merged; PR #4 remains draft and
unmodified. KroflaFPS reports Build 3.2 synthetic-copy acceptance on the S26 Ultra,
not independent universal crash/durability certification.

## Baseline and current release status

Build 1 established the launchable Compose application and CI. Build 2 was merged
at `86082b4b887a82d66cef41ea8072428d50f7f254` via PR #2 and confirmed working on the
S26 Ultra/Android 17 by KroflaFPS. Build 3 preserves that permission/inventory code.

**Build 3.1 is a gated engineering subset. No real Android file mutation or Undo is
enabled.** Implemented: coordinator/state model, Room journal, conservative
reconciliation logic, review/history UI and realistic disposable-fixture tests.
Not implemented/approved: a production Android shared-storage writer with safe
anchored I/O/publication/removal semantics, owner signing credentials and hardware
recovery/durability acceptance. The shipping `AndroidOperationFiles` adapter
rejects all writes and cannot conditionally unlink. Bounded descriptor-backed
recovery reads are implemented; unsupported roots, identities or /proc access
return unverifiable evidence. Android/FUSE validation is still pending. Do not describe these blocked capabilities as an operational move feature.

## Toolchain and persistence dependencies

One `:app` module; application ID `com.kroflafps.s26downloadorganizer`.
AGP 9.5.0, Gradle 9.8.2, JDK 21, built-in Kotlin/Compose compiler 2.2.10,
Compose BOM 2026.09.00, Material 3, AndroidX Core 1.19.1, Activity 1.13.0,
Lifecycle 2.11.0, coroutines 1.10.2, compile/target API 37, minimum API 26 and
JUnit 4.13.2 are retained. Build 4 version code/name: 7/0.4.0; telemetry adds -telemetry.

Room 2.8.5, Room Gradle plugin 2.8.5 and **KSP2 2.3.4** add journal generation and
schema export without replacing AGP's Kotlin plugin. Official guidance checked
before selection: [Room releases/configuration](https://developer.android.com/jetpack/androidx/releases/room),
[KSP migration](https://developer.android.com/build/migrate-to-ksp),
[built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
[KSP 2.3.4](https://github.com/google/ksp/releases/tag/2.3.4), and
[Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).
KSP1 is unsuitable for AGP 9. Runtime/compiler POMs and actual generation/compilation
validate this selection. Robolectric 4.16.1/native SQLite is test-only, not a device.

## Retained inventory boundary

On API 30+, `StorageAccessAdapter` checks `Environment.isExternalStorageManager()`.
Settings opens only on a tap: package-specific All Files Access, general list,
then app details. Denial/cancellation is never treated as a grant. Resume/Refresh
recheck access; pause cancels inventory and clears its screen state. API 26–29
show an explanation and request no legacy permission.

`AndroidDownloadsRepository` resolves primary Downloads with Android Environment
APIs and runs metadata scans on Dispatchers.IO. One-level NIO attributes use
NOFOLLOW_LINKS. Links are listed, not traversed. Missing/inaccessible entries,
empty sources, revoked access and storage failures retain their Build 2 behavior.
Ordering is newest modification first, then exact name and normalized identity.
Normalized Path components exclude Organized/ and descendants, not sibling prefixes.
No SD card/work-profile/virtual-provider inventory is added. The separate telemetry adapter adds bounded subfolder metadata pages; the flat inventory remains unchanged.

## Operation boundaries and roots

`operations` contains platform-independent records, policies, ports, coordinator
and recovery logic. `history` isolates Room. `storage` retains inventory/adapters;
`ui` handles observable state/presentation. `OrganizerApplication` owns one journal
and one coordinator. No DI framework is introduced. WorkManager and the bounded native JobService exist only in the telemetry source set.
Room's unused multi-instance invalidation service is explicitly removed from the
merged manifest; source/merged allowlist tests reject services in normal builds and allow only the two BIND_JOB_SERVICE services in telemetry. No foreground service is packaged.

The only forward source is an explicitly selected immediate regular file in
primary Downloads. The sole Build 3 forward target is primary Organized/Other/.
Undo reverses an eligible committed operation into Downloads. No classification,
category selection, routing customization or batch selection exists. Reject
malformed/non-normalized/relative/outside paths, directories, symlinks, unknown
identity, unexpected changes and known temporary/in-progress names. A user must
confirm downloading is finished; that is not a formal readiness guarantee.

The coordinator requires its adapter to enforce actual rooted identity and
no-follow I/O, not just lexical checks. The Android adapter cannot currently
certify those mutation semantics and fails closed. Fixture roots are private,
exclusively controlled test directories; their synchronization assumptions must
not be generalized to user-writable Android shared storage.

## No-overwrite publication and verification model

Intent precedes the first mutation. A journaled `.s26-<operation UUID>.part` is
exclusively created before bounded copying. The real-I/O fixture adapter uses a
64 KiB buffer, FileChannel.force(true), SHA-256 and before/after identity, size,
mtime/ctime evidence. The coordinator verifies copied size/hash, rereads source
evidence and revalidates before removal. The mutation I/O implementations remain
**fixture-only**; the APK has no fallback path-based writer. The production
read-only inspector uses a 64 KiB buffer, SHA-256, descriptor fstat evidence,
no-follow child opens and post-read identity/stamp/path checks. It reads no more
than its allowance plus one growth/EOF sentinel per file, checks permission and
cancellation during reads, and never treats a snapshot as a deletion receipt.
See the [public API review](ANDROID_STORAGE_REVIEW.md) for its limits.

Fixture publication uses atomic exclusive hard-link creation to a new final
name; existing names cause retry with `filename (1).ext`, etc. It never renames
onto an occupied destination, truncates it or uses replacement options. Candidate
intent is saved before each publication attempt. Numbering preserves the last
extension, extensionless/dotfile names and Unicode; an overlong stem is shortened
at UTF-8 code-point boundaries. Impossible suffix/path budgets or exhausted
collision space fail closed. Failed exclusive stage allocation never adopts an
existing file. Test races use actual concurrent filesystem link calls.

A published hard-link staging alias is deliberately retained and journaled,
including after a fixture completion. There is no path-based automatic cleanup,
even of apparently app-owned names. This is additional tracked output, not a
claim of a polished operational storage layout. Android/FUSE hard-link support
is not assumed; no production publication adapter is approved. A future adapter
using non-atomic final publication must explicitly journal partial final output
and preserve the source until its own safety proof is accepted.

## Source deletion and durability

A metadata/hash check followed by path deletion has a TOCTOU gap: another app
could replace the path between those calls. Neither a quick check, advisory lock,
ATOMIC_MOVE nor a private-looking filename fixes this. There is no approved
Android compare-identity-and-unlink primitive here. The shipping adapter cannot
delete; the fixture's synchronized removal only models that contract in a closed
world. Core tests of COMPLETED do not establish a real Android safe-delete capability.

Without conditional removal or directory-sync support, verified publication must
remain **COPY_ONLY**, with the original retained and recovery needed. COPY_ONLY
is not a move and never enables Undo. File sync, directory sync, copied bytes,
verified bytes, publication, removal, source-directory sync and journal completion
are distinct evidence. SQLite requests synchronous=FULL; this and FileChannel
sync are requests to the OS/storage stack, not universal sudden-power-loss guarantees.
Filesystem and SQLite never share an atomic transaction.

## Journal, schema and concurrency

App-private Room database `operation-journal.db`, exported schema version **2**:
operations, append-only phase events and a singleton durable recovery cursor. Records include UUID/type/related move,
source/target/stage paths, original name, identity/change evidence, size/hash,
phase/error, recovery flag, timestamps/revision, copied/verified/published/sync/
removal evidence. Complete contents are never stored. Sensitive path/hash metadata
stays local. No analytics or network permission exists.

Short transactions claim intent, or compare-and-set the revision/update the row
and append its phase event. Saves validate the previous event tail, legal phase
transitions, monotone evidence, immutable paths/source identity/hash, source-claim
retention and precise revision increments. New intents cannot inject completion
evidence; Undo claims require an eligible parent in the same transaction. They
never span copying. A unique source token blocks
already-active/handled identity requests; a unique related ID blocks repeated
Undo. UUID replay returns the same record without repeating writes. One coordinator
serializes user actions; DB constraints also protect separate coordinator instances.
Journal read/write/acknowledgment failures stop further operations and leave
last-known evidence for reconciliation. Room updates run off the main thread.

Normal sequence (MOVE and UNDO share phases):

```text
PREPARED -> ALLOCATING -> COPYING -> COPY_VERIFIED -> DESTINATION_COMMITTED
DESTINATION_COMMITTED -> COPY_ONLY (retain source; do not resume deletion)
DESTINATION_COMMITTED -> SOURCE_REMOVAL_PENDING -> SOURCE_REMOVED -> COMPLETED
Any ambiguous in-flight phase -> RECOVERY_REQUIRED
```

Any ambiguous failure becomes RECOVERY_REQUIRED. FAILED_SAFE requires evidence
that the original is intact and no output remains (or no mutation was attempted).
COMPLETED requires recorded verification/publication, conditional removal,
source-directory synchronization and journal finalization. It is never inferred
from a successful copy alone. Events retain phase chronology by revision even
when wall-clock times are not useful; history prioritizes unresolved records.

Schema 1 remains unchanged. Explicit migration 1→2 creates the cursor table and
quarantines legacy COMPLETED rows lacking adapter-confirmed removal, appending
a RECOVERY_REQUIRED event, clearing completion time and preserving claims/data.
Already confirmed rows and pending operations retain their evidence and events.
Native SQLite tests reopen/migrate persisted fixture data and verify the export
identity hash. Every future schema change must retain exports, increment the
version and add a reviewed/tested versioned migration. Missing migrations fail closed. There is no
destructive migration or automatic database reset. CI detects exported schema
drift; tests compare the Room database identity hash to the versioned export.

## Bounded, evidence-only reconciliation

Launch or **Check recovery** considers at most 20 pending records and a 64 MiB
aggregate verification budget (source/stage/destination), plus at most one
growth/EOF sentinel per observation. A persistent UUID keyset cursor with a
fixed cycle ceiling advances even for oversized, unchanged, or row-write-failed
records. New inserts cannot indefinitely extend the active cycle. Each call
processes one page and wraps at most once; subsequent launches/manual checks
continue. Oversized work receives RECOVERY_BUDGET and remains visible for manual
review; it is not silently abandoned or hashed without a bound. Cancellation
retains the current position; total database unavailability stops reconciliation.
Per-row save failures are reported while later rows receive attention. No directory-wide orphan discovery, retry, deletion or Undo
occurs. Repeated equivalent evidence does not append duplicate transitions.

| Evidence | Conservative outcome |
| --- | --- |
| Permission missing | Defer; retain journal |
| Untrusted identity/observation | RECOVERY_REQUIRED; never certify completion |
| Original intact; no stage/final output | FAILED_SAFE; explicit future retry only |
| Partial or uncommitted output | Preserve all paths; RECOVERY_REQUIRED |
| Verified original and destination both present | COPY_ONLY; no automatic removal/Undo |
| Original absent; verified destination, even with recorded removal/sync flags | RECOVERY_REQUIRED / REMOVAL_UNCONFIRMED; absence is not an app removal receipt |
| Both original and output missing | Serious unresolved failure |
| A tracked path contains another file/modified content | Conflict; never replace or silently clean up |

Reconciliation never transitions an interrupted row to COMPLETED. A crash can
separate intent, actual unlink, removal-evidence persistence, directory sync and
finalization. External deletion/rename or stale flags cannot establish an
unbroken app-controlled removal chain. Only uninterrupted coordinator execution
with an adapter-confirmed conditional removal, sync and valid journal succession
can finalize in the closed-world fixture model. Recovery preserves all remaining
files; even SOURCE_REMOVED rows stay unresolved. Empty history is honest. Normal SQLite
reopen and phase-fault simulations are not proof of reboot/power-loss durability.

## Undo and UI

Only a completed forward record with verified published output, recorded removal/
sync, no recovery flag and no prior reverse request is eligible. Undo rechecks
organized identity, stamp, size and hash; modified organized files are rejected.
Its own UUID/related ID, intent, exclusive staging/publication, verification,
removal policy and recovery are identical to forward operations. Occupied original
names require explicit unique-name restoration consent or Cancel. There is no
Replace/Overwrite and no automatic Undo of copy-only results.

The existing Compose dashboard adds Review move, an explicit finished-download
warning, gate-aware confirmation, busy/error/copy-only/recovery messages and a
lazy history (100 records, unresolved first). Actual unresolved count remains
visible. Pausing clears selection/cancels action work. History and the journal
are not deleted when storage permission is revoked. Confirm/Undo stay disabled
in the installed engineering APK; fake success controls/data are not introduced.

## Signing and retention gates

See [README signing procedure](../README.md#stable-development-signing-and-journal-retention).
Normal APKs use ephemeral debug signing and are labeled engineering-only. A
separate protected-environment job can re-sign a verified main/manual build with
owner-provisioned secrets and verify an independently pinned certificate. PRs
cannot access that job's secrets through the workflow. Protection requires owner
configuration of the environment and main-only branch rules; actual provisioning
has not been verified. The signing script additionally rejects PR/non-main CI
contexts and requires a unique compiled boolean-false mutation marker. Missing credentials/pin mismatch fail, with no
stable-signing fallback. Disposable-key tests check this tooling, not owner setup.
Even the optional stable-signed engineering APK remains mutation-disabled.

Stable signatures support updates, not protection from uninstall/Clear data,
device loss or hardware failure. Journal backup/device transfer are excluded;
restoring a stale journal onto a different filesystem could be dangerous. No
remote backup/export system is implemented. Do not uninstall a nonempty journal.

## Future approved architecture

The destination remains unified Organized/ with Installers, Documents, Images,
Audio, Videos, Archives and Other. Builds 4–7 retain conservative readiness,
local classification, rules-based routing, hybrid opportunistic events plus
WorkManager reconciliation, persistent history/Undo, grouped notification batches,
and UI refinement. A permanently running dataSync foreground service is not a
requirement. Preserve duplicates, exclude destinations as sources, and revalidate
before operations. Readiness now contains pure evidence policies; classification/routing remain future work.

Media index synchronization is considered but deferred while Android mutations
are disabled; future best-effort indexing cannot establish movement or update
every browser/third-party download database. No four-tab UI redesign, AMOLED restyle, automatic organizing, notifications or remote service is implemented. The isolated telemetry variant adds only read-only jobs/work and a boot/package restoration receiver. Independent Jinx audit and owner hardware acceptance remain pending.

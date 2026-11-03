# S26 Download Organizer architecture

## Baseline and current release status

Build 1 established the launchable Compose application and CI. Build 2 was merged
at `86082b4b887a82d66cef41ea8072428d50f7f254` via PR #2 and confirmed working on the
S26 Ultra/Android 17 by KroflaFPS. Build 3 preserves that permission/inventory code.

Build 3.1 / PR #3 was merged at `08dddedf9241b403de738787e5c6ec5297429e30`.
Build 3.2 adds a separate, explicitly initiated **synthetic-fixture-only copy
capability**. Generic MOVE/UNDO and the Android operation writer/removal stay
disabled. The diagnostic implementation is experimental; Samsung API behavior,
installer upgrade retention and hardware durability are unverified.

See [Build 3.2 capability/state/device review](BUILD_3_2_DEVICE_TEST.md). It narrows
source access to two exact reproducible fixture hashes inside the dedicated test
input folder and writes only newly exclusively allocated files inside the exact
test output folder. It performs non-atomic publication with tracked partial final
files, never a replacing rename or deletion. No legacy state-machine weakening.

## Toolchain and persistence dependencies

One `:app` module; application ID `com.kroflafps.s26downloadorganizer`.
AGP 9.5.0, Gradle 9.8.2, JDK 21, built-in Kotlin/Compose compiler 2.2.10,
Compose BOM 2026.09.00, Material 3, AndroidX Core 1.19.1, Activity 1.13.0,
Lifecycle 2.11.0, coroutines 1.10.2, compile/target API 37, minimum API 26 and
JUnit 4.13.2 are retained. Version code/name: 6/0.3.2-diagnostic for the standalone diagnostic; normal versionName remains 0.3.2.

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
No SD card/work-profile/virtual-provider inventory or recursive scanning is added.

## Operation boundaries and roots

`operations` contains platform-independent records, policies, ports, coordinator
and recovery logic. `history` isolates Room. `storage` retains inventory/adapters;
`ui` handles observable state/presentation. `OrganizerApplication` owns one journal
and one coordinator. No DI framework, WorkManager or service is introduced.
Room's unused multi-instance invalidation service is explicitly removed from the
merged manifest; source/merged allowlist tests continue rejecting runtime services.

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

App-private Room database `operation-journal.db`, exported schema version **3**:
legacy operations/events/cursor plus separate diagnostic copies/events. Cursor
slot 0 retains legacy recovery; slot 1 serves the diagnostic recovery cycle. Records include UUID/type/related move,
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

Schemas 1 and 2 remain unchanged. Migration 2→3 adds the separate diagnostic
tables and a unique unresolved-source claim; the legacy tables/events are
preserved. Both upgrade paths and reopened diagnostic data are tested.
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
The optional stable-signed diagnostic APK has the same narrow synthetic-copy
capability; signing cannot enable generic MOVE/UNDO. Public certificate reports
and optional prior-APK comparison do not prove device installer/data-retention
behavior. Any mismatch stops testing without uninstall/data clearing.

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
before operations. Future packages detection/processing/readiness/classification/
routing are not populated prematurely.

Media index synchronization is considered but deferred while Android mutations
are disabled; future best-effort indexing cannot establish movement or update
every browser/third-party download database. No four-tab UI redesign, AMOLED
restyle, monitoring, notifications, services, boot receiver or remote service is
implemented. Independent Jinx audit and owner hardware acceptance remain pending.

## Separate Build 3.2 diagnostic capability

The `diagnostic` coordinator, bounded streaming/verification implementation and
Room records are separate from MOVE/UNDO. `AndroidDiagnosticCopies` exposes no
caller-supplied root/path; its public factory resolves primary storage itself.
The existing read-only AndroidOperationFiles and mutation constant are unchanged.
A dedicated diagnostic build type, exact diagnostic application-ID check and
platform/storage/permission checks gate the new capability independently. Normal
debug and release disable it. No test-only filesystem backend
is packaged. UI explicitly inspects two known fixture names, then shows exact
paths and warnings before each user-confirmed copy; it never scans recursively.

Writable file descriptors exist only after O_CREAT|O_EXCL|O_NOFOLLOW returns a
fresh regular file in an anchored output directory. They are retained; the app
never reopens an existing stage/final path writable. SHA-256 and source identity/
stamp checks bracket both bounded copies; file/directory sync is required before
finalization. The stage is retained, and the final name can be partial during
writing. No content-atomic publication claim. Any failed intent/phase write,
cancellation, missing permission, unsupported sync or verification conflict
prevents a success response and retains recorded paths. Recovery performs only
bounded observations/journal updates and cannot turn interruption into success.

Sources must match the exact fixed synthetic hashes, not merely a test-looking
name. This is not file classification/readiness or an arbitrary Downloads copy
feature. Nothing deletes any original/output, implements Undo cleanup, watches
files or schedules work. Directory relocation/adversarial races and sudden-loss
durability remain limitations requiring separate review; see the full device
experiment document for the exact runtime checks and stop conditions.

## Standalone diagnostic identity and signing-blocker repair

The owner reported that Build 3.2's original same-package APK could not update
Build 3.1 because their certificates differ. This repair keeps the production
application ID unchanged and adds `.diagnostic` only to a dedicated diagnostic
build type. The namespace/code package remains unchanged. The launcher/app title
is S26 Organizer Diagnostic, and its Settings instructions identify that app.
Normal debug and release use the original ID, disable synthetic-copy controls and
cannot pass the Android diagnostic runtime gate. CI builds normal debug for
regression/package checks and uploads only the standalone diagnostic APK.

Each package has its own context/UID and app-private Room journal, with no shared
UID, cross-app provider/export/import or private-data copying. Startup-provider
authorities and AndroidX signature permissions derive from each application ID.
Room schema/migrations stay unchanged; separate installation does not open or
migrate installed Build 3.1's database. Storage settings use activity.packageName,
so grant All Files Access to the diagnostic separately. Shared storage remains
shared, with exactly the same synthetic-fixture and exclusive-allocation rules.

The new APK need not match Build 3.1's certificate for first parallel installation.
Later updates to the same diagnostic ID still require certificate continuity.
No credentials are required for this experiment; no source deletion or generic
mutation gate was enabled. The [signing investigation](SIGNING_STRATEGY.md)
documents the future main-key plan and why a new key cannot replace the installed
main key without a separately reviewed continuity/migration solution. Device
coinstallation/private-journal isolation and copying still require owner testing.

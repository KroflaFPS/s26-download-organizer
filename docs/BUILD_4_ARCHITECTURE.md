# Build 4: read-only trigger and readiness telemetry

## Status and isolation

This is an evidence-gathering experiment, not an organizer. Shared storage is
metadata-read-only. The main application ID and legacy journal v2 are unchanged.
The `telemetry` build type uses `com.kroflafps.s26downloadorganizer.telemetry`,
label **S26 Read-Only Telemetry**, version 7 / 0.4.0-telemetry. Android assigns it
its own UID, All Files Access grant, private databases, jobs and standby bucket.
It neither opens nor migrates either installed Build 3.1 or Build 3.2 database.
Backup/device transfer exclusions remain in force. Its behavior cannot certify
identical wake coverage under the main application's standby history.

Build 3.1 is merged at `08dddedf9241b403de738787e5c6ec5297429e30`.
Build 3.2 PR #4 remains draft/unmerged at `7cd4811`; it is not this branch's base.
KroflaFPS reports successful side-by-side installation, both synthetic fixture
copies, source preservation, collisions, journal/recovery and permission denial
on the S26 Ultra. These are owner reports, not independent hardware durability
certification. No Build 3.2 copying implementation is packaged here.

## Why native triggers plus deferred reconciliation

MediaStore notifications are possible change signals. They are neither a complete
filesystem event stream nor evidence that a writer closed a file. Direct writes,
provider visibility, coalescing, quotas, Doze and Samsung policy can miss/delay
notifications. We watch Downloads and Files external URIs with descendant flags;
Files also produces camera/screenshot false positives.

**Audit status: OWNER DEVICE ACCEPTANCE AND INDEPENDENT ADA RE-AUDIT PENDING.**
B4-01 identified a real defect in the original `0629888` implementation: scheduling
4001 before its jobFinished could stop that same running job. That approach and
its callback-only justification are withdrawn.

Native one-shot jobs now reserve **fresh increasing IDs starting at 20000**;
WorkManager retains 10000–19999. A process-wide lock serializes registration in
this single-process app. The next-ID reservation is committed to private preferences
before schedule(), above both the completing callback's ID and all registered
native IDs. A restart skips a reserved-but-unscheduled ID. Failed persistence,
failed registration or integer exhaustion fails registration without fixed-ID
fallback/wraparound. Normal registration preserves any existing native job;
completion schedules only if no higher-ID successor exists. Discovery/cancellation
is by the exact JobService component, including legacy 4001, not by every app job.

After bounded work, the service registers a distinct-ID successor, then sends
jobFinished for the old instance. Android explicitly stops a running job when
schedule() replaces its ID; **the successor never uses that ID**. Calling
jobFinished then immediately reusing an ID is not used either: AOSP JobServiceEngine
posts MSG_JOB_FINISHED, so the call is not a synchronous acknowledgement. This
strategy does not depend on predicting when that acknowledgement is processed.
Update/max-delay hints remain 2/15 seconds, not a delivery SLA. Content-trigger
jobs are neither periodic nor persisted. Application.onCreate does not register
native work because a job may itself be cold-starting the process.

Stop during work/rearming cancels that run and suppresses jobFinished. Late stops
and duplicate finalization cannot finish/cancel a newer token's run. Framework
retry is requested only while enabled, readable and without an existing successor;
superseded old callbacks do no scanning. Pause cancels only this component's native
registrations plus its named WorkManager work. Permission denial prevents reads
and new registration. Fresh job IDs do not inherit changed-URI data from old IDs;
changes before the successor registration can be missed and require reconciliation.

Process death before schedule may leave only the interrupted job/framework retry;
death after schedule leaves the already registered successor. Durable metadata
checkpoints and periodic/manual reconciliation are the fallback, not a delayed
in-process callback. Reboot/package restoration reserves a fresh ID after consent;
force-stop still requires manual launch. Scheduling cost, quotas, OEM behavior and
provider coverage can delay/fail registration or delivery. No immediate-delivery
or universal crash-recovery guarantee is claimed.

Robolectric tests exercise the actual registration adapter's registry/IDs, skipped
reservations, legacy registrations, stale counters/callbacks, consent and revocation.
Lifecycle regressions fail on the old implementation. They do **not** simulate a
live Android stop/finish acknowledgement pipeline. Multiple real consecutive
content callbacks and interrupt/rearm cases are mandatory S26 acceptance gates
(B4-03/B4-04); a pending-job snapshot alone is insufficient.

A unique KEEP PeriodicWorkRequest runs approximately every two hours, with its
first request delayed two hours. WorkManager 2.12.0 is stable, min API 24, fitting
this project's min API 26; other dependency/toolchain versions are retained.
It initializes only in the supported telemetry variant and reserves job IDs
10000–19999, separate from the native fresh-ID range. A unique non-expedited delayed worker
continues pending pages/samples. It retries at most seven times after its first
attempt with linear 30-second backoff. OS timing may be much later. Remaining
work is durable for the next manual, external or periodic request. There is no
permanent polling loop, foreground service or notification. Boot/package-update
receivers request reconciliation and restore registration only after prior user
consent. Delivery under restricted policy is not guaranteed. Force-stop prevents
execution until manual launch. No exemption intent or notification permission.

**B4-02 retained limitation, reproduced with WorkManager 2.12.0:** when a RUNNING
unique drain has already computed more=false, a new dirty request plus KEEP is
ignored before the old worker reports success. The dirty generation survives but
no new drain is queued. It may wait for another signal/manual scan or the next
approximately two-hour periodic request **plus OS deferral**. There is no hard
upper latency bound, especially under Doze/restrictions/force-stop. The regression
holds an actual WorkManager worker future, verifies RUNNING, injects the DAO request,
then verifies no successor and successful later reconciliation. KEEP is unchanged;
no unbounded APPEND chain, replace/cancel churn or polling loop was introduced.


## Bounded scanning and durable coordination

The existing Build 2 flat inventory is preserved. The separate telemetry scanner
inspects metadata in primary Downloads and bounded subfolders, never Organized.
It uses supported Android Environment APIs for the primary root, no-follow
directory descriptors and /proc descriptor anchors. It fails a page if the anchor
or directory identity cannot be validated. No user file contents are opened,
hashed or parsed by this scanner. SHA-256 is used only to salt/hash relative path
keys for local observation comparison, not for file content.

Each invocation handles one page of at most 128 names. Directory enumeration
stops at 4096 names or a five-second deadline; depth is at most four; relative
path length at most 1024; the persisted directory queue at most 128. Links are
not followed. Sorted lexical cursors continue pages. A fresh requested cycle
starts at Downloads regardless of top-level mtime, so additions before a cursor
and changes in existing subfolders can be observed in subsequent cycles. Bounds,
unreadable directories and unsupported metadata produce visible warnings and
LIMITED_OR_UNREADABLE coverage; they never imply a complete inventory. An
unreadable directory advances with a warning so siblings are not starved.
Directories exceeding enumeration/queue bounds may remain partially uncovered;
this is an explicit experimental limitation, not a complete recursive scanner.

A short Room transaction increments requested generation before scanning. A
30-second boot-aware lease and owner-token commit fence coordinate distinct
coordinator instances; an in-memory mutex alone is insufficient. Page results,
observations, next cursor, children and event are committed atomically in a short
DB transaction. No transaction spans metadata I/O. Requests arriving during a
cycle remain dirty for the next cycle. Expired/replaced lease writers cannot
commit or release a newer lease. Cancellation, permission loss and DB failures
retain checkpoints. Process death may repeat a page, but repeated observations
within the minimum sample gap do not manufacture quiescence evidence. SQLite
reopen tests are not device power-loss durability proof.

URI hints query at most 16 item URIs, with per-query cancellation and an overall
three-second deadline. A definitely outside-Downloads hint skips immediate
scanning but still persists a dirty generation and schedules deferred inspection.
Unknown/directory/coalesced hints scan conservatively. Each page makes at most
32 optional IS_PENDING queries with one-second query cancellation. Ambiguous,
missing or inaccessible provider rows give unknown evidence. Binder/provider
cancellation responsiveness and FUSE/proc behavior need target-device testing. Deadlines are cooperative checks between metadata calls; a blocked kernel/provider call cannot be promised to finish within that deadline. Job stop/cancellation prevents committing later unapproved observations, but is not a hard real-time I/O bound.

## Readiness evidence, never operation authorization

| Label | Meaning |
| --- | --- |
| UNKNOWN | Insufficient metadata, clock uncertainty or not enough separated samples |
| NOT_READY | Known temporary name, visible IS_PENDING, or a changed identity/size/mtime |
| POSSIBLY_READY | At least three observations, at least 15 seconds apart and 30 seconds quiet, without provider publication evidence |
| READY_WITH_EVIDENCE | Same quiet evidence plus a uniquely matched provider row reporting IS_PENDING=0 |

Every label has `authorizesMutation=false`. A published provider row can be stale;
a paused writer can look quiet; identical size/mtime/inode can conceal changes.
No file lock, exclusivity, content integrity, writer-closure or deletion safety is
claimed. Zero bytes alone is neither a hard negative nor a completion proof.
Boot changes reset comparison; monotonic elapsed time prevents wall-clock jumps
from manufacturing a quiet window. Observations include reasons and a deferred
check time; worker retry limits prevent indefinite aggressive polling.

No footer checks are implemented: they are optional here and would add file
content reads without proving closure. A corrupt ZIP/PDF/media file may therefore
be metadata-quiet. Classification is Build 5, and eventual mutation authorization
must independently revalidate identity/content and satisfy its own safety gates.

## Privacy, retention and schema policy

`readonly-telemetry.db` schema 1 is separately exported/versioned. The main
journal's schemas 1 and 2 and migration are unchanged. No destructive fallback.
Future telemetry schema versions require explicit, tested migrations. Unknown
version tests confirm failure without deleting existing event data.

Events retain at most 400 rows and seven days; observations at most 4000 rows
and seven days, pruned on subsequent writes. Paused databases are not purged by
a background timer. Raw relative paths/cursors exist only in the app-private
bounded directory queue; observation keys are per-install salted path hashes.
No raw URI, filename, full path or exception message enters event text/export.
No network permission, analytics, cloud upload or richer path export exists.

Clipboard/optional Android Sharesheet export includes fixed allowlisted codes,
UTC times, API/variant, aggregate readiness counts, page durations, registration
snapshot and battery-policy snapshots. Retained observations are not a live
inventory: disappeared entries can remain until retention. Exporting requires a
tap; the owner chooses any recipient. Inspect even redacted text before sharing.
`wake time - newest metadata mtime` is explicitly a rough heuristic, **not actual
event/completion-to-wake latency**. No source-specific event timestamp is known.

## Build matrix, signing and integration with PR #4

| Artifact/build | ID suffix | Generic mutation | Diagnostic copy | Telemetry |
| --- | --- | --- | --- | --- |
| Normal debug/release (not owner artifact) | none | false | false | false |
| s26-build-4-readonly-telemetry | .telemetry | false | false | true |
| Optional protected stable-signed read-only telemetry | .telemetry | false | false | true |
| JVM fixture adapters | test process only | fixtures only | absent | fake/disposable metadata |

The strict actual-APK check verifies package/label, permission allowlist, compiled
false mutation/copy markers and absence of diagnostic/test implementations in DEX.
Telemetry sources and WorkManager runtime are excluded from normal builds.
The first telemetry install does not need either existing package's certificate.
Subsequent same-ID updates require the same signing certificate. Do not uninstall
or clear any app's data to bypass a mismatch. Ordinary CI uses an ephemeral debug
key, reported in certificate.txt. Optional stable signing remains manual/main-only,
protected-environment/pin gated, fail-closed on missing credentials; no credentials
are requested for this experiment. Signing cannot enable mutation. Actual owner
provisioning and in-place retention testing remain unverified.

Integration plan: leave PR #4 and its schema 3 untouched. Review/merge decisions
belong to the owner. If PR #4 lands first, integrate on this branch only after
review: preserve journal migration 2→3, keep this separate telemetry DB at 1,
resolve version codes monotonically and retain all per-variant gates. Move the
Build 3.2 copy classes into its diagnostic-only source set (or enforce equivalent
compiled exclusion) before combining so the telemetry DEX guard still passes.
The diagnostic variant may enable only its exact-fixture copy flag; telemetry
must retain copy=false and main must retain both flags=false. Re-run both variant
suites, schema checks, package/signing checks and independent audit. Do not
cherry-pick telemetry flags into a production mutation build.

## References reviewed

- [Android background optimization: content URI triggers and rescheduling](https://developer.android.com/topic/performance/background-optimization#content-trigger)
- [JobInfo.TriggerContentUri](https://developer.android.com/reference/android/app/job/JobInfo.TriggerContentUri)
- [JobScheduler.schedule](https://developer.android.com/reference/android/app/job/JobScheduler#schedule(android.app.job.JobInfo)): replacing a running same-ID job stops it.
- [AOSP JobServiceEngine](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/apex/jobscheduler/framework/java/android/app/job/JobServiceEngine.java): jobFinished enqueues a message; not a completion acknowledgement.
- [ExistingWorkPolicy.KEEP](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy#KEEP): unfinished unique work causes the new request to be ignored.
- [JobService lifecycle](https://developer.android.com/reference/android/app/job/JobService)
- [AOSP ContentObserverController (official mirror)](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/apex/jobscheduler/service/java/com/android/server/job/controllers/ContentObserverController.java): changed URI/authority transfer during replacement/failure; not an OEM coverage guarantee.
- [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work): stable 2.12.0, minimum API 24.
- [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest): inexact, constraints and Doze dependent.

Before Build 5/6: obtain independent review and measured S26 logs; decide whether
signal coverage and bounded traversal are adequate, define classifier/content
budgets, integrate the separate variants safely and resolve signing and Android
publication/deletion constraints independently. No production automation approval.

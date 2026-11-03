# Changelog

All notable changes to the S26 Ultra Download Organizer project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Repository initialization on GitHub with multi-AI documentation structure.
- `ARCHITECTURE.md` specifying modules, Android permissions, and team roles.
- `ROADMAP.md` tracking planned milestones.

### Build 4 targeted Ada audit repairs (2026-11-03)

- Repair B4-01 on the existing draft PR #6, starting at `0629888`; no branch
  switch/reset/rebase, PR #4 modification, signing setup or feature build.
- Withdraw the unsafe same-ID-before-jobFinished rearm claim. Reserve fresh
  native IDs durably before scheduling, outside WorkManager's range, and avoid
  replacing any still-running/unacknowledged ID. Preserve legacy registrations,
  component-scoped pause, stale callback handling and fail-closed allocation.
- Correct stop-during-rearm and duplicate-finish state transitions. Two new
  lifecycle regressions failed against the audit baseline before repair.
- Reproduce B4-02 with WorkManager 2.12 KEEP, a held RUNNING worker future and the
  real telemetry DAO/coordinator. Retain KEEP and document the delayed-drain
  limitation: dirty state survives, but may wait for later signal/manual/inexact
  ~2h periodic work plus OS deferral; no maximum latency is promised.
- Add registration/reservation/concurrency/permission regression coverage,
  queue-saturation coverage and scanner-error redaction assertions. No scanner,
  schema, manifest, dependency, feature-gate or credential changes.
- Update architecture and mandatory phone-only repeated-callback/root/nested/
  stop/revoke/reboot/export checks. **OWNER DEVICE ACCEPTANCE AND INDEPENDENT ADA
  RE-AUDIT PENDING.** JVM/Robolectric do not prove live Android/FUSE behavior.
- Add a telemetry-only unavailable-primary-volume guard: platform access-check
  exceptions deny reads/registration rather than escaping the service/UI path.
  The original main storage adapter remains unchanged.
- Local repaired-head verification: 95 normal + 122 telemetry JVM executions,
  zero failures/errors/skips; lint zero errors, 5 normal/8 telemetry advisory
  warnings. Fresh APK/signing/schema/CI evidence is recorded in the PR handoff.

### Build 4 — Read-only Ghost Bot/readiness telemetry (2026-11-03)

- Carry forward the uncommitted Build 4 work on `feat/build-4-readonly-telemetry`,
  based on Build 3.1 main `08ddded`. Fetch/read-only inspection confirmed main's
  later skill-only merge `354000e`; `.agents/skills/karpathy-guidelines/SKILL.md`
  was explicitly read from origin/main, not natively discovered or modified.
- Add the independently installable `telemetry` build type, package
  `com.kroflafps.s26downloadorganizer.telemetry`, S26 Read-Only Telemetry label,
  versionCode 7 / 0.4.0-telemetry. Preserve normal application identity and legacy
  journal v2. Generic mutation and diagnostic copy remain false in every APK.
- Add opt-in one-shot Downloads/Files descendant content triggers, completion-time
  rearming with registration tokens, stop cancellation, unique inexact ~2h
  reconciliation and consent-aware boot/package restoration. WorkManager 2.12.0
  is telemetry-only; no foreground service/notification/exemption/network access.
- Add metadata-only bounded no-follow directory pages, lexical cursors, durable
  dirty generations/boot-aware lease fencing, explicit limited coverage and
  unreadable-directory fairness. No file content scanning/hashing or user writes.
- Add pure readiness evidence states with temporary/pending/change negatives,
  separated quiet samples, clock/boot handling and zero-byte ambiguity. Every
  state denies mutation authorization; no footer parser or closure claim.
- Add a separate Room telemetry schema 1, bounded events/observations, local
  status and functional enable/pause/manual/registration controls, redacted
  Markdown clipboard and optional sharesheet. Default exports omit private
  names/paths/full URIs and label mtime timing as a heuristic.
- Add readiness, scheduling/lifecycle, coordinator/checkpoint/lease/reopen,
  retention/schema-failure, permission/cancellation, pagination and export tests.
  Preserve all Build 1–3.1 tests; extend actual merged-manifest and APK guards.
- Extend CI to verify both variants and upload only the read-only telemetry APK
  plus public package/certificate report. Preserve protected manual/main signing
  and fail-closed credential/pin checks; no owner credentials requested/created.
- Add architecture/reference/integration notes and phone-only acceptance steps.
  PR #4 remains draft/unmodified. Record Build 3.2 device results as owner-reported
  synthetic-copy checks, not independent certification. Build 4 device acceptance
  remains pending. Local JVM verification: 95 normal + 111 telemetry executions,
  zero failures/errors/skips. Both lint variants pass with zero errors (5 normal,
  7 telemetry advisory warnings). Schema and actionlint checks pass. Final APK,
  signing and published CI evidence are reported in the Build 4 draft PR.

### Build 1 — Foundation and cloud CI/CD (2026-11-02)
- One Android application module for `com.kroflafps.s26downloadorganizer`, using
  Gradle Kotlin DSL, a version catalog, and the complete checksum-pinned wrapper.
- Stable Android 17/API 37 toolchain with minimum API 26 and JDK 21.
- Launchable Material 3 Compose dashboard displaying the app title, Build 1,
  Foundation ready, and an explicit inactive-organization notice.
- System light/dark themes, safe screen insets, scrolling, and a launcher icon.
- JVM tests of the source manifest's permission/background-component boundary
  and exported dashboard launcher declaration.
- GitHub Actions workflow for wrapper validation, Gradle caching, JVM tests,
  Android lint, `assembleDebug`, and debug APK/report artifacts retained for 14 days.
- Cloud build and Samsung APK installation instructions in the README.

### Changed
- Replaced the permanent foreground-service assumption with planned opportunistic
  event detection plus deferred WorkManager reconciliation.
- Documented provisional future all-files access, the Organized/ destination root,
  grouped notifications, duplicate preservation, safe readiness/moves, and journaled Undo.
- Reordered the roadmap into seven milestones, separating implemented foundation
  work from future storage, operation, classification, and automation work.

Build 1 does not access user downloads, request permissions, organize files,
run background automation, or post notifications. Device installation/runtime
acceptance remains the owner's responsibility; no device test is claimed.

### Build 1 validation
- Cloud workspace: `testDebugUnitTest lintDebug assembleDebug` succeeded; two JVM
  tests passed with zero failures/errors. APK signature and 16 KB ZIP alignment
  verification passed; packaged identity is correct (minimum API 26, target 37).
- Workflow syntax passed actionlint; wrapper JAR matches Gradle's published SHA-256.
- Lint: zero errors, two advisory warnings (newer Compose compiler available and
  Android 12+ backup rules). Kotlin/Compose compiler deliberately match AGP's
  bundled Kotlin; Build 1 stores no organizer data and disables legacy backup.
- Gradle reports dependency/plugin deprecations affecting a future Gradle upgrade;
  the pinned Gradle 9.8.2 build succeeds without suppressing checks.
- Published [PR #1](https://github.com/KroflaFPS/s26-download-organizer/pull/1) from
  `feat/build-1-foundation`. The [pull-request Actions run](https://github.com/KroflaFPS/s26-download-organizer/actions/runs/41511540170)
  for implementation commit `aa929db` passed tests, lint, compilation, and artifact
  uploads. Confirmed the non-expired `s26-download-organizer-build-1-debug` APK
  artifact and `build-1-verification-reports` artifact, retained until 2026-11-16.


### Build 2 — Storage access and read-only Downloads inventory
- Baseline: Build 1 was merged via PR #1 and owner-tested on the S26 Ultra,
  as reported by KroflaFPS. Toolchain and one-module structure are preserved.
- Bumped versionCode to 2/versionName to 0.2.0 and dashboard/artifact names to Build 2.
- Added only MANAGE_EXTERNAL_STORAGE, with an explicit explanation, user-initiated
  package-specific Settings intent, ordered general/app-details fallback, and
  real grant rechecks. No ordinary runtime permission dialog or unrelated grants.
- Added accurate unsupported-inventory behavior on API 26–29, preserving launch support.
- Added Android-isolated primary Downloads resolution and a metadata-only NIO
  scanner: immediate children, file/folder/link indicators, names/extensions,
  nullable sizes/dates, deterministic ordering, no symlink traversal or recursion.
- Added normalized boundary-aware Organized/ source exclusion without creating it.
- Added ViewModel/StateFlow presentation, off-main serialized scans, duplicate-refresh
  prevention, pause clearing/cancellation, stale-result rejection, error/retry,
  partial metadata notices, empty state, and a lazy file list using the existing theme.
- Added lifecycle Compose/ViewModel at the existing version and coroutines core/test;
  no new framework, storage parser, database, worker, or service.
- Added fixture/fake-based JVM tests and explicit source/merged manifest allowlists.
- Preserved Actions tests/lint/assembleDebug and report uploads, using Build 2 artifact names.
- Updated storage rationale, compatibility limits, read-only guarantees, future
  boundaries, debug-signature guidance, and the owner's acceptance checklist.

Build 2 has no file mutation, readiness verification, classification, routing,
monitoring, journal/Undo, notification posting, or network analysis. Device
acceptance is pending KroflaFPS's testing; CI cannot prove Samsung storage behavior.


### Build 2 local verification
- `testDebugUnitTest lintDebug assembleDebug` passed in the cloud workspace:
  32 JVM tests, zero failures/errors/skips, Kotlin/Compose compilation and APK build successful.
- Lint: zero errors, five advisory warnings (approved all-files Play policy warning,
  newer Kotlin/Compose and coroutines core/test versions available, and the existing
  Android 12+ backup-rules advisory). No lint suppression or toolchain upgrade was added.
- actionlint, APK debug signature verification, and 16 KB ZIP alignment passed.
  Packaged app ID, versionCode 2/versionName 0.2.0, minimum API 26, target API 37,
  launcher, and explicit permission allowlist were verified.
- No on-device Build 2 execution or acceptance is claimed.

### Build 2 published verification
- Opened [PR #2](https://github.com/KroflaFPS/s26-download-organizer/pull/2) from
  `feat/build-2-storage-inventory` against main; left unmerged for owner review.
- [Actions implementation run](https://github.com/KroflaFPS/s26-download-organizer/actions/runs/41518395309)
  at `b6e5410` passed wrapper validation, JVM tests, Android lint and assembleDebug.
  Confirmed successful uploads and non-expired `s26-download-organizer-build-2-debug`
  and `build-2-verification-reports` artifacts. Artifacts are retained for 14 days.
- Samsung permission handling, real Downloads inventory and the read-only device
  acceptance checklist remain for KroflaFPS to verify; no device acceptance is marked complete.


### Build 3 — Journaled operations engineering subset (not operationally released)
- Baseline: Build 2 merged via PR #2 at 86082b4b887a82d66cef41ea8072428d50f7f254
  and confirmed working on the S26 Ultra/Android 17 by KroflaFPS.
- Preserved the proven toolchain, inventory, permission flow, theme and launcher.
  Incremented versionCode to 3/versionName to 0.3.0 and labeled APKs engineering-only.
- Verified official Room/KSP guidance and added stable Room/plugin 2.8.5, KSP2
  2.3.4 and test-only Robolectric 4.16.1 without upgrading Kotlin or AGP.
- Added app-private Room journal/schema v1, immutable operation evidence, short
  revision/event transactions, unique source/Undo claims, visible history and
  fail-closed missing-migration behavior. No destructive migration fallback.
- Added a portable manual coordinator and recovery/Undo logic with explicit
  intent/copy/verification/publication/removal/sync/finalization phases. Tests use
  actual exclusive create/hard-link publication, 64 KiB copying, SHA-256 and sync
  in disposable fixtures; conditional removal is a fixture-only closed-world model.
- Added conservative COPY_ONLY, FAILED_SAFE and RECOVERY_REQUIRED outcomes,
  idempotent bounded reconciliation and preserved tracked/ambiguous staging files.
- Added Review move/Undo confirmation, finished-download warning, busy/error
  states and real Room history, preserving the existing dashboard design.
- Added two independent shipping safety gates: constant-disabled authorization
  and an Android adapter with no writer/removal/trusted-recovery capability.
  No real user-file mutation or Undo is enabled, even if stable-signed.
- Added backup/device-transfer exclusions for the operation journal and removed
  Room's unused multi-instance invalidation service from the merged manifest.
- Added extensive fixture/fault-injection tests, actual Room reopen integration,
  schema-identity checks, mutation-gate tests and existing inventory regressions.
- Preserved CI tests/lint/APK checks; added versioned-schema and disposable-key
  signing checks. Optional stable signing is protected, main/manual only, pinned
  to an owner certificate, and fails without complete credentials. PRs need no secrets.
- Documented signing setup, retention risks, deletion TOCTOU, sync limitations,
  unimplemented Android capabilities and exact read-only device acceptance steps.

Build 3 is a conservative partial delivery. Stable owner signing, independent
Android filesystem audit and hardware acceptance are pending. No automatic
movement, watching, WorkManager, foreground/boot service, classification, routing,
notifications, remote backup or full UI redesign is implemented. Tests and file
sync requests do not prove sudden-power-loss or universal crash safety.


### Build 3 local verification
- `testDebugUnitTest lintDebug assembleDebug` passed: 73 JVM tests, zero failures,
  errors or skips, including native-SQLite Room integration and phase-boundary cases.
- Three disposable-key signing tests passed: repeated-key certificate continuity,
  missing-credential failure, pin-mismatch failure and temporary-private-material cleanup.
- Lint: zero errors, five advisory warnings (approved all-files policy and newer
  compiler/coroutines/KSP versions available). No lint checks were suppressed.
- APK debug signature, 16 KB ZIP alignment, application/version/SDK/launcher/
  permission metadata and absence of fixture-only mutation adapters were verified.
- actionlint validated the workflow. No owner-provisioned signing, emulator,
  device installation, Android 17 mutation test or sudden-power-loss test was performed.


### Build 3.1 — Targeted audit repairs and safe release preparation
- Continued PR #3 / feat/build-3-safe-operations from expected head
  05de66c632233991b963262bce19a26d3d30d1c6; no merge or Builds 4–7 work.
- Version code/name 4/0.3.1; Build 3.1 engineering dashboard and artifact names.
- Removed recovery's automatic completion inference from source disappearance,
  including stale removal/sync flags. Ambiguity remains RECOVERY_REQUIRED;
  copy-only and completion remain distinct and copy-only never enables Undo.
- Durable bounded keyset pagination advances past large/permanent unresolved
  records, with a fixed cycle ceiling, visible budget deferral and row-failure
  reporting. No unbounded startup hashing or repeated movement/cleanup.
- Strengthened journal revision, immutable evidence, legal transition, monotone
  phase-proof, new-intent, source/Undo claim and event-tail consistency checks.
  Duplicate Undo request IDs return their existing result without repeating I/O.
- Exported schema 2, preserving schema 1. Explicit migration preserves records,
  adds cursor state and quarantines legacy inferred completions without erasure.
- Added public-API Android no-follow descriptor read/hash observations beneath
  approved roots. SHA-256 streaming is capped, cancellation/permission-aware and
  closes descriptors. All publication, create/write and source deletion methods
  remain unavailable; no user-file mutation is enabled.
- Hardened signing preflight with trusted CI-context checks and compiled APK
  mutation-disabled marker verification. Added disposable-key/context/marker and
  test-adapter packaging checks. No owner credentials created or uploaded.
- Added migration/recreation, recovery starvation, external disappearance,
  interrupted removal, event rollback, Undo conflict and bounded-reader regressions.
- Documented public API research, explicit artifact matrix and remaining device,
  signing, no-clobber and conditional-removal blockers. Independent audit and
  real-device acceptance remain pending. No operational APK is approved.

Verification for this revision is recorded with the final PR report. Tests model
process termination and isolated I/O; they do not establish Android shared-storage
crash durability or power-loss safety. Earlier Build 3 descriptions above are
historical; the stricter Build 3.1 recovery rules supersede completion inference.

### Build 3.1 local verification
- JVM: 87 tests passed, zero failures/errors/skips, including native SQLite Room
  migration/recreation, event-transaction rollback and persisted cursor tests.
- Six Python signing/packaging tests passed using disposable credentials only.
- Kotlin/Compose/KSP compilation, testDebugUnitTest, lintDebug and assembleDebug
  succeeded. Lint has zero errors and five unchanged advisory warnings.
- Workflow actionlint, Room schema versioning, APK signature, 16 KiB alignment,
  source/merged manifest contracts and absence of test-only adapters verified.
- No owner signing credentials, emulator/device tests, hardware crash tests or
  real-file operational tests were used. All owner-facing APKs remain disabled.

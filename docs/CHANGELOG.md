# Changelog

All notable changes to the S26 Ultra Download Organizer project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Repository initialization on GitHub with multi-AI documentation structure.
- `ARCHITECTURE.md` specifying modules, Android permissions, and team roles.
- `ROADMAP.md` tracking planned milestones.

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


### Build 3.2 — Controlled synthetic-copy prototype (device acceptance pending)
- Verified main 08dddedf9241b403de738787e5c6ec5297429e30 (Build 3.1 PR #3 merged)
  and created a new feature branch; no modification/reopening of PR #3.
- Version 5/0.3.2, unchanged Android toolchain, permissions and legacy operation gates.
- Added an independent debug-only diagnostic copy coordinator/Android adapter,
  restricted to two known synthetic fixture names/sizes/hashes in the exact test
  input folder and a dedicated test output folder. Maximum 1 MiB, no recursion,
  arbitrary paths, directory creation, source writes, delete, move or Undo.
- Public descriptor-based no-follow anchored I/O, exclusive stage/final allocation,
  bounded streaming, SHA-256 and identity/change checks, file/directory sync.
  Final publication is deliberately non-atomic, with tracked partial final names;
  no replacing rename/hard-link assumption or unsafe fallback.
- Added independent diagnostic records/events and cursor, exported schema 3 and
  explicit migration 2→3 preserving legacy data and 1→2 recovery repair.
- Explicit review/confirmation, copy-only result, progress, retained stages/partial
  outputs, separate history and manual bounded evidence-only recovery. Interrupted
  records are never silently promoted to success or cleaned up.
- Added real disposable-file, concurrency/collision, failure/cancellation,
  journal/acknowledgement loss, recovery and Room migration/reopen regressions.
- Added deterministic fixture ZIP generation and public certificate reports for
  phone-only test setup. Comparison of retained APK certificates stops on mismatch;
  no advice to uninstall/clear data, no owner credentials created or uploaded.
- Updated protected signing labels/preflight for the narrow capability, preserving
  main/manual/environment/pinned-certificate restrictions and MOVE/UNDO disabled.
- Device-specific /proc/FUSE/exclusive-create/sync behavior, installed signing
  compatibility and hardware durability remain unverified. No Build 4 work added.

### Build 3.2 local validation
- 101 JVM tests passed with zero failures/errors/skips, including all 87 retained
  tests, actual disposable I/O, collision concurrency, abrupt termination, native
  SQLite migration/recreation and diagnostic transaction rollback.
- Ten Python signing/tooling/packaging tests passed with disposable credentials;
  actual APK certificate comparison detects mismatches without uninstall advice.
- Kotlin/Compose/KSP, lintDebug and assembleDebug passed. Lint: zero errors,
  five unchanged advisory policy/dependency warnings; no checks suppressed.
- Schema 1/2 unchanged, schema 3 exported; versioned-schema guard, manifest
  allowlist, actionlint, APK signature and 16 KiB ZIP alignment verified.
- Generated release BuildConfig/manifest have diagnostic capability false and
  generic MOVE/UNDO false. No release APK or owner signing was performed.
- No emulator/S26 copy, reboot/power-loss or installed upgrade test performed;
  platform capabilities and owner acceptance remain explicitly unverified.


### Build 3.2 — Standalone diagnostic signing-blocker repair
- Owner reported that the initially reviewed APK could not update Build 3.1:
  certificate 64b12f…1e23 differed from the old Build 3.2 certificate eeac4c…b18f.
- Added dedicated diagnostic build type with applicationIdSuffix .diagnostic,
  launcher/title S26 Organizer Diagnostic, version code 6 / 0.3.2-diagnostic.
  Normal debug/release preserve the main application ID and disable synthetic
  copying. Runtime now also requires the exact separate diagnostic application ID.
- App-private journal is isolated by Android package/context/UID; no shared UID,
  private-data copy, database migration across apps, or main-install replacement.
  Existing Room schemas and copy/recovery/filesystem protections are unchanged.
- Added per-variant merged-manifest/authority/permission contracts, actual Android
  package-URI/fallback intent tests, normal-build capability refusal and compiled
  APK identity/signing tests. Both normal and diagnostic run the existing suite.
- CI uploads only the clearly named standalone APK and public package/certificate
  report. Protected signing refuses a normal main-package input; same-package
  mismatch still fails while differing IDs are reported as separate installs.
- Updated phone-only instructions: retain Build 3.1, install app-diagnostic.apk,
  grant All Files Access to the diagnostic separately and verify two app histories.
  No owner credentials are required. No key/password was introduced into source.
- Documented permanent-signing options for future main builds and the unresolved
  original-private-key requirement. No promise of recovering a key from its public
  certificate, and no uninstall/Clear data workaround. Device acceptance pending.

### Standalone repair local verification
- 104 JVM tests passed for normal debug and 104 for diagnostic (208 executions),
  zero failures/errors/skips. All existing tests retained, plus Android API 30
  Settings URI/fallback and package-capability checks.
- 13 Python tests passed, including compiled package/label/authority/permission
  checks, separate-ID comparisons, same-ID mismatch refusal and protected-signing
  rejection of a main-package APK before private-material creation.
- Both variants passed Kotlin/Compose/KSP compilation, lint and APK assembly.
  Each lint report has zero errors and five advisory warnings.
- Schema guard, actionlint, signature verification, diagnostic 16 KiB alignment
  and normal release package/disabled-flag generation passed. Room exports unchanged.
- No owner credentials provisioned, installed app/data accessed or device
  coinstallation/permission/copy test performed. Hardware acceptance remains pending.

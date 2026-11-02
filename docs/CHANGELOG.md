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

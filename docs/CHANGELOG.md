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

# Project roadmap and backlog

The original watcher, safe storage engine, local classifiers, routing rules,
notifications, history/Undo, and Compose UI goals remain. The sequence now puts
storage validation and recovery before background automation. Only checked items
are implemented; device acceptance and published CI evidence are tracked separately.

## 1. Foundation and CI/CD — Build 1

- [x] Create repository and architecture, roadmap, and changelog documents.
- [x] Initialize one Gradle Kotlin DSL application module and version catalog.
- [x] Add the complete Gradle wrapper and debug signing defaults.
- [x] Add the Build 1 Material 3 Compose dashboard with light/dark support.
- [x] Add JVM manifest contract tests.
- [x] Compile Kotlin/Compose, pass JVM tests/lint, and generate a signed debug APK
  in the cloud workspace.
- [x] Add GitHub Actions wrapper validation, caching, tests, lint, debug APK build,
  and artifact upload.
- [x] Verify a successful published GitHub Actions run and downloadable artifact
  ([Build 1 run](https://github.com/KroflaFPS/s26-download-organizer/actions/runs/41511540170)).
- [x] Build 1 merged through PR #1 and installed/launched on the Galaxy S26 Ultra
  (Android 17), as reported by KroflaFPS.

## 2. Storage access and read-only inventory

- [x] Build 2 all-files access/inventory confirmed on the S26 Ultra by KroflaFPS.
- [x] Implement independently testable storage adapters and permission revocation handling.
- [x] Read-only metadata inventory of immediate Downloads entries; no file mutations.
- [x] Boundary-aware normalized source exclusion for Organized/ and descendants;
  no creation or traversal of the destination.
- [x] Handle inaccessible storage and changing/missing entries with errors or partial notices;
  temporary files are listed without claiming they are ready for future movement.
- [x] Add permission setup, explicit API 26–29 gating, lazy inventory UI and Refresh.
- [x] Add temporary-fixture, policy, screen-state and source/merged-manifest tests.
- [x] Verify Build 2 JVM tests/lint/debug APK in the cloud workspace.
- [x] Confirm a successful published Build 2 Actions run and APK artifact
  ([implementation run](https://github.com/KroflaFPS/s26-download-organizer/actions/runs/41518395309)).
- [x] Build 2 merged at `86082b4b887a82d66cef41ea8072428d50f7f254` via PR #2
  and owner-tested on Android 17, as reported by KroflaFPS.

## 3. Safe operations and persistent journal — Build 3 / 3.1 engineering subset

- [x] Room journal, UUID/identity claims, revisioned phase events, schema v1/v2 exports and explicit migration 1→2.
- [x] Portable single-file coordinator with no-clobber naming/publication contracts,
  explicit copy verification, copy-only outcomes and separate removal/sync evidence.
- [x] Conservative idempotent recovery and conflict-checked Undo logic.
- [x] Build 3.1: no completion inferred from disappearance/stale evidence;
  durable fair bounded recovery pagination and explicit large-record deferral.
- [x] Build 3.1: stricter phase/evidence/revision/claim/event validation; migration,
  rollback, Undo conflict, cancellation and fairness regression coverage.
- [x] Build 3.1: public-API Android descriptor-backed bounded read/hash adapter;
  writes still rejected, Samsung /proc/FUSE behavior unverified.
- [x] Real-I/O disposable fixture tests, phase termination/failure injection, Room
  close/reopen integration, missing-migration failure, UI gate and inventory regressions.
- [x] Minimal review/history/recovery UI; real-file Confirm/Undo disabled.
- [x] Protected stable-signing workflow and disposable-key verification tests;
  no private credentials committed or assumed.
- [ ] Approve/configure owner stable signing and journal-retention procedures.
- [ ] Implement and independently audit an Android adapter with rooted no-follow
  mutation I/O and no-clobber publication. Until then, no shared-storage mutation capability.
- [ ] Establish a defensible source-deletion capability, or retain copy-only behavior.
  Path stat followed by unlink is insufficient; readiness alone cannot close this race.
- [ ] Validate Android/FUSE sync/publication/recovery behavior on disposable device files.
- [ ] Enable a reviewed owner-testable operational build only after release gates.
- [ ] Independent Jinx audit and KroflaFPS Build 3 device acceptance.

JVM success does not complete hardware crash/durability gates. The shipping
Build 3.1 engineering APK preserves read-only Downloads and cannot modify user files.

## 4. File readiness verification

- [ ] Conservatively defer temporary and unstable downloads.
- [ ] Combine repeated observations with available completion evidence.
- [ ] Revalidate readiness and source identity immediately before a move.
- [ ] Test changing files, delayed writes, renames, and interrupted downloads.

## 5. Content classification

- [ ] Inspect APK metadata locally with `PackageManager.getPackageArchiveInfo()`.
- [ ] Classify documents (including PDF/EPUB/DOCX) with local metadata/text heuristics.
- [ ] Inspect media and archives using MIME types and bounded header/content inspection.
- [ ] Handle unknown or malformed content safely with an Other fallback.

## 6. Hybrid background automation

- [ ] Add opportunistic FileObserver/event detection where supported.
- [ ] Add bounded WorkManager reconciliation for missed events and interruptions.
- [ ] Deduplicate candidates and respect permission/background execution constraints.
- [ ] Combine readiness, classification, routing, and journaled operations.
- [ ] Validate Samsung/Android 17 behavior without a permanent dataSync service.

## 7. Routing customization, notifications, and UI refinement

- [ ] Route into the unified Organized/ category tree and configurable subfolders.
- [ ] Add custom folder/rule mappings and real automation controls.
- [ ] Add grouped notification batches and permission handling.
- [ ] Expose persistent individual activity history and Undo in the dashboard.
- [ ] Refine accessibility, status/error presentation, and owner-tested workflows.

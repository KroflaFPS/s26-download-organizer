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
- [x] Implement Build 4 operational Android adapter (`AndroidOperationFiles`) with
  descriptor-anchored reads/SHA-256 verification, `NOFOLLOW_LINKS` checks,
  `CREATE_NEW` staging, hard-link-first / `CREATE_NEW` FUSE copy fallback, and
  pre-removal identity/hash checks (independent audit still pending).
- [ ] Close shared-storage source-deletion TOCTOU races cleanly or obtain
  independent audit sign-off (Android lacks kernel compare-and-unlink).
- [ ] Validate Android/FUSE sync/publication/recovery behavior on disposable device files.
- [x] Enable operational moves, `Sort now`, background auto-sorting, and `Undo`
  in Build 4 (`0.4.0` / `versionCode 5`).
- [ ] Independent audit and KroflaFPS physical device acceptance.

JVM and Robolectric success does not complete hardware crash/durability gates.

## 4. File readiness verification (Build 4)

- [x] Conservatively defer temporary and unstable downloads (`.crdownload`,
  `.part`, `.tmp`, `.s26-` staging files, etc.).
- [x] Combine repeated Room-persisted observations (`ObservedCandidate` / schema v3)
  with size/mtime stability across the 15-second settle window.
- [x] Revalidate readiness and source identity immediately before move and removal.
- [x] Test changing files, delayed writes, renames, and interrupted downloads in JVM/Robolectric tests.

## 5. Content classification (Build 4)

- [x] Inspect APK metadata locally with `PackageManager.getPackageArchiveInfo()`.
- [x] Classify documents (including PDF/EPUB/DOCX/ODT/text/office) with bounded
  magic-byte and ZIP container inspection plus extension heuristics.
- [x] Inspect media and archives using magic bytes, ZIP entry inspection, and extension heuristics.
- [x] Handle unknown or malformed content safely with an `Other` fallback.

## 6. Hybrid background automation (Build 4)

- [x] Add opportunistic `FileObserver` detection (`DownloadsWatcher`) when the app process is alive.
- [x] Add bounded `WorkManager` reconciliation (`SortWorker` / `SortScheduler` — 15-minute
  periodic work + 15-second debounced one-shot work) for missed events and interruptions.
- [x] Serialize runs (`AutoSortEngine.runMutex`), deduplicate candidates via unique journal
  source claims, and respect storage permission & toggle state.
- [x] Combine readiness, classification, `Organized/<Category>/` routing, media scan
  notification, and journaled operations.
- [ ] Validate Samsung/Android 17 behavior without a permanent `dataSync` service on device.

## 7. Routing customization, notifications, and UI refinement

- [x] Route into the unified `Organized/<Category>/` tree with auto-created category
  subfolders (`Installers`, `Documents`, `Images`, `Audio`, `Videos`, `Archives`, `Other`).
- [x] Add real automation controls (`Auto sorting` switch and `Sort now` action) in the dashboard.
- [ ] Add user-configurable custom folder/rule mappings UI.
- [x] Add grouped summary notifications (`SortNotifier`) and `POST_NOTIFICATIONS`
  runtime permission handling on Android 13+.
- [x] Expose persistent individual activity history, manual `Move`, and `Undo` in the dashboard.
- [ ] Refine accessibility, status/error presentation, and owner-tested workflows on device.

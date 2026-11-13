# S26 Download Organizer architecture

## Baseline and current release status

Build 1 established the launchable Compose application and CI. Build 2 was merged
at `86082b4b887a82d66cef41ea8072428d50f7f254` via PR #2 and confirmed working on the
S26 Ultra/Android 17 by KroflaFPS. Build 3/3.1 added the Room journal, coordinator,
recovery engine, and gated read-only Android adapter.

**Build 4 (`0.4.0` / `versionCode 5`) activates operational sorting and background
automation.** Files downloaded into primary `Downloads/` are waited on until
settled, classified locally, and journal-moved into automatically created
`Organized/<Category>/` folders (`Installers`, `Documents`, `Images`, `Audio`,
`Videos`, `Archives`, `Other`). Hardware crash/power-loss durability, Samsung One UI
background restrictions, and live Android 17 FUSE semantics remain unverified until
tested on the target device.

## Toolchain and persistence dependencies

One `:app` module; application ID `com.kroflafps.s26downloadorganizer`.
AGP 9.5.0, Gradle 9.8.2, JDK 21, built-in Kotlin/Compose compiler 2.2.10,
Compose BOM 2026.09.00, Material 3, AndroidX Core 1.19.1, Activity 1.13.0,
Lifecycle 2.11.0, coroutines 1.10.2, WorkManager 2.12.0, compile/target API 37,
minimum API 26, and JUnit 4.13.2. Version code/name: 5/0.4.0.

Room 2.8.5, Room Gradle plugin 2.8.5 and **KSP2 2.3.4** generate the database/DAO
and export versioned schemas (1, 2, 3). Robolectric 4.16.1 + AndroidX Work Testing
2.12.0 + native SQLite run in JVM tests, which do not substitute for device hardware tests.

## Retained inventory boundary

On API 30+, `StorageAccessAdapter` checks `Environment.isExternalStorageManager()`.
Settings opens only on a tap: package-specific All Files Access, general list,
then app details. Denial/cancellation is never treated as a grant. Resume/Refresh
recheck access; pause cancels inventory and clears its screen state. API 26–29
show an explanation and request no legacy permission.

`AndroidDownloadsRepository` resolves primary Downloads with Android Environment
APIs and runs metadata scans on `Dispatchers.IO`. One-level NIO attributes use
`NOFOLLOW_LINKS`. Links are listed, not traversed. Ordering is newest modification
first, then exact name and normalized identity. Normalized path components exclude
`Organized/` and descendants so sorted files are never re-scanned as inputs.

## Operation boundaries, classification, and background automation

Package responsibilities:
- `domain` — `FileCategory` (`Installers`, `Documents`, `Images`, `Audio`, `Videos`, `Archives`, `Other`) and inventory/storage contracts.
- `classification` — `FileClassifier` combines bounded header magic-byte inspection, ZIP container structure checks (APK `AndroidManifest.xml`, OOXML/ODF/EPUB metadata), `PackageManager.getPackageArchiveInfo()` verification, and extension heuristics, defaulting safely to `Other`.
- `operations` — platform-independent records, policies (`.s26-` and temporary download exclusions, `MUTATIONS_ENABLED = true`), coordinator (routed into `Organized/<Category>/`), and bounded recovery logic.
- `history` — Room database `operation-journal.db` (schema version **3**, `MIGRATION_1_2`, `MIGRATION_2_3`) storing operations, append-only events, durable recovery cursor, and `observed_candidates` readiness tracking.
- `storage` — `AndroidOperationFiles` performs descriptor-anchored reads/SHA-256 verification, `NOFOLLOW_LINKS` checks, `CREATE_NEW` exclusive `.s26-<uuid>.part` staging, hard-link-first no-clobber publication with `CREATE_NEW` copy fallback when FUSE rejects cross-dir links, directory sync (treating FUSE `EINVAL`/`ENOTSUP`/`EACCES` on directory `fsync` gracefully after file `fsync` succeeds), and pre-removal identity/hash checks.
- `automation` — `DownloadReadinessTracker` (15-second stable size/mtime settle window), `AutoSortEngine` (serialized by `Mutex` + journal source claims), `DownloadsWatcher` (`FileObserver` when process is active), `SortScheduler` & `SortWorker` (`WorkManager` 15-minute periodic + 15-second debounced one-shot reconciliation), `OrganizerPreferences`, and `SortNotifier` (grouped `POST_NOTIFICATIONS` summary).
- `ui` — Compose dashboard with `Auto sorting` toggle, `Sort now` immediate action, `Review move` (showing auto-detected category), `Undo`, history list, and recovery check.

Room's unused multi-instance invalidation service remains removed from the merged manifest.

## No-overwrite publication, deletion race, and durability limits

1. Intent precedes filesystem mutation; `.s26-<uuid>.part` is exclusively created via `CREATE_NEW`.
2. Publication uses `Files.createLink` first or `CREATE_NEW` stream copy fallback with `FileChannel.force(true)`. Occupied targets auto-number `name (1).ext` etc. without ever overwriting existing files.
3. For `canConditionallyRemove` completed moves, the tracked `.part` staging alias is cleaned up only after `DESTINATION_COMMITTED` and before final `verify` captures destination `ctime`.
4. Pre-removal identity/hash checks (`removeConditionally`) reduce accidental deletion risk, but Android shared storage still lacks a kernel compare-identity-and-unlink primitive (`TOCTOU` window exists if another process concurrently swaps paths).
5. `FileChannel.force(true)` and SQLite `synchronous=FULL` are requests to the OS/FUSE stack, not sudden-power-loss guarantees; filesystem and SQLite never share an atomic transaction.

## Signing and journal retention

Normal debug/PR APKs use ephemeral debug signing. Protected `development-signing` on `main` can re-sign Build 4 APKs when owner secrets are provisioned and verify the `MUTATIONS_ENABLED=true` manifest marker (`tools/sign_engineering_apk.py`). Journal backup/transfer remains disabled (`allowBackup="false"`) so stale operation state is never restored onto another filesystem.

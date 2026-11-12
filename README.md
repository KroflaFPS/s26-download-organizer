# S26 Download Organizer

Offline Android organizer for the Samsung Galaxy S26 Ultra on Android 17.
Build 2 was merged at `86082b4b887a82d66cef41ea8072428d50f7f254` through PR #2
and owner-tested, as confirmed by KroflaFPS. Its permission flow, inventory,
light/dark theme, launcher and toolchain are preserved.

**Build 4 (`0.4.0` / `versionCode 5`) enables automatic background download sorting,
local classification, manual `Move`/`Sort now`, and `Undo`.**

## Implemented behavior and limits

- Primary shared `Downloads/` metadata inventory and All Files Access (`MANAGE_EXTERNAL_STORAGE`) setup on API 30+, plus `POST_NOTIFICATIONS` prompt on Android 13+.
- **Automatic background sorting (`Auto sorting` toggle, enabled by default):**
  - Opportunistic `FileObserver` (`DownloadsWatcher`) schedules a debounced 15-second reconciliation check while the app process is running.
  - `WorkManager` (`SortWorker`) runs every 15 minutes as a reliable fallback for downloads completed while the app was closed.
  - `DownloadReadinessTracker` persists observations in Room (`observed_candidates`, schema v3) and waits for temporary suffixes (`.crdownload`, `.part`, `.tmp`, etc.) to vanish and file size + mtime to remain stable for 15 seconds before moving.
- **Automatic local classification into `Organized/<Category>/`:**
  - `FileClassifier` inspects magic bytes, ZIP container entries (`AndroidManifest.xml`, OOXML/ODF/EPUB markers), Android `PackageManager.getPackageArchiveInfo()`, and file extensions.
  - Subfolders inside `Organized/` are created on demand: `Installers`, `Documents`, `Images`, `Audio`, `Videos`, `Archives`, and `Other`.
  - Existing filenames are never overwritten; conflicts are safely numbered (`file (1).ext`, etc.).
- **Safe journaled moves & `Undo`:**
  - Every move and `Undo` uses `CREATE_NEW` staging, `FileChannel.force(true)`, SHA-256 verification, hard-link-first / `CREATE_NEW` copy fallback, and pre-removal identity/hash checks backed by `operation-journal.db`.
  - Grouped notifications summarize sorted batches when files are moved automatically, and Android's `MediaScannerConnection` is notified of moved paths.
  - Dashboard provides **Auto sorting**, **Sort now**, per-file **Move** review, **Check recovery**, and **Undo** for verified completed moves.
- **Caveats & limits:** Hardware crash/sudden power-loss safety is not claimed, shared Android storage still has no kernel compare-and-unlink primitive, and Samsung One UI battery optimization may restrict `FileObserver` when swiped from recents (leaving 15-minute `WorkManager` reconciliation active).

## Build and tests

AGP 9.5.0, Gradle 9.8.2, JDK 21, Kotlin/Compose compiler 2.2.10, Compose BOM 2026.09.00, compile/target API 37, minimum API 26, Room 2.8.5, KSP2 2.3.4, WorkManager 2.12.0, Robolectric 4.16.1 (test-only). Build 4 uses `versionCode 5` / `versionName "0.4.0"`.

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_room_schema.py
```

Set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to the Android SDK. `tools/check_room_schema.py` expects all exported Room schemas (`1.json`, `2.json`, `3.json`) to be staged or committed.

## Download the APK (no local PC required)

1. Sign into GitHub, open this repository → **Actions → Build debug APK**.
2. Choose a successful run for the intended branch/commit. Download
   **s26-download-organizer-build-4-debug** under **Artifacts**.
3. Extract the ZIP in Samsung **My Files**, then tap `app-debug.apk`.
4. Grant **All Files Access** (and notification permission on Android 13+ if desired) when prompted.

Note: Ephemeral debug APKs use a CI debug key; if upgrading from an earlier differently signed build with an existing journal, check recovery first or use the stable-signed `main` workflow once owner secrets are provisioned.

## On-device verification checklist (Galaxy S26 Ultra / Android 17)

1. Grant **All Files Access** and verify `Downloads/` files appear in the dashboard; `Organized/` stays excluded from the source list.
2. Download a disposable test image, PDF/DOCX, ZIP, and APK while the app is open and while it is closed; confirm each is moved only after settling into `Organized/Images/`, `Organized/Documents/`, `Organized/Archives/`, and `Organized/Installers/`.
3. Tap **Sort now** with a freshly created disposable file in `Downloads/` and confirm immediate classification and movement.
4. Confirm duplicate filenames create `name (1).ext` rather than overwriting, and test **Undo** on a moved file.
5. Check Samsung **Device care / Battery** settings if you want `DownloadsWatcher` / `WorkManager` to run without aggressive background throttling.

- [Architecture and safety boundaries](docs/ARCHITECTURE.md)
- [Roadmap and release status](docs/ROADMAP.md)
- [Changelog and verification](docs/CHANGELOG.md)
- [Android storage review](docs/ANDROID_STORAGE_REVIEW.md)

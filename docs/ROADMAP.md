# Project Roadmap & Backlog

## Phase 1: Project Setup & CI/CD Pipeline
- [x] Create GitHub repository (`s26-download-organizer`).
- [x] Setup `docs/` tracking directory (`ARCHITECTURE.md`, `CHANGELOG.md`, `ROADMAP.md`).
- [ ] Add GitHub Actions workflow (`.github/workflows/build-apk.yml`) to auto-build APKs in the cloud.
- [ ] Initialize base Gradle / Kotlin Android project skeleton.

## Phase 2: Core Watcher & Storage Engine
- [ ] Implement `FileObserver` watchdog on `/storage/emulated/0/Download/`.
- [ ] Add download completion detector (handling `.crdownload`, `CLOSE_WRITE`, and `MOVED_TO` events).
- [ ] Safe file move utility with collision handling (avoiding overwrites).

## Phase 3: Classifier Engine
- [ ] Implement APK parser using `PackageManager.getPackageArchiveInfo()` (extract app name, category, icon).
- [ ] Implement Document classifier (PDF/EPUB/DOCX text & metadata heuristic).
- [ ] Implement Media & Archive inspector (MIME type + inner archive file types).

## Phase 4: Dynamic Router & Rules
- [ ] Automatic destination directory resolver (`Installers/{Category}/`, `Documents/{Type}/`).
- [ ] Notification action card ("Moved [file] to [folder] [Undo]").
- [ ] In-memory / Room database undo log.

## Phase 5: Minimalist UI (Jetpack Compose)
- [ ] Simple dashboard showing recent sorting events.
- [ ] Toggle master service on/off.
- [ ] Custom folder mapping overrides.

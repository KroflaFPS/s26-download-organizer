# S26 Ultra Download Organizer - Architecture & Technical Specs

## 1. Overview
A background service app for Samsung Galaxy S26 Ultra (Android 15+) designed to monitor `/storage/emulated/0/Download/`, identify incoming downloads (APKs, documents, media, archives), and automatically route them to context-appropriate folders.

## 2. Core Modules
- **`service/`**: Foreground service + `FileObserver` / `MediaStore` watchdog monitoring the Downloads directory. Detects file write completion (`CLOSE_WRITE`, `.crdownload` rename).
- **`classifier/`**: Inspection engine:
  - **APKs**: Uses Android `PackageManager.getPackageArchiveInfo()` to parse app label, package name, icon, and native category (`CATEGORY_GAME`, `CATEGORY_AUDIO`, `CATEGORY_PRODUCTIVITY`, etc.).
  - **Documents**: Lightweight local text/metadata parser for invoices, receipts, travel documents, e-books.
  - **Media/Archives**: MIME types and archive header inspection.
- **`router/`**: Destination engine that dynamically creates destination subfolders (e.g. `Installers/{Category}/`, `Documents/Invoices/{Year}/`) and safely moves files.
- **`ui/`**: Jetpack Compose dashboard displaying recent sorting activity, manual rule overrides, and 1-tap Undo.

## 3. Key Permissions
- `android.permission.MANAGE_EXTERNAL_STORAGE` (All Files Access)
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC`
- `android.permission.RECEIVE_BOOT_COMPLETED`
- `android.permission.POST_NOTIFICATIONS`

## 4. Multi-AI Workflow Responsibilities
- **Gemini**: Technical Research, API validation, Code & Diff Auditor.
- **ChatGPT**: Architecture prompt engineer and task framer for Codex.
- **Codex**: Primary programmer and implementation author.
- **User (KroflaFPS)**: Project lead, orchestrator, and device tester.

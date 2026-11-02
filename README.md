# S26 Download Organizer

An offline Android download organizer for the Samsung Galaxy S26 Ultra on Android
17. Build 1 was merged through [PR #1](https://github.com/KroflaFPS/s26-download-organizer/pull/1)
and owner-tested. **Build 2 adds All Files Access setup and a read-only Downloads
inventory.** File organization remains inactive. Build 2 reads metadata only;
it never opens file contents, moves, renames, copies, deletes, or changes files.
Build 2 is not device-accepted until KroflaFPS tests it on the S26 Ultra.

Future builds will organize downloads under `/storage/emulated/0/Organized/`
using conservative readiness checks, local classification, safe journaled moves,
and hybrid background detection/reconciliation.

## Build and verification

The project uses JDK 21, AGP 9.5.0 (built-in Kotlin 2.2.10), Gradle 9.8.2,
Compose BOM 2026.09.00, and the matching Kotlin Compose compiler plugin.
Minimum Android version is 8.0/API 26; compile/target SDK is Android 17/API 37.
See [architecture](docs/ARCHITECTURE.md) for version choices and package boundaries.

With JDK 21 and Android command-line tools configured (`ANDROID_HOME`):

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. There is no custom signing
configuration: Android's ordinary debug key is used. JVM tests enforce the
source and merged manifest contracts; lint and compilation validate Android/Compose
configuration. These checks do not replace device installation and runtime tests.

## Get the APK using GitHub Actions (no PC required)

1. Open this repository in a browser while signed into GitHub. Select **Actions**
   → **Build debug APK**.
2. Open the successful run for the intended branch/commit. Pushes to `main`,
   `develop`, `feat/**`, `feature/**`, and `fix/**`, and pull requests trigger builds.
   After the workflow exists on the default branch, you can also select
   **Run workflow**, choose a branch, and run it manually.
3. Confirm both the JVM test step and the lint/APK step succeeded. Under
   **Artifacts**, download **s26-download-organizer-build-2-debug**. GitHub delivers
   a ZIP containing `app-debug.apk`. Artifacts expire after 14 days; run the
   workflow again if the artifact has expired. Test/lint reports are in the
   separate **build-2-verification-reports** artifact.
4. On the S26 Ultra, use **My Files** to find the downloaded ZIP (normally in
   Downloads), extract it, and tap `app-debug.apk`.
5. If Android asks, allow **Install unknown apps** for the app opening the APK
   (for example My Files or the browser), then return and tap **Install**. Samsung
   Auto Blocker or a managed-device policy may block sideloading; if so, review
   **Settings → Security and privacy** and the device's installation policy.
   Restore any temporary installation setting after installation.
6. Launch **S26 Download Organizer** from the installer or app drawer. Confirm
   **Build 2**, **Foundation ready**, and the read-only/inactive-organization explanation.
   Check light/dark mode, rotation, and a larger system font size. Report the
   workflow commit and any installation/runtime error to the project.

Debug APKs are for development. Fresh CI runners may create different debug
signing keys; an APK from a later run may fail to update the previous installation
with a signature mismatch. For Build 2, uninstall the previous debug app if signatures conflict, then
install the new APK. This removes app-private data; future builds with persistent
history will need a deliberate development signing/data-retention policy.

## Storage access and inventory

On Android 11/API 30 and newer, tap **Open Settings** to grant **All Files Access**.
This is a special Settings grant, not an ordinary runtime permission dialog.
The app first opens its package-specific All Files Access page, then falls back
to the general list or its app-details page when unavailable. If all screens
are unavailable, it explains how to find the setting manually. Opening Settings
never counts as a grant. Returning to the app checks the real grant again.

All Files Access is the chosen strategy for this personal sideloaded organizer
and will eventually support organizing downloads. Its broad grant does not
expand Build 2's behavior: only immediate children of primary shared Downloads
are listed. No other source, SD card, work profile, or subfolder is scanned.
Files, folders, and symbolic links are indicated separately; links are never
followed. Hidden and Unicode names are preserved. File size is shown in bytes,
and dates use the device's locale/time zone. An extension is a filename suffix,
not content classification. Missing metadata is labeled unavailable, and
changing/inaccessible entries produce a partial-list notice or an error.

The list is a snapshot: **Refresh** rereads metadata; there is no background
monitoring. Scans and sorting run off the UI thread. Repeated refresh taps do
not overlap scans. Pausing clears the displayed inventory and cancels the scan;
resuming rechecks access and scans again only if authorized. Revocation returns
to setup without restoring stale results. No inventory is saved to disk.

The `Organized/` destination is never created or modified. A normalized,
component-boundary policy excludes that root and descendants if sources overlap
in future configurations; sibling names such as `Organized-backup` are allowed.

API 26–29 can launch the dashboard but intentionally cannot list Downloads in
Build 2. They receive an accurate explanation and no legacy permission request.
Samsung-specific Settings/storage behavior requires real-device validation.
The app has no network permission, analytics, services, or workers.

## S26 Ultra device acceptance checklist (owner testing required)

1. Install the Build 2 artifact as described above. If a Build 1 signature
   conflicts, uninstall the old debug app first (app-private data is removed).
2. With All Files Access off, launch: confirm Build 2, the read-only explanation,
   setup view, and no file list. No Settings page should open automatically.
3. Tap **Open Settings**, then return without granting. Confirm setup remains.
   Grant access on a second visit and return: confirm progress, then real entries
   matching the immediate Downloads contents in My Files.
4. Check names (including hidden/Unicode/spaces), file versus folder indicators,
   extensions, zero-byte sizes, modification dates, count, and newest-first order.
   Folder contents and symlink targets must not appear as separate entries.
5. Tap **Refresh** repeatedly. Confirm one loading state, responsive scrolling,
   and an updated count/list. With a large existing inventory, scroll through it;
   there should be no thumbnails, freezing, or extra file actions.
6. Revoke All Files Access in Settings and return. Confirm the old list is gone
   and setup is shown. Grant again and confirm recovery. Repeat after rotation
   or relaunch; verify dark mode and larger fonts.
7. If Downloads is already empty, confirm the empty message and working Refresh.
   If another app changes/removes a file during a scan, confirm safe refresh or
   an actionable partial/error message. Do not delete personal files just to test.
   Retry after any temporary storage/access error; no crash or false empty success
   should occur. OEM-unavailable settings/error paths are also covered by JVM
   tests where practical, but cannot all be forced on a normal phone.
8. Compare file contents, names, sizes and modification dates before/after using
   My Files or existing trusted tooling. Confirm the app creates no Organized/
   folder and changes no Downloads entries. Record commit, Android/One UI version,
   observed results, and any error; Build 2 acceptance remains unchecked until then.

Build 3 will add the safe move engine and persistent journal. Readiness,
classification, automation, routing customization, notifications, and Undo remain
future work; none is enabled by granting access in Build 2.

## Documentation and collaboration

- [Architecture and technical decisions](docs/ARCHITECTURE.md)
- [Seven-milestone roadmap](docs/ROADMAP.md)
- [Changelog](docs/CHANGELOG.md)

Gemini supports research and auditing; ChatGPT supports architecture/task framing;
Codex implements the app; KroflaFPS leads the project and tests on-device; Ada (ChatGPT) is the lead architect
and Jinx (Gemini) is the independent auditor.

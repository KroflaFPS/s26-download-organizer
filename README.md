# S26 Download Organizer

An offline Android download organizer for the Samsung Galaxy S26 Ultra on Android
17. **Build 1 is the foundation only:** a launchable Compose dashboard and cloud
APK build pipeline. File organization is not active, and this build does not
access or change user downloads or request storage/notification permissions.

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
source manifest contract; lint and compilation validate Android/Compose
configuration. These checks do not replace device installation and runtime tests.

## Get the APK using GitHub Actions (no PC required)

1. Open this repository in a browser while signed into GitHub. Select **Actions**
   → **Build debug APK**.
2. Open the successful run for the intended branch/commit. Pushes to `main`,
   `develop`, `feat/**`, `feature/**`, and `fix/**`, and pull requests trigger builds.
   After the workflow exists on the default branch, you can also select
   **Run workflow**, choose a branch, and run it manually.
3. Confirm both the JVM test step and the lint/APK step succeeded. Under
   **Artifacts**, download **s26-download-organizer-build-1-debug**. GitHub delivers
   a ZIP containing `app-debug.apk`. Artifacts expire after 14 days; run the
   workflow again if the artifact has expired. Test/lint reports are in the
   separate **build-1-verification-reports** artifact.
4. On the S26 Ultra, use **My Files** to find the downloaded ZIP (normally in
   Downloads), extract it, and tap `app-debug.apk`.
5. If Android asks, allow **Install unknown apps** for the app opening the APK
   (for example My Files or the browser), then return and tap **Install**. Samsung
   Auto Blocker or a managed-device policy may block sideloading; if so, review
   **Settings → Security and privacy** and the device's installation policy.
   Restore any temporary installation setting after installation.
6. Launch **S26 Download Organizer** from the installer or app drawer. Confirm
   **Build 1**, **Foundation ready**, and the inactive-organization explanation.
   Check light/dark mode, rotation, and a larger system font size. Report the
   workflow commit and any installation/runtime error to the project.

Debug APKs are for development. Fresh CI runners may create different debug
signing keys; an APK from a later run may fail to update the previous installation
with a signature mismatch. For Build 1, uninstall the previous debug app and then
install the new APK. This removes app-private data; future builds with persistent
history will need a deliberate development signing/data-retention policy.

## Documentation and collaboration

- [Architecture and technical decisions](docs/ARCHITECTURE.md)
- [Seven-milestone roadmap](docs/ROADMAP.md)
- [Changelog](docs/CHANGELOG.md)

Gemini supports research and auditing; ChatGPT supports architecture/task framing;
Codex implements the app; KroflaFPS leads the project and tests on-device.

# S26 Download Organizer

Offline Android organizer for the Samsung Galaxy S26 Ultra on Android 17.
Build 2 was merged at `86082b4b887a82d66cef41ea8072428d50f7f254` through PR #2
and owner-tested, as confirmed by KroflaFPS. Its permission flow, inventory,
light/dark theme, launcher and toolchain are preserved.

**Build 3.2 is an experimental synthetic-fixture copy prototype.** Generic
MOVE/UNDO and source removal remain disabled. Build 3.1 was merged through PR #3
at main `08dddedf9241b403de738787e5c6ec5297429e30`; this work uses a new draft PR.
No Samsung copy, crash durability or in-place upgrade acceptance is claimed.

The existing All Files Access setup, API 26–29 inventory gating, metadata-only
flat Downloads inventory, Room journal/history and conservative legacy recovery
are retained. Automatic organizing is inactive. Existing operation confirmations
remain disabled. No new permissions, network access, services or workers.

The separate **Disposable-file copy test** accepts only two known synthetic files
(up to 1 MiB), deliberately placed in `Download/S26_Organizer_Test_Input/`.
Its sole output is `Organized/S26_Organizer_Test_Output/`. The owner creates both
folders; the app cannot create directories. There is no recursive inventory or
arbitrary path picker. Source names, sizes and SHA-256 must match the fixed
allowlist before mutation. Each copy needs an exact-path review and acknowledgement.

Stage and final files use exclusive creation; no preexisting destination is
opened writable. A verified stage is streamed into an exclusively allocated final
file, with numbered collision names. This is **non-atomic publication**: partial
final files can be visible and remain journaled on interruption. All staging
files remain tracked. Nothing is automatically cleaned up or deleted. Success
says **Copy only — original kept in Downloads.** Diagnostic records never enable
Undo. Runtime refuses unsupported anchoring, identities, permission/storage and
sync. Android behavior remains unverified until the owner's controlled test.

[Read the full capability review, signing stop conditions and phone-only checklist](docs/BUILD_3_2_DEVICE_TEST.md)
before installing/testing. The successful Actions run provides synthetic fixture
and APK ZIPs, so no local PC is needed. Only synthetic data is generated/uploaded;
no user content is uploaded or analyzed remotely.

## Build and tests

The unchanged baseline is AGP 9.5.0, Gradle 9.8.2, JDK 21, Kotlin/Compose compiler
2.2.10, Compose BOM 2026.09.00, compile/target API 37, minimum API 26, one `:app`.
Build 3.2 uses versionCode 5/versionName 0.3.2. Stable Room 2.8.5 and KSP2 2.3.4
were selected after checking the official documentation and then compiling with
this toolchain. Robolectric 4.16.1 is test-only.

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_room_schema.py
```

Set JAVA_HOME to JDK 21 and ANDROID_HOME to the SDK. The signing-script tests
use disposable generated keys and the newly built APK; they never need owner
credentials. The schema check expects schemas to be committed/staged and fails
on generated drift or unversioned exports. Normal builds do not need secrets.

JVM tests include real fixture I/O, no-clobber link races, bounded SHA-256 copies,
source/destination changes, injected failures and termination at each important
boundary, Undo conflicts, idempotent recovery, actual generated Room DAO/native
SQLite close/reopen tests, missing-migration failure, UI gate tests, and Build 2
regressions. Fixture-only conditional removal is a closed-world contract model,
**not an Android atomic-unlink implementation**. Throwing a simulated termination
and reopening SQLite are not power-loss, device-reboot or Android 17 tests.

See [architecture](docs/ARCHITECTURE.md) for the exact state/evidence model,
retained staging aliases, deletion race, durability limits and remaining gates.

## Download the experimental APK (no local PC required)

1. Audit the source/artifact with Ada, then sign into GitHub → this repository →
   **Actions → Build debug APK** and choose the successful Build 3.2 PR run.
2. Download **s26-download-organizer-build-3-2-synthetic-copy-debug** and the
   separate **s26-build-3-2-synthetic-fixtures** ZIP. Extract them in Samsung My Files.
3. Before upgrading, compare `certificate.txt` with the exact previously installed
   APK's certificate. Unknown compatibility or a mismatch stops testing. Follow
   the [certificate comparison procedure](docs/BUILD_3_2_DEVICE_TEST.md#artifact-and-signing-matrix).
   **Do not uninstall or clear app data to bypass a signature mismatch.**
4. Once signature continuity is resolved, tap `app-debug.apk`. Review install-source
   permission/Auto Blocker if prompted. Verify **Build 3.2 · Engineering**, disabled
   MOVE/UNDO, and the preserved Downloads inventory.
5. Follow the [full disposable-fixture checklist](docs/BUILD_3_2_DEVICE_TEST.md#phone-only-owner-checklist)
   before any diagnostic copy. Reports/schema are in **build-3-2-verification-reports**;
   artifacts expire after 14 days.

Ordinary PR/push APKs use ephemeral debug keys that may differ from installed
builds. A matching-signed APK is required for an in-place update. Android refusing
an upgrade is a stop condition, not an instruction to remove the installed app.
Uninstall/Clear data can erase operation records needed to understand retained
outputs. Preserve the existing installation, app data and journal.

## Stable development signing and journal retention

No keystore, password or private key belongs in Git. The optional workflow path
is implemented but has not been run with owner credentials. It still creates a
**synthetic-fixture diagnostic APK with MOVE/UNDO disabled**, not an approved
production organizer. Signing does not broaden its capability.

Owner provisioning (use a trusted private terminal with JDK/keytool; a private
Codespace can be used without a local PC):

1. Outside the repository, use `umask 077` and generate a long-lived development
   key. Enter passwords interactively; do not put passwords in command arguments:

   ```sh
   keytool -genkeypair -storetype PKCS12 -keystore s26-development.p12 \
     -alias s26-development -keyalg RSA -keysize 3072 -validity 3650 \
     -dname "CN=S26 Development"
   keytool -list -v -keystore s26-development.p12 -alias s26-development
   ```

2. Keep an encrypted offline backup of the keystore and credentials. Record the
   public SHA-256 certificate fingerprint independently; do not change the key
   or expected fingerprint between builds.
3. In GitHub Settings → Environments, create **development-signing**, restrict it
   to `main`, and configure required owner approval before provisioning secrets.
   Add these **environment secrets**, not repository files:
   `S26_DEV_KEYSTORE_BASE64`, `S26_DEV_STORE_PASSWORD`, `S26_DEV_KEY_ALIAS`,
   `S26_DEV_KEY_PASSWORD`, `S26_DEV_CERT_SHA256`.
   The first is base64 of the private keystore; the last is the public certificate
   SHA-256 (with or without colons). PKCS12 commonly uses the same store/key password.
   Use GitHub's secret UI or `gh secret set --env development-signing` with stdin/
   interactive prompts; never paste credentials into issues, PRs, logs or chat.
4. After owner review/merge, manually dispatch the workflow on **main** with
   **stable_signing** enabled and approve the protected environment job. PRs and
   other refs cannot run this signing job. The ordinary verification job must pass.
5. CI decodes private material only in runner temporary storage, re-signs the
   engineering APK, verifies the exact pinned certificate, removes temporary
   credentials and uploads **s26-download-organizer-build-3-2-stable-signed-diagnostic**
   with a public `certificate.txt` report. Missing credentials or a pin mismatch
   fail that job; it does not present the ephemeral APK as stable-signed. The
   script also refuses PR/non-main CI contexts and APKs without the compiled
   disabled MOVE/UNDO and explicit diagnostic-copy manifest markers. That marker is a signing preflight check;
   the independent code gate and unavailable adapter writers enforce behavior.
6. Compare the certificate report to the independently retained fingerprint on
   every build. Android enforces matching signatures for normal in-place updates;
   do not bypass a mismatch by uninstalling an app with a nonempty journal.

The stable signature supports in-place upgrades; it does **not** protect against
uninstall, Clear data, device loss or storage failure. The journal is app-private
SQLite with backup/device-transfer exclusions to avoid restoring stale operation
state onto a different filesystem. No remote backup or export service exists.
Schemas 1, 2 and 3 are exported. Migration 2→3 adds separate diagnostic records
and events without changing legacy rows; migration 1→2 remains intact. Migration 1→2 preserves operation/event records,
adds the durable recovery cursor, and quarantines legacy inferred completions.
Future schema changes must bump the DB version, retain old schemas, add/test
explicit versioned migrations, and preserve records.
Missing migrations fail closed; there is no destructive migration fallback.

## Current S26 Ultra acceptance

First perform the retained permission/inventory/light/dark/launcher regression
checks. Generic MOVE/UNDO must remain disabled. Diagnostic tests require audit of
the exact build, certificate continuity and only the provided synthetic fixtures.
See the [exact phone-only checklist](docs/BUILD_3_2_DEVICE_TEST.md#phone-only-owner-checklist).
Do not uninstall or clear app data for a signature mismatch. No device test is
marked complete. Staging/partial files and their journal must be retained on error.

## Roadmap and collaboration

Builds 4–7 retain readiness verification, local classification, hybrid background
automation, routing customization, notifications and UI refinement. Build 3
blockers must be resolved explicitly; Build 4 readiness alone cannot fix deletion
TOCTOU races. No four-tab redesign, empty category grids, services or workers
were added. Media indexing is deferred while operations are disabled; future
index refresh must not be confused with movement or browser download history.

- [Architecture and safety boundaries](docs/ARCHITECTURE.md)
- [Roadmap and incomplete release gates](docs/ROADMAP.md)
- [Changelog and verification](docs/CHANGELOG.md)

KroflaFPS owns the project and device testing; Ada (ChatGPT) leads architecture;
Jinx (Gemini) independently audits; Codex implements. No independent audit is claimed.

## Build 3.2 release matrix

| Build | Generic MOVE/UNDO | Diagnostic copy | Intended use |
| --- | --- | --- | --- |
| Debug PR/push/manual APK | Disabled | Explicit known synthetic fixtures only | Audited, controlled experiment |
| Optional protected stable-signed debug APK | Disabled | Same narrow boundary | Same, with certificate continuity |
| Release configuration | Disabled | Disabled | No release publication introduced |
| JVM adapters | Fixture model only | Disposable temporary directories | Never packaged |

The separate diagnostic capability does not approve arbitrary-file operations.
No operational move engine, conditional deletion or Android atomic-publication
claim is made. See [device review](docs/BUILD_3_2_DEVICE_TEST.md) and the historical
[Build 3.1 review](docs/ANDROID_STORAGE_REVIEW.md). Owner signing/environment
configuration, installed signature compatibility, independent audit and S26 copy
acceptance remain unverified. Keep the new Build 3.2 PR draft and unmerged.

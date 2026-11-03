# S26 Download Organizer

Offline Android organizer for the Samsung Galaxy S26 Ultra on Android 17.
Build 2 was merged at `86082b4b887a82d66cef41ea8072428d50f7f254` through PR #2
and owner-tested, as confirmed by KroflaFPS. Its permission flow, inventory,
light/dark theme, launcher and toolchain are preserved.

**Build 3.2 is a separately installed experimental synthetic-fixture diagnostic.** Generic
MOVE/UNDO and source removal remain disabled. Build 3.1 was merged through PR #3
at main `08dddedf9241b403de738787e5c6ec5297429e30`; this work uses a new draft PR.
The test app is **S26 Organizer Diagnostic**, application ID
`com.kroflafps.s26downloadorganizer.diagnostic`. Normal debug/release keep the
original main ID; only the dedicated diagnostic build has synthetic copying.
Its private journal is separate from installed Build 3.1. The original
main-package APK failed installation due to different certificates; this
standalone artifact avoids replacing that app. No signing setup is needed now.
No Samsung side-by-side installation, copy or crash-durability acceptance is claimed.

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
Build 3.2 uses versionCode 6; diagnostic versionName is 0.3.2-diagnostic. Stable Room 2.8.5 and KSP2 2.3.4
were selected after checking the official documentation and then compiling with
this toolchain. Robolectric 4.16.1 is test-only.

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
./gradlew --no-daemon testDebugUnitTest testDiagnosticUnitTest lintDebug lintDiagnostic assembleDebug assembleDiagnostic
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

## Install alongside Build 3.1 (no signing setup or local PC)

1. Review the updated PR #4 source/artifact, then open its successful Actions run.
2. Download **s26-build-3-2-standalone-diagnostic** and the separate
   **s26-build-3-2-synthetic-fixtures** ZIP. Extract both in Samsung My Files.
3. Check `certificate.txt`: application ID must be
   `com.kroflafps.s26downloadorganizer.diagnostic`, label **S26 Organizer Diagnostic**.
   Tap **app-diagnostic.apk**, not the old `app-debug.apk`. It installs a separate
   app; its certificate does not need to match Build 3.1. Review install-source/
   Auto Blocker prompts if required. No signing password is needed.
4. Leave Build 3.1 installed. Confirm two launcher entries, fresh diagnostic
   history and unchanged Build 3.1 history/settings. Grant All Files Access
   separately to **S26 Organizer Diagnostic** when using its setup screen.
5. Follow the [phone-only fixture checklist](docs/BUILD_3_2_DEVICE_TEST.md#phone-only-owner-checklist).
   Generic MOVE/UNDO stays disabled. Reports/schema are in
   **build-3-2-verification-reports**; artifacts expire after 14 days.

Do not uninstall or clear Build 3.1 data. The standalone app cannot open its
private journal. Updates to the same diagnostic application ID will still need a
matching certificate; stop on a future mismatch without clearing either app.
The normal main APK is built for checks but is not uploaded as this experiment.

## Permanent signing and journal retention

**No new credentials are required for this diagnostic experiment.** Ordinary
Android debug signing supports first installation of the distinct package.
The optional protected signing workflow now validates/re-signs only the
standalone diagnostic identity, requires trusted main/manual invocation and a
pinned certificate, and fails without credentials. PR jobs need no secrets.

For future main-app builds, retain the original application ID and establish a
long-lived owner-controlled signing key with encrypted backups, protected CI
secrets/approval and certificate checks. A new key alone cannot update installed
Build 3.1: its original private key (or valid authorized signing lineage) is
needed for signature continuity. An APK/fingerprint cannot recreate that key.
No recoverable old key is assumed, and no main replacement/migration is attempted.
See [the signing investigation and future plan](docs/SIGNING_STRATEGY.md).

Android sandboxing separates the two journals. Uninstall/Clear data/device loss
can erase an app's journal; a stable signature cannot prevent that. Backups and
device-transfer exclusions remain. Schemas 1–3 and explicit migrations are
unchanged; there is no destructive fallback or cross-app import/export.

## Current S26 Ultra acceptance

First perform the retained permission/inventory/light/dark/launcher regression
checks. Generic MOVE/UNDO must remain disabled. Diagnostic tests require audit of
the exact standalone identity and only the provided synthetic fixtures.
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
| Standalone diagnostic build | Disabled | Explicit known synthetic fixtures only | Separate test installation |
| Optional protected stable-signed diagnostic APK | Disabled | Same narrow boundary | Same, with certificate continuity |
| Normal debug/release | Disabled | Disabled | Original main identity preserved; not owner-published here |
| JVM adapters | Fixture model only | Disposable temporary directories | Never packaged |

The separate diagnostic capability does not approve arbitrary-file operations.
No operational move engine, conditional deletion or Android atomic-publication
claim is made. See [device review](docs/BUILD_3_2_DEVICE_TEST.md) and the historical
[Build 3.1 review](docs/ANDROID_STORAGE_REVIEW.md). Owner signing/environment
configuration, side-by-side installation, updated identity audit and S26 copy
acceptance remain unverified. Keep the new Build 3.2 PR draft and unmerged.

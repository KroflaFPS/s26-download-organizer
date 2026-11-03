# S26 Download Organizer

Offline Android organizer for the Samsung Galaxy S26 Ultra on Android 17.
**Build 5 adds a read-only classification preview: PREVIEW ONLY • NO FILES MOVED.**
Tap **Preview type** on one Downloads file to see a bounded offline suggestion,
format evidence, provisional readiness, Review reason and bytes read. It never
creates destination folders or enables operations. The separate `:classifier`
module is pure Kotlin/JVM. [Architecture and supported/held formats](docs/BUILD_5_ARCHITECTURE.md),
[phone-only acceptance](docs/BUILD_5_DEVICE_TEST.md), and [review handoff](docs/BUILD_5_HANDOFF.md).

The existing Build 1–3.1 foundation below is retained. PR #4 diagnostic copy and
PR #6 telemetry remain separate drafts; their code is not integrated.
Build 2 was merged at `86082b4b887a82d66cef41ea8072428d50f7f254` through PR #2
and owner-tested, as confirmed by KroflaFPS. Its permission flow, inventory,
light/dark theme, launcher and toolchain are preserved.

**Build 3.1 is an engineering-only, mutation-disabled build. It is NOT approved
for real-file move/Undo testing.** Downloads stays read-only. Automatic
organizing is not active. No owner signing credentials are assumed to exist.

The implemented subset is a persistent Room journal, operation history and
review dialogs, a capability-based single-file coordinator, and conservative
recovery/Undo logic tested with disposable filesystem fixtures. The shipping
Android adapter adds bounded, descriptor-backed local hash/identity observation,
but has no writer or deletion capability. Its public API implementation still
requires Samsung shared-storage validation. Signing alone cannot unlock it. This is a deliberate
fail-closed release gate, not a working Android move feature disguised as success.

## Implemented behavior and limits

- Primary shared Downloads metadata inventory and All Files Access setup on API
  30+. Settings success is never assumed to mean permission was granted.
- API 26–29 still launch with an unsupported-inventory explanation. No legacy or
  unrelated permissions, network access, services, workers or boot receivers.
- Review a regular, apparently eligible file with **Review move**. The dialog
  shows source/target and requires confirmation that downloading has finished;
  **Confirm move remains disabled**. Known temporary names are not eligible.
- Actual Room-backed history (empty until operations exist), journal-open errors,
  and **Check recovery**. No fabricated history. Unresolved records are prominent.
- Recovery is bounded, evidence-only and never retries, publishes, removes files,
  or cleans up orphans. Without permission it defers. At most 20 rows and a
  64 MiB aggregate verification allowance are considered per invocation. A durable
  keyset cursor advances past large/unresolved rows; tap again to continue. Large
  files stay explicitly deferred for manual review. Missing source names never
  prove app-controlled removal, even with historical sync flags. An empty journal
  does not prove hardware crash safety.
- Undo is offered only for sufficiently verified completed moves with no prior
  reverse request. Its execution is disabled in this APK. Copy-only/unresolved
  operations never promise Undo. Replace/Overwrite is never an option.

The operation core forward target is **Organized/Other/** only. Build 5's seven-category
classification proposals are independent of that operation path. The installed APK creates neither
Organized/ nor partial outputs. Inventory remains metadata-only; recovery may
read and SHA-256 hash only explicitly journaled files within bounded limits.
Only an explicit Build 5 preview tap parses a bounded content sample; nothing is transmitted. The existing
boundary-aware inventory exclusion for Organized/ remains in place.

## Build and tests

The unchanged baseline is AGP 9.5.0, Gradle 9.8.2, JDK 21, Kotlin/Compose compiler
2.2.10, Compose BOM 2026.09.00, compile/target API 37, minimum API 26. Build 5 adds
`:classifier` using the same Kotlin version and existing JUnit, without new runtime libraries.
Build 5 uses versionCode 8/versionName 0.5.0-preview. Stable Room 2.8.5 and KSP2 2.3.4
were selected after checking the official documentation and then compiling with
this toolchain. Robolectric 4.16.1 is test-only.

```sh
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
./gradlew --no-daemon :classifier:test testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_room_schema.py
python3 tools/verify_preview_apk.py
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

## Download the engineering APK (no local PC required)

1. Sign into GitHub, open this repository → **Actions → Build debug APK**.
2. Choose a successful run for the intended branch/commit. Download
   **s26-build-5-readonly-preview** under **Artifacts**; retain `certificate.txt`.
3. Extract the ZIP in Samsung **My Files**, then tap `app-debug.apk`. Permit
   installation from the opening app if Android prompts. Samsung Auto Blocker
   or device policy may require reviewing **Settings → Security and privacy**.
4. Launch and verify **Build 5 · Read-only preview**, the disabled-mutation explanation,
   and the preserved Downloads setup/inventory. Artifacts expire after 14 days.
   Reports and exported schema are in **build-5-verification-reports**.

Ordinary PR/push artifacts use an ephemeral Android debug key. They are explicitly
engineering-only. A differently signed APK may not update an installed Build 2.
Build 2 has no operation journal, but **do not blindly uninstall an installed
Build 3 or clear its data**: this deletes its recovery journal. Only transition
from an empty engineering journal after checking it and retaining any necessary
records. Once journaled user operations are enabled in a future audited build,
updates must preserve signature and application data.

## Stable development signing and journal retention

No keystore, password or private key belongs in Git. The optional workflow path
is implemented but has not been run with owner credentials. It still creates a
**mutation-disabled engineering APK**, not an approved operational release.

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
   credentials and uploads **s26-download-organizer-build-3-1-stable-signed-engineering**
   with a public `certificate.txt` report. Missing credentials or a pin mismatch
   fail that job; it does not present the ephemeral APK as stable-signed. The
   script also refuses PR/non-main CI contexts and APKs without the compiled
   mutation-disabled manifest marker. That marker is a signing preflight check;
   the independent code gate and unavailable adapter writers enforce behavior.
6. Compare the certificate report to the independently retained fingerprint on
   every build. Android enforces matching signatures for normal in-place updates;
   do not bypass a mismatch by uninstalling an app with a nonempty journal.

The stable signature supports in-place upgrades; it does **not** protect against
uninstall, Clear data, device loss or storage failure. The journal is app-private
SQLite with backup/device-transfer exclusions to avoid restoring stale operation
state onto a different filesystem. No remote backup or export service exists.
Schemas 1 and 2 are exported. Migration 1→2 preserves operation/event records,
adds the durable recovery cursor, and quarantines legacy inferred completions.
Future schema changes must bump the DB version, retain old schemas, add/test
explicit versioned migrations, and preserve records.
Missing migrations fail closed; there is no destructive migration fallback.

## Current S26 Ultra acceptance checklist (read-only engineering build)

1. Record the commit and Android/One UI version. Install only after resolving the
   signature/journal-retention guidance above. Confirm the engineering banner.
2. With All Files Access denied, confirm setup and no inventory. Open Settings,
   cancel, return, then grant and return: confirm real primary Downloads entries.
3. Compare names, types, extensions, sizes, dates, count, hidden/Unicode names,
   zero-byte files and newest-first order with My Files. Subfolders/link targets
   must not be enumerated. Test Refresh, large-list scrolling, rotation, fonts
   and light/dark mode. Revoke access and confirm the old inventory disappears.
4. Review an eligible file. Check its source, Other/ target and finished-download
   warning. Confirm must remain disabled even after checking the box. Cancel.
   Folders, symlinks and known in-progress names must not offer this action.
5. Check history/recovery. A fresh install should show an empty journal; denial
   must defer recovery. Relaunch/force-stop/restart the device to check launch
   behavior, but do not interpret an empty-journal restart as operation recovery proof.
6. Confirm no Organized/ folder or partial output is created and no Downloads
   entry changes name, content, size or modification date. Do not remove personal
   files merely to test failure states. Do not attempt real move/Undo tests yet.
7. Report results to KroflaFPS/Ada/Jinx. Independent audit and all Build 3 hardware
   mutation/crash/durability acceptance remain pending.

Future operational acceptance additionally requires stable signing approval,
review of Android root/descriptor/no-clobber semantics, safe copy-only behavior
when deletion cannot be proven, persistent journal restart tests, idempotent
recovery, Undo conflicts, interrupted I/O and controlled process termination on
**disposable** device files. Power-loss testing and sudden-loss guarantees are
not claimed. No release gate may be bypassed just to enable a button.

## Roadmap and collaboration

Build 5 implements advisory local classification only. Builds 4/6/7 retain readiness verification, hybrid background
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

## Build 3.1 release matrix and remaining experiments

| Build path | Signing | File mutation | Suitable use |
| --- | --- | --- | --- |
| PR, feature push, ordinary main/manual | Ephemeral debug | Disabled | Launch, inventory, UI regression |
| Protected manual main with provisioned credentials | Pinned stable certificate | Disabled | Same; verify supported in-place upgrades |
| JVM fixtures | Disposable private directories only | Test-only | Failure and concurrency models; never shipped |

No APK is approved for disposable-file move/copy/Undo operational experiments.
The adapter cannot publish or remove a file. Signing does not change these gates.
The protected environment's actual owner-approval/branch rules and credentials
have not been provisioned or independently verified in this assignment.

See [Android storage review](docs/ANDROID_STORAGE_REVIEW.md) for public API
findings, outstanding severity and the exact device experiments required before
any independently reviewed operational build. The read-only checklist above is
safe for this APK. Keep PR #3 open for Ada/Jinx review; Builds 4–7 are untouched.

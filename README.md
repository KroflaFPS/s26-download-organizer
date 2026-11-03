# S26 Download Organizer

Offline Android organizer foundation for Samsung Galaxy S26 Ultra / Android 17.
**Build 4 is a read-only trigger/readiness experiment. Automatic organizing,
MOVE, Undo execution and diagnostic copying remain disabled.**

Builds 1–2 were merged and owner-tested. Build 3.1 is merged at `08ddded`;
current `main` also contains the Codex skill installation at `354000e`. Build 3.2
PR #4 remains separate and draft. KroflaFPS reports its side-by-side synthetic
copy, collision, original-preservation, recovery and permission checks succeeded;
these are owner reports, not independent durability/Android certification.
This branch does not contain its copy implementation or schema 3.

**Audit repair status: OWNER DEVICE ACCEPTANCE AND INDEPENDENT ADA RE-AUDIT PENDING.**
The original same-ID rearm was defective. The targeted repair reserves fresh
native job IDs; live S26 repeated-callback/stop behavior is still unverified.
WorkManager KEEP's late-finishing-worker window is reproduced and retained:
durable requests can wait until later signal/manual/inexact periodic work.

## Build 4 implementation

- Original flat Downloads inventory, All Files Access setup/revocation, Material 3
  theme, disabled operation review and legacy journal/recovery are retained.
- Separate **S26 Read-Only Telemetry** app, ID
  `com.kroflafps.s26downloadorganizer.telemetry`. It installs alongside Build 3.1
  and S26 Organizer Diagnostic, with its own permission, private journal and jobs.
- Explicit opt-in native one-shot Downloads/Files content triggers, bounded
  metadata-only subfolder observations, durable fenced checkpoints, unique ~2h
  WorkManager fallback and boot/package restoration. No permanent service,
  notification, automatic battery-exemption request or network permission.
- UNKNOWN / NOT_READY / POSSIBLY_READY / READY_WITH_EVIDENCE observations remain
  evidence labels only. Zero bytes and stable size do not prove completion.
  No content hashing/parsing in telemetry, classifier or mutation authorization.
- Enable/pause, manual read-only scan, registration check and redacted Markdown
  clipboard/optional sharesheet export. At most 400 events, 4000 observations and
  seven days on subsequent writes; traversal bounds and partial coverage visible.

API 30+ and All Files Access are required for telemetry/inventory. API 26–29
still launch with unsupported behavior explained. MediaStore may miss/directly
coalesce writes; Doze, quotas and One UI can delay work. The registration snapshot
and rough mtime timing cannot prove wake coverage or actual completion latency.
Force-stop blocks execution until manual launch. Samsung acceptance remains pending.

## Install without replacing existing journals

In GitHub → **Actions → Build debug APK**, choose the successful Build 4 draft PR
run for the reviewed head. Download **s26-build-4-readonly-telemetry**, extract it
in My Files, inspect `certificate.txt` (package/label/commit/fingerprints), then tap
`app-telemetry.apk`. It must install **S26 Read-Only Telemetry** as a separate app.
Grant its own All Files Access and explicitly enable observation in its panel.
No signing passwords or uninstall are needed for the first separate install.
Do not install a normal main-package debug APK over Build 3.1.

Same-package updates still need matching certificates. Ordinary CI debug keys
can change; retain the certificate report. Stop on mismatch; never uninstall or
clear any app's data to bypass it. Each app's journal would be erased. Artifacts
expire after 14 days; **build-4-verification-reports** contains tests/lint/schemas.

[Exact phone-only acceptance checklist](docs/BUILD_4_DEVICE_TEST.md) covers denial,
revocation, browsers, My Files, subfolders, lock/overnight/reboot/force-stop,
optional battery comparisons and redacted reporting. Use disposable material.
CI success does not demonstrate Samsung wake coverage, power-loss durability or
source-deletion safety. Independent review and owner device acceptance are pending.

## Build and verification

Retained toolchain: AGP 9.5.0, Gradle 9.8.2, JDK 21, Kotlin/Compose compiler 2.2.10,
Compose BOM 2026.09.00, compile/target 37, min 26, Room 2.8.5/KSP2 2.3.4.
WorkManager 2.12.0 (stable, min 24) is added only to the telemetry variant.
Version code/name: 7 / 0.4.0, with `-telemetry` suffix for that APK.

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest testTelemetryUnitTest
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process lintDebug lintTelemetry
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process assembleDebug assembleTelemetry
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_room_schema.py
python3 tools/telemetry_apk.py app/build/outputs/apk/telemetry/app-telemetry.apk --output app/build/outputs/apk/telemetry/certificate.txt
```

Set JAVA_HOME to JDK 21 and ANDROID_HOME to an SDK with platform android-37.0 and
build-tools 37.0.0. Separate invocations/bounded workers avoid cloud memory
pressure. Signing tests use disposable generated credentials, never owner keys.
Schema checks require reviewed exports to be versioned. CI validates wrapper,
JVM variants, lint, both APKs, schemas, strict compiled package/gates and signing.
Only the read-only telemetry APK is uploaded for owner testing.

| Build path | App ID suffix | MOVE/Undo | Synthetic copy | Telemetry |
| --- | --- | --- | --- | --- |
| Normal debug/release, not uploaded | none | disabled | disabled | disabled |
| Owner read-only telemetry artifact | .telemetry | disabled | disabled | consent required |
| Optional protected stable-signed telemetry | .telemetry | disabled | disabled | consent required |

The main journal schemas 1/2 and migration remain unchanged; a separate exported
telemetry schema 1 avoids PR #4's schema 3. Future versions require explicit
non-destructive migrations. Tests use disposable fixtures/native SQLite, not the
owner's Downloads. The test-only filesystem writers are never packaged.

## Stable development signing and journal retention

This branch's optional protected job signs only the telemetry package. It does not repair or replace the installed main app's certificate. No key setup is required for the first telemetry install. A future main-app signing transition needs a separate owner-reviewed continuity plan.

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
   credentials and uploads **s26-build-4-stable-signed-readonly-telemetry**
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

## Architecture and next milestones

[Build 4 architecture, official references and exact PR #4 integration plan](docs/BUILD_4_ARCHITECTURE.md)
describe lifecycle rearming, bounds, leases, retention and unresolved platform
risks. [Core architecture](docs/ARCHITECTURE.md) retains the journal/no-overwrite
safety model. [Roadmap](docs/ROADMAP.md) and [changelog](docs/CHANGELOG.md) distinguish
implemented telemetry from later classification, routing, mutation and automation.
The [Android storage review](docs/ANDROID_STORAGE_REVIEW.md) still applies:
readiness cannot repair conditional-deletion races. No source deletion is enabled.

KroflaFPS owns device testing; Ada leads architecture; Jinx independently audits;
Codex implements. Keep the separate Build 4 PR draft and unmerged pending review.

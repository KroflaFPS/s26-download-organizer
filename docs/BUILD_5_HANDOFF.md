# Build 5 review handoff for Ada and Jinx

Scope: deterministic offline read-only preview on an isolated branch from verified main
`354000ea4bb2eeff234883eff164a5288483dfd9`. PR #4 head `7cd4811719c98060dcdddb6501c2ffb5849bc12d`
and PR #6 head `f0d3600345e1149edb72f8f33bb4bbfd9bfa6bc2` were inspected live as open drafts.
Neither branch is imported, modified, merged or used as this branch's base.

## Implemented

- Separate `:classifier` Kotlin/JVM module: typed evidence/safety/route/reason model, curated seven-category
  destinations, NFC/compound extensions, metadata gate, 4 KiB header, bounded traditional ZIP inspection,
  conservative text cues, byte accounting, cancellation and cooperative time/read/entry limits.
- Main-app per-file live preview: explicit tap, one ephemeral result, no batch scans, IO dispatcher,
  serialized descriptor reads, permission/lifecycle clearing and stale-result suppression.
- Positional extension of existing read-only handles; separate rooted preview inspector validates regular
  immediate Downloads paths and descriptor/name/anchor metadata. No writable open mode or fallback.
- Adversarial synthetic fixtures, deterministic truncated/random inputs, boundary/resource tests,
  packaged APK identity/permissions/signature report, CI core suite and report/artifact upload.
- Architecture, supported versus held formats, sample outputs and phone-only read-only acceptance guide.

## Deferred and why

PackageManager inspection (unbounded internal I/O relative to this budget); RAR/7z/TAR deeper inspection;
disk images, RAW/vector, legacy/encrypted Office, AAB/multi-APK validation and media track/codec parsing.
These produce Review instead of confident guesses. CBR holds; only conservatively supported CBZ gets
the provisional Ebooks proposal. No cache, persistent override/exemption store, rename/restore UI,
background work, observer integration or automatic operation is added.

Matching ZIP markers are forgeable and payloads are unvalidated. Header recognition cannot detect every
polyglot or malicious file. Metadata/identity observations cannot prove writer closure or all unchanged
bytes. A kernel/FUSE call cannot be interrupted by the cooperative parser clock. These limitations
are visible through confidence/readiness/reasons and must remain separate from mutation authorization.

## Changed surfaces and invariants

- New `classifier/` sources/tests and build file; root settings/catalog/plugin declaration; app gets only
  the internal module dependency using the existing Kotlin/JUnit versions.
- MainActivity, DashboardScreen, new ClassificationViewModel; AndroidClassificationPreview,
  RootedPreviewReader and a positional read method with partial-read/error translation on AndroidReadHandles.
- Version **8 / 0.5.0-preview**, resource descriptions, manifest-contract version expectation.
- README, three Build 5 documents, new APK verifier and existing CI task/artifact/report wiring.
- No source manifest/permissions/components, Room v1/v2 exports, migration/database/DAO, OperationPolicy,
  coordinator/recovery/operation roots, existing scanner, signing script or signing credentials change.
  `MUTATIONS_ENABLED = false` is preserved. The protected stable-signing condition/environment remains
  unchanged; only the ordinary artifact's download name follows the Build 5 artifact rename.
- No repository skills or AGENTS.md are created/committed. Existing Karpathy skill is followed unchanged.

## Validation and hardware boundaries

Commands (full JDK 21 and Android SDK per baseline):

```sh
./gradlew --no-daemon :classifier:test testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/check_room_schema.py
python3 tools/verify_preview_apk.py
git diff --check
```

Core tests are ordinary JUnit/JVM with in-memory synthetic sources; no Android runtime is used.
The core-only command `./gradlew --no-daemon --configure-on-demand :classifier:test` also passes
without an Android SDK environment variable (19 tests, zero failures/errors/skips).
App lifecycle and rooted port tests are JVM tests with test dispatchers/disposable files and injected
descriptor ports. Existing Room tests use Robolectric. None is real Android `/proc`/FUSE/permission UI
or Samsung acceptance. The separate telemetry variant is absent from verified main and intentionally
not imported from PR #6, so there is no Build 5 telemetry execution claim.

Local verification results and fresh CI links are recorded in the draft PR after execution.
Initial environment setup needed an external Android SDK, a full JDK (image supplied only a JRE),
configured proxy and trusted system CA store. No project tool versions are changed to fix setup.

No physical phone/emulator install, actual-user-file mutation, owner signing, protected branch change,
merge, credential provisioning or independent-review approval is performed. Disposable JVM tests create
and change only their own temporary fixtures. The phone guide remains owner acceptance work.

## Questions reserved for owner/review

1. Keep the provisional defaults: numbered `.apk.1`/`.pdf.1` Review, supported CBZ → Ebooks,
   subtitle sidecars → Videos? Configuration can replace comic/subtitle choices independently;
   there is no rename action or automatic route in this build.
2. After independent review, which held format families deserve the next bounded parser?
3. Resolve main-package signing continuity before any owner phone installation; never uninstall or
   clear data/journal to bypass a certificate mismatch. PR #4/#6 integration and all mutation approval
   remain later, explicit decisions.

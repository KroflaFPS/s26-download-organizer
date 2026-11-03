# Build 3.2 signing blocker and permanent-signing plan

## Observed installation blocker

The owner reported that installation of the original Build 3.2 artifact failed:

| APK | Reported certificate SHA-256 |
| --- | --- |
| Installed Build 3.1 | 64b12fc74e66ce02a0feb09d466480096a109f76328eaabb3f9be8c72e4a1e23 |
| Original Build 3.2 main-package APK | eeac4cd81357f95269d586d09245623e77020a1e88c2f20a9cc8dfa847b18f |

Both used the main application ID. Android's ordinary update path requires a
compatible signing identity; an APK certificate is public information, not the
private key needed to sign a matching update. No bypass/uninstall/data clearing
is appropriate. The actual device failure is owner evidence; Codex has not
performed a device installation.

## Current experiment: independent diagnostic app, no owner credentials

A dedicated `diagnostic` build type installs as
`com.kroflafps.s26downloadorganizer.diagnostic`, with launcher label
**S26 Organizer Diagnostic**, version code 6 / name `0.3.2-diagnostic`.
Normal `debug` and `release` retain `com.kroflafps.s26downloadorganizer`; both
disable diagnostic copying. The Kotlin namespace stays unchanged intentionally.
The diagnostic runtime additionally checks the exact diagnostic application ID.

The first standalone installation does not replace Build 3.1 and does not need
its certificate to match. Android allocates separate app-private storage/UIDs;
there is no sharedUserId, exported journal provider, database copy, import or
cross-app migration. `JournalDatabase.open(this)` uses the diagnostic application's
own context. For user 0 the logical paths are:

```text
/data/user/0/com.kroflafps.s26downloadorganizer/databases/operation-journal.db
/data/user/0/com.kroflafps.s26downloadorganizer.diagnostic/databases/operation-journal.db
```

These illustrate sandbox isolation, not hardcoded paths. Installed Build 3.1's
database is never opened or migrated by the diagnostic app. Schemas 1–3 and
migrations are unchanged. Authorities and app-defined signature permissions use
each application ID, preventing coinstallation collisions. All Files Access is
also per app: grant it explicitly to **S26 Organizer Diagnostic**. Its Settings
URI uses `Activity.packageName`, not the unchanged Kotlin namespace. Shared
Downloads/test-output folders are shared storage; private journals are not.

The normal main APK is built only for regression/package checks and is not
uploaded as an owner experiment artifact. CI publishes only
`s26-build-3-2-standalone-diagnostic`, containing `app-diagnostic.apk` and a public
`certificate.txt` that asserts the package/label and compiled gates. Wrong-package
publishing and protected signing fail closed. No private signing credentials are
needed for this first installation; ordinary Android debug signing is sufficient.

This does not solve later diagnostic upgrades with changing ephemeral keys.
Once installed, updates to the **same diagnostic ID** need a matching signature.
Retain that app's journal and the exact artifact/certificate report. Stop on a
future diagnostic update mismatch; do not uninstall or Clear data as a workaround.
No installed-upgrade retention is claimed from host signing tests.

## Future main application: establish permanent signing separately

No owner setup is required now. Before a future main application with journaled
user operations is shipped, choose a long-lived owner-controlled development
signing key for personal sideloads. Play Store signing/publication is unnecessary.
Use a protected GitHub environment restricted to trusted main/manual jobs with
owner approval, encrypted offline key backups, environment-secret password
references, a separately retained/pinned certificate fingerprint and exact APK
package checks. Never commit a keystore/password, expose signing secrets to PRs,
or silently substitute an ephemeral debug key for a stable-signed artifact.

The existing optional protected signing job is currently for the **standalone
diagnostic** artifact only. Its script rejects a main-package APK before writing
private material. A future normal-app signing job must explicitly build and
verify the main package and correct capability gates; this assignment does not
silently add main-app publishing or enable MOVE/UNDO.

Critical continuity issue: a newly generated permanent key cannot by itself
update the already installed Build 3.1 signed by `64b12f…1e23`. First determine
whether its original private key was deliberately retained in an owner-controlled
backup or protected signing system. Do not assume an ephemeral Actions runner
keystore survived. Its certificate/fingerprint/APK cannot recreate the private
key. Authorized key rotation also requires a valid cryptographic signing lineage;
it is not an arbitrary replacement-key workaround.

If the original key is unavailable, retain Build 3.1 and its data. Resolve a
separately reviewed migration/export and deployment plan with the owner before
claiming upgrade continuity. No journal export/import, re-signing bypass,
installation replacement or data reset is implemented here. A stable key protects
future signature continuity from its established installation, not existing data
from uninstall, Clear data, device loss or hardware failure. Backup/device-transfer
exclusions remain enabled to avoid stale journals on different filesystems.

## Verified versus pending

Host checks inspect both compiled APK IDs/labels, noncolliding authorities and
signature permissions, lack of shared UID, capability flags, signer behavior,
certificate-aware reporting and wrong-package rejection. JVM tests exercise real
Android Settings intent construction using controlled activities. They do not
prove Android installer success, Samsung permissions, per-device DB retention,
/proc/FUSE behavior, exclusive creation or sync on hardware. The owner must verify
two installed icons, a fresh diagnostic history, unchanged Build 3.1 history and
the synthetic-copy checklist in [the device guide](BUILD_3_2_DEVICE_TEST.md).

References reviewed:
[application ID versus namespace](https://developer.android.com/build/configure-app-module),
[Android application sandbox](https://developer.android.com/privacy-and-security/security-tips),
[APK signing and continuity](https://developer.android.com/studio/publish/app-signing).

# Build 4 S26 Ultra acceptance — phone only, read-only

These checks gather evidence. CI/Robolectric do not establish Samsung trigger
coverage, FUSE behavior, battery-policy timing or reboot/power-loss durability.
No PC, ADB, root, signing password or uninstall is required. Keep existing Build
3.1 and S26 Organizer Diagnostic installed, with their journals/data intact.

## Install the exact reviewed artifact

1. Review the Build 4 draft PR and its head commit. In GitHub → Actions → Build
   debug APK, choose its successful **pull_request** run for that head. Download
   **s26-build-4-readonly-telemetry**, not an older main/diagnostic APK.
2. Extract the ZIP in My Files. Read certificate.txt: label **S26 Read-Only
   Telemetry**, package **com.kroflafps.s26downloadorganizer.telemetry**, commit and
   public SHA-256 certificate/APK fingerprints. Compare with the run/PR report.
   First installation is a third separate app; different IDs need not share keys.
3. Tap app-telemetry.apk and allow installation from the opening app if prompted.
   Follow Samsung's security/Auto Blocker policy if it blocks sideloading. It must
   install a separate launcher entry, never replace either existing application.
   Stop on any unexpected update prompt or identity. Never uninstall/clear data
   to work around a signature mismatch. Artifacts expire after 14 days.
4. Confirm Build 4 and **READ-ONLY EXPERIMENT: No organizing, moving, copying or
   deleting**. Existing app histories/settings should be unchanged. The new app's
   operation history is independently empty. Confirm move/Undo remains disabled.
   It must not create Organized/ or change any shared files.
5. Grant All Files Access to **S26 Read-Only Telemetry** using its own setup button.
   Cancel/deny first: no inventory/scan success. Grant, return and compare flat
   Downloads inventory with My Files. The new grant must not change other apps'
   grants. API 26–29 should launch with an accurate unsupported explanation.

Ordinary CI debug keys can change between runs. Before a later telemetry update,
compare certificate.txt to the installed artifact's saved report. If fingerprints
differ, stop and retain the existing installation/logs; request a signed compatible
update. A public fingerprint cannot recreate a missing private key. Stable-signing
provisioning is a future owner decision, not a prerequisite for this first install.
Uninstall/Clear data erases that app's private journal and telemetry.

## Consent, controls and baseline

1. Record head SHA, Android/One UI version and local time, leaving names/paths out
   of reports. Begin with normal **Optimized** battery policy. No auto exemption
   request should appear. Observation defaults off.
2. Tap **Check trigger registration** while off: it must not silently enable
   observation. Tap **Enable read-only observation** after granting access. Expect
   a registration snapshot, manual metadata page and bounded deferred work.
3. Tap **Run read-only scan** and then **Check trigger registration**. Wait/recheck
   to see pages advance. Rapid taps must not duplicate concurrent scans. Coverage
   can be CONTINUING or LIMITED_OR_UNREADABLE; it must not claim full traversal
   when bounds/errors occur. Retained readiness counts are not current file counts.
4. Tap **Copy diagnostics as Markdown**. Paste into a private note; inspect for
   filenames, paths/full URIs. None should appear. Optionally use **Share redacted
   diagnostics** and choose a private destination. Sharing is your explicit action.
5. Tap **Pause observation**: no new app-triggered scans/rearming should occur.
   Historical logs remain. Enable again only when ready for further measurements.
6. Revoke All Files Access in Settings during observation, return and run a scan.
   Inventory clears; telemetry reports unavailable access, no false new successful
   page, and checkpoint work remains deferred. Regrant to test recovery.

## Controlled disposable observations

Use only deliberately created/downloaded disposable material. The owner performs
My Files/browser operations; this app never creates, copies, renames or deletes
fixtures. Do not use personal files or download untrusted APKs. There is no need
to fill storage, deliberately power-cut or damage the device.

For each case record owner action time in a note, keep the phone in the stated
condition, then reopen telemetry and export. Content wake counts may coalesce;
periodic/manual wakes are distinct. A missing event is a result to report, not a
reason to claim success or continually change settings.

| Case | Phone-only procedure and observation |
| --- | --- |
| UI open | Download a small disposable text/image in Chrome into Downloads; compare actual action time to CONTENT wake UTC. No 3-second SLA. |
| Samsung Internet | Repeat with its download manager. Pause/resume a disposable download if its UI supports it; temporary/pending evidence should be negative when visible. A paused file can still look quiet. |
| My Files | Copy/rename only a disposable fixture into Downloads; verify the app itself performs no mutation. MediaStore might not immediately notify. |
| Subfolder | In My Files create a disposable Downloads subfolder, then add a fixture inside an existing subfolder. After periodic/manual cycles, inspect page/coverage changes. Root mtime is not trusted as a gate. Bounds must remain visible. |
| Irrelevant notification | Take a disposable camera photo outside Downloads. A Files wake may occur; an irrelevant hint may defer scanning, but durable later reconciliation is still expected. |
| Third-party export | Export harmless generated text from a trusted app into Downloads. Record whether any CONTENT wake occurs; not all providers notify alike. |
| Zero bytes/temp/corrupt | If easily prepared deliberately, observe zero-byte and .part/.crdownload names. Zero bytes is ambiguous. No content/footer integrity verdict exists; a corrupt file may look metadata-quiet. |
| Task dismissed | Enable, swipe telemetry from Recents, perform a disposable download, wait and relaunch/export. Task dismissal does not establish process death. |
| Normal process death | Let Android reclaim it naturally during normal phone use, then perform a later disposable download. Without ADB there may be no reliable proof of process death: mark this condition **unconfirmed** unless independent evidence exists. Do not substitute Force stop. |
| Locked/overnight | Enable, lock the phone; arrange a harmless download before locking or use later wake logs. Leave overnight, reopen and export. Periodic work is ~2h/inexact, not an alarm or promise. |
| Reboot | Enable, reboot normally, unlock, allow time for restricted boot delivery/work, then relaunch/export. Registration restoration is conditional on OS policy. No power-loss durability claim. |
| Force stop | Settings → Apps → this telemetry app → Force stop. Expect no execution until manual launch. Relaunch and check restoration; this is expected Android behavior, not a watchdog failure. |
| Package update | Only with a verified same-certificate higher/equal allowed version APK; record prior counts, update in place, verify private records survive. If mismatch, stop. |
| Optional battery comparison | After retaining Optimized results, owner may manually select Unrestricted for this telemetry app, repeat identical harmless cases and record policy/times. No setting change is required by onboarding and it does not prove main-app behavior. |

Readiness needs separated observations; quickly tapping Refresh is not evidence.
READY_WITH_EVIDENCE means a quiet metadata window plus a provider publication
flag, never writer closure, integrity or permission to move. Provider metadata may
be missing/stale. No classifier or footer parser is implemented. Source identity
and content are not locked by the app.

## Report format and stop conditions

Paste the default Markdown and separately add the scenario, owner action UTC,
observed phone condition, Android/One UI, Optimized/Unrestricted, permission changes
and expected/actual result. Avoid personal content/filenames. Report whether
process death was actually established. The export includes API/variant, latest
wake/page, registration snapshot, fixed event codes/counts and battery snapshots.
The rough wake-minus-mtime column is **not** measured completion-to-wake latency;
manual action notes are separate approximate evidence, not source timestamps.

Stop on unexpected copying/moving/deleting/Undo availability, output-folder
creation, private path export, incorrect package, persistent database error or
permission-bypass behavior. Preserve all journals and reports; do not clear data.
Independent audit and owner acceptance remain pending until evidence is reviewed.

# S26 Download Organizer architecture

## Build 1: implemented foundation

One Android application module (`:app`) with application ID
`com.kroflafps.s26downloadorganizer`. `MainActivity` hosts a Material 3 Compose
dashboard and system light/dark themes. The dashboard identifies **Build 1**,
reports **Foundation ready**, and explains that file organization is inactive.
It supports screen insets, scrolling, and system text scaling.

Build 1's source manifest declares no permissions, services, or receivers. The
app has no file access, monitoring, workers, classification, routing, file
operations, journal, Undo, or notifications. It has no backend, authentication,
telemetry, or analytics.
The JVM tests check the source manifest's foundation boundary and launcher
declaration; they do not test Android storage or device runtime behavior.

Standard AndroidX dependencies contribute startup initializers, a profile
installer receiver, and an app-scoped signature permission to the merged
manifest. These do not grant storage access or implement organizer automation.

## Toolchain

| Component | Selection |
| --- | --- |
| Android Gradle Plugin | 9.5.0 |
| Gradle wrapper | 9.8.2, distribution SHA-256 pinned |
| JDK and JVM target | 21 |
| Kotlin / Compose compiler plugin | 2.2.10 / 2.2.10 |
| Compose BOM | 2026.09.00 (Material 3 1.4.0) |
| AndroidX Core / Activity Compose / Lifecycle runtime | 1.19.1 / 1.13.0 / 2.11.0 |
| JUnit | 4.13.2 |
| Compile SDK / target SDK | Android 17, API 37 (stable base platform `android-37.0`) |
| Build tools | 37.0.0 |
| Minimum SDK | API 26 (Android 8.0) |

All dependencies are stable releases. Versions were checked against Google
Maven, Maven Central, the Android SDK repository, and Gradle release metadata.
AGP 9.5.0 requires Gradle 9.6.0 or later; this project uses 9.8.2. AGP supplies
Kotlin 2.2.10 internally, so a separate `org.jetbrains.kotlin.android` plugin is
unnecessary. The Compose compiler plugin matches that Kotlin version. API 37 is
the newest stable major target SDK; Build 1 needs no minor SDK extension APIs.

API 26 provides a reasonable modern baseline for later storage adapters without
restricting the app to the target phone. The Galaxy S26 Ultra on Android 17 is
the primary owner-tested device. Compilation is not evidence of successful
installation or runtime testing on that device.

The version catalog owns dependency versions. CI uses the wrapper, JDK 21, and
explicit SDK packages, runs JVM tests and Android lint, assembles the debug APK,
and uploads it with verification reports. Ordinary Android debug signing is
sufficient; no release keystore or GitHub secret is required. Debug signing keys
may differ between CI runs, so update installation is not guaranteed (see README).

## Future hybrid organizer (planned, not implemented)

The app is an offline organizer. Opportunistic event detection will schedule
bounded processing; WorkManager will provide deferred reconciliation to discover
missed downloads. Neither file events nor WorkManager imply immediate execution
or uninterrupted observation. Reconcile after interruptions and access changes.

A permanently running `dataSync` foreground service is **not** a dependency.
Android background execution restrictions make it unsuitable as the foundation
of indefinite monitoring. No organizer service, boot receiver, or worker ships
in Build 1.

Planned flow:

```text
Download events --+-> candidate processing -> readiness -> classification -> routing
Reconciliation --+                                                           |
                                                               journaled operations
                                                                             |
                                                               history / Undo / batches
```

### Storage and destination policy

All-files access (`MANAGE_EXTERNAL_STORAGE`) is the **provisional primary storage
strategy for later builds**, subject to real-device validation and distribution
policy. It will require an explicit owner-facing access flow and handling of
denial/revocation. Storage adapters must isolate Android-specific APIs from pure
domain policies. Build 1 neither declares this permission nor requests it.

The intended download source is `/storage/emulated/0/Download/`. All organized
files will live under the unified destination `/storage/emulated/0/Organized/`:

- `Installers/`
- `Documents/`
- `Images/`
- `Audio/`
- `Videos/`
- `Archives/`
- `Other/`

The organizer must exclude its entire destination root from source observation
and reconciliation, including when source configuration changes. These paths
describe the primary device's shared storage; adapters must resolve and validate
storage locations rather than spread hardcoded filesystem paths across UI code.

### Safety and file readiness

File events, filename extensions, and a single stable size sample do not prove
download completion. A later readiness policy will conservatively combine
temporary-name checks, repeated stability observations, and evidence available
from storage adapters; uncertain candidates remain deferred. Revalidate the
source immediately before operating because it may change after classification.

The move engine must record intent and results persistently, handle partial
failures and process death, verify destination success before any source removal,
and support recovery and reversible operations. Cross-filesystem operations need
an explicitly verified copy/commit/removal sequence. Undo must recheck source and
destination state and preserve files created since the original operation.

Duplicates are preserved: never overwrite or silently delete an existing file.
Future destination allocation must create unique names with concurrency-safe
reservation/creation, rather than relying on an existence check followed by a
potentially overwriting move. Reconciliation and events must converge without
applying the same operation twice. None of this operational logic exists yet.

### Notifications and history

Automatic moves will produce **batched, grouped notifications** to avoid one
notification per file. Persistent activity history will expose individual
operations and Undo actions. Notifications are a summary; the operation journal
is the durable record. Notification permission handling belongs to a later build.

## Package boundaries

Build 1 uses `com.kroflafps.s26downloadorganizer.MainActivity`, `ui`, and `ui.theme`.
There are no empty packages or speculative implementations. These boundaries
are reserved for future work within the same module until a real reason to split
Gradle modules appears:

| Package | Responsibility | Build 1 status |
| --- | --- | --- |
| `ui` | Compose dashboard, presentation state, theme | Dashboard and theme implemented |
| `domain` | Pure Kotlin models and policies | Planned |
| `storage` | Permission/access adapters, inventory, storage I/O | Planned |
| `detection` | Opportunistic download events; exclude destination root | Planned |
| `processing` | Candidate coordination and deferred reconciliation | Planned |
| `readiness` | Conservative completion and stability decisions | Planned |
| `classification` | Local APK metadata, document, media and archive inspection | Planned |
| `routing` | Rules, categories, destination selection | Planned |
| `operations` | Safe move engine and recovery orchestration | Planned |
| `history` | Persistent journal, activity history and Undo support | Planned |

Filesystem operations stay outside Compose. Keep policies independent of Android
where practical and test adapters separately. Build 1 has no DI framework,
WorkManager, Room, or speculative framework abstractions.

## Collaboration

- **Gemini:** technical research, Android API validation, code/diff audit.
- **ChatGPT:** architecture and task framing.
- **Codex:** primary implementation engineer.
- **KroflaFPS:** project lead and device tester.

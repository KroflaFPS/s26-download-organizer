# S26 Download Organizer architecture

## Build 1: merged and owner-tested foundation

One Android application module (`:app`) with application ID
`com.kroflafps.s26downloadorganizer`. `MainActivity` hosts a Material 3 Compose
dashboard and system light/dark themes. The original dashboard identified **Build 1**,
reported **Foundation ready**, and explained that file organization was inactive.
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

## Build 2: implemented read-only inventory (device acceptance pending)

Build 1 was merged through PR #1 and tested on the owner's S26 Ultra, as reported
by KroflaFPS. Build 2 preserves the module, theme, launcher, foundation status and
proven toolchain. It increments versionCode to 2/versionName to 0.2.0 and labels
the dashboard Build 2. It adds only the MANAGE_EXTERNAL_STORAGE source permission,
metadata inventory, lifecycle state, tests, and Build 2 artifact names.

### Permission and compatibility

`StorageAccessAdapter` guards API 30 calls and checks
`Environment.isExternalStorageManager()`. API 26–29 intentionally show an
unsupported-inventory explanation; no legacy read permission is declared.
A tap opens the package-URI app All Files Access screen, falling back in order
to the general All Files Access list and application details. Activity-not-found
and security failures are handled, with manual instructions if none opens.
There are no unsolicited Settings launches or ordinary runtime permission dialogs.

Permission is rechecked on every resume, refresh, before the repository scan,
and before results enter screen state. Settings returning successfully is never
proof of access. Pause cancels work and clears the inventory; denial or revocation
returns to setup. No inventory or grant assumption is persisted. Permission changes
while continuously foregrounded are detected on refresh or the next resume,
not through observers or polling.

This broad permission is intentional for a personal sideloaded organizer, not a
claim of Play Store distribution eligibility. OEM Settings pages and Android 17
shared-storage behavior still need owner testing. Only primary shared Downloads
is supported; removable storage, virtual providers, and work profiles are outside
Build 2. Android-protected directories are not promised accessible by the grant.

### Read-only storage boundary

`AndroidDownloadsRepository` resolves Downloads using
`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` and resolves
Organized relative to Android's primary external-storage root. These deprecated
but supported path APIs are isolated here for direct filesystem access with the
verified special grant; no paths are constructed in Compose. Mounted and
mounted-read-only storage can be inventoried; unavailable storage is an error.
No code creates Downloads or Organized if absent.

`ReadOnlyInventoryScanner` uses a closeable NIO DirectoryStream and BasicFileAttributes
with NOFOLLOW_LINKS. It lists only immediate children. A symbolic-link source
root is rejected; child links are described using their own metadata and never
traversed. No content streams, APK parsers, media decoders, copy/move/delete,
rename, creation, or metadata-writing operations exist in production inventory
code. Domain entries contain only identity/path, original name, entry type,
nullable file size and modification time; extensions come only from filenames.

Source exclusion uses absolute normalized Path components and Path.startsWith,
not string-prefix matching: root and descendants are excluded, similarly named
siblings are not. It is a lexical policy, not a symlink-target resolver. Build 2
prevents traversal by rejecting link roots and never following child links;
future configurable roots/mutation engines will need their own alias/race review.

Missing/changed entries are skipped with a visible partial count. Unreadable
entry metadata is labeled unavailable and included in that count. A directory
listing/iteration failure discards partial results and shows an error. Missing
timestamps sort last; otherwise sorting is descending modification time, then
exact filename and normalized identity in locale-independent lexical order.
Directories have no recursive size. Zero-byte ordinary files remain zero.
A scan is a changing filesystem snapshot, not an atomic inventory or readiness
claim. Large lists are held as metadata only and presented with LazyColumn.

### State, threading, and tests

`InventoryViewModel` exposes StateFlow for checking, permission-required,
unsupported, loading, loaded (including empty), and error states. A repository
port keeps Android out of state tests. Refreshes coalesce while active; cancellation
and a generation check prevent stale results. Repository Mutex serialization
prevents an old blocking filesystem call and a resumed scan from overlapping.
All enumeration, stat calls and sorting run on Dispatchers.IO, with cooperative
cancellation between entries and explicit stream closure. Compose only formats
visible rows and sends events; it has no filesystem access.

Added lifecycle runtime Compose/ViewModel dependencies reuse the existing 2.11.0
version; explicit coroutines core/test 1.10.2 support cancellation/state and JVM
tests. No DI or state framework is added. Tests use temporary fixtures or fake
repositories only: metadata conversion, names/extensions, sorting, exclusions,
empty/mixed/changing/inaccessible sources, symlinks, failures, revocation,
refresh coordination, cancellation, and recovery. Manifest tests inspect both
source and AGP's actual merged debug manifest with explicit permission/component
allowlists. These tests do not establish device-specific permission behavior.

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
in Build 1 or Build 2.

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
policy. Build 2 implements an explicit owner-facing access flow and denial/revocation
handling for read-only inventory. Future write behavior remains unimplemented.
Storage adapters isolate Android-specific APIs from pure domain policies.
Build 1 neither declared this permission nor requested it.

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

Build 2 uses `com.kroflafps.s26downloadorganizer.MainActivity`, `ui`, `ui.theme`,
`domain`, and `storage`.
There are no empty packages or speculative implementations. These boundaries
stay within one module; planned packages will be created only when needed:

| Package | Responsibility | Build 2 status |
| --- | --- | --- |
| `ui` | Compose dashboard, presentation state, theme | Dashboard, theme and inventory state implemented |
| `domain` | Pure Kotlin models and policies | Inventory models, repository port and source exclusion implemented |
| `storage` | Permission/access adapters, inventory, storage I/O | Access adapter and metadata-only inventory implemented |
| `detection` | Opportunistic download events; exclude destination root | Planned |
| `processing` | Candidate coordination and deferred reconciliation | Planned |
| `readiness` | Conservative completion and stability decisions | Planned |
| `classification` | Local APK metadata, document, media and archive inspection | Planned |
| `routing` | Rules, categories, destination selection | Planned |
| `operations` | Safe move engine and recovery orchestration | Planned |
| `history` | Persistent journal, activity history and Undo support | Planned |

Filesystem operations stay outside Compose. Keep policies independent of Android
where practical and test adapters separately. Build 2 has no DI framework,
WorkManager, Room, or speculative framework abstractions.

## Collaboration

- **Gemini:** technical research, Android API validation, code/diff audit.
- **ChatGPT:** architecture and task framing.
- **Codex:** primary implementation engineer.
- **KroflaFPS:** project lead and device tester.

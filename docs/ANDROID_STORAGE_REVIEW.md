# Build 3.1 Android storage feasibility review

**Historical Build 3.1 assessment.** Build 3.2 adds a separate synthetic-only
O_EXCL copy experiment; see [current capability review](BUILD_3_2_DEVICE_TEST.md).
Generic mutation/removal stays disabled.

Reviewed 2026-11-02 against the public Android references and API 37 SDK.
This is an engineering assessment, not Samsung hardware acceptance. All APKs
remain mutation-disabled. No real Downloads mutation tests were run.

## Public API findings

| Capability | Public API and finding | Build 3.1 status |
| --- | --- | --- |
| Roots/access | Environment primary storage + existing All Files Access adapter; API 30+ | Preserved; API 26–29 accurately gated |
| Descriptor reads | Os.open/fstat/read/lstat/readlink, ParcelFileDescriptor.dup/getFd; O_RDONLY, O_NOFOLLOW, O_NONBLOCK, O_CLOEXEC | Implemented read-only, SDK compiled |
| Anchored child lookup | Directory descriptor via /proc/self/fd/N/child; no public Os.openat API in the reviewed SDK | Runtime fail-closed if unavailable; device semantics unverified |
| Identity/hash | fstat dev/ino, size, nanosecond mtime/ctime; bounded SHA-256; compare descriptor and current anchored entry | Implemented snapshot; not a lock or removal receipt |
| Exclusive staging | StandardOpenOption.CREATE_NEW specifies atomic existence/create checking | Feasible primitive; Android rooted writer not implemented or certified |
| Bounded copying | 64 KiB streaming, incremental SHA-256 and cancellation | Real-I/O fixtures only; shipping adapter has no writer |
| Final publication | Os.link/Files.createLink can refuse an occupied target on supporting filesystems | Real concurrent host-fixture tests pass; shared-storage hard links/support/anchoring unverified, Android publisher disabled |
| Rename/move | ATOMIC_MOVE and SecureDirectoryStream.move may replace an existing target | Rejected as proof of no-clobber publication |
| Source removal | Path unlink or SecureDirectoryStream.deleteFile does not conditionally compare inode and unlink as one operation | Unsupported; no stat-then-delete fallback |
| Sync/durability | Os.fsync and FileDescriptor.sync request file sync; directory support/ordering varies by storage stack | Fixture requests tested; Android mutation sync unsupported; no sudden-power-loss guarantee |

References:

- [Os public methods](https://developer.android.com/reference/android/system/Os)
- [OsConstants](https://developer.android.com/reference/android/system/OsConstants)
- [StructStat](https://developer.android.com/reference/android/system/StructStat)
- [ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor)
- [CREATE_NEW](https://developer.android.com/reference/java/nio/file/StandardOpenOption#CREATE_NEW)
- [Files move/createLink](https://developer.android.com/reference/java/nio/file/Files)
- [SecureDirectoryStream move/deleteFile](https://developer.android.com/reference/java/nio/file/SecureDirectoryStream)

Compilation against the public SDK ruled out Os.realpath and O_DIRECTORY as
public members usable here. The implementation uses Path.toRealPath and fstat
validation after read-only open instead, with no hidden API or numeric-flag shim.
The primary Android storage alias may itself be rejected by no-follow opening;
unsupported aliases, absent/placeholder identity values, permission changes,
SELinux restrictions and /proc failures produce unavailable evidence. There is
no weaker path-only fallback. A descriptor anchors reads to an object; it does
not stop another application mutating that object or changing its directory.
Read snapshots never authorize production deletion.

## Recovery and journal proof

Intent, unlink, evidence persistence, directory synchronization and journal
finalization are separate boundaries. A missing source cannot show who removed
or renamed it, even when the old journal contains removal and sync flags.
Recovery therefore never finalizes an interrupted operation as COMPLETED.
Verified source+destination can be COPY_ONLY; absent source+matching destination
is RECOVERY_REQUIRED/REMOVAL_UNCONFIRMED. No recovery operation writes user files.
Legacy inferred completions are quarantined by migration 1→2; old confirmed
coordinator records are retained. History retains their original identifiers,
related Undo claims and chronology. No UNDONE_EXEMPT ledger is introduced.

Each invocation considers at most 20 records, reserving up to three source sizes
within 64 MiB, plus a one-byte EOF/growth probe per observation. Oversized rows
are explicitly deferred for manual review. Cursor progress is independent of
revision/update times and persisted after each considered row, including failed
row updates. A fixed cycle ceiling prevents new inserts extending a cycle
forever. Repeated calls continue and wrap once; they never automatically retry
ambiguous I/O. Complete database failure stops recovery; it cannot guarantee
progress while journal persistence is unavailable. Invalid records remain
unresolved, are counted as journal failures and do not trigger filesystem I/O.

## Outstanding findings and severity

- **High operational blocker:** no certified Android no-clobber publisher or
  rooted writer. No API 37 compilation or host test establishes Samsung FUSE
  behavior. All production mutation entry points remain unavailable.
- **High operational blocker:** no compare-identity conditional removal primitive
  established through public Android APIs. Future verified publication must be
  copy-only unless this is independently resolved; safe publication alone does
  not justify unlinking. No APK currently even enables copy-only writes.
- **High release prerequisite:** owner stable-signing provisioning and protected
  environment approval/branch settings are unverified. No keys/passwords created,
  uploaded or assumed. Disposable signing tests prove tool behavior only.
- **Medium validation gap:** /proc descriptor path visibility, primary alias
  resolution, inode/change evidence stability, actual SQLite upgrade retention
  and restart behavior need S26/Android 17 experiments.
- **Known conservative limit:** large recovery records are explicitly deferred,
  never silently skipped forever or hashed without a limit. No large-file/manual
  verifier UI is implemented. Such records require human review.
- **Residual durability limit:** database and filesystem have no shared atomic
  transaction; sync requests do not certify sudden power-loss persistence.

## Exact device experiments still required

Current APK: use only the README read-only acceptance checklist (permission,
inventory, disabled controls, empty history/relaunch, no output creation).
It is unsuitable for disposable-file move/copy/Undo operational testing.

After separate review, a dedicated diagnostic/operational build would be needed:

1. Record model, Android/One UI, mount/filesystem and build/certificate identity.
   Use newly created disposable files only; never point a fixture adapter at Downloads.
2. Confirm the Android primary alias opens without weakening no-follow checks.
   Verify /proc descriptor lookup, fstat identity/stamps and canonical paths for
   normal, zero-byte, Unicode, symlink, renamed and same-size replaced fixtures.
   Unavailable guarantees must remain a visible refusal.
3. With an independently reviewed publisher, race two exclusive allocations
   against existing and externally replaced names. Verify every preexisting byte,
   no truncated/overwritten entries and journaled partial outputs on interruption.
   If hard links are unsupported, stop; do not substitute a replacing rename.
4. Interrupt permission, fill disposable storage under controlled limits and
   terminate the process at intent/copy/verify/publication/journal boundaries.
   Relaunch repeatedly; verify exact contents, distinct copy-only/recovery states,
   no duplicate retries, cursor fairness and preservation of originals.
5. Measure supported file/directory sync responses and record limitations. Normal
   restart or force-stop does not prove sudden-power-loss durability.
6. Exercise Undo with occupied original name (Cancel or unique-name consent),
   modified/missing organized content and interrupted journal transitions. Every
   newer Downloads occupant must survive byte-for-byte. Deletion stays disabled
   while conditional removal remains unsupported.
7. Provision stable signing using README instructions. Compare independently
   recorded certificate fingerprints across two approved builds. Perform an
   in-place upgrade with disposable journal entries, preserving every operation,
   event, cursor and app data. Do not uninstall or Clear data to bypass a mismatch.

Ada/Jinx review and owner acceptance are outstanding. The PR may be reviewed as
an engineering foundation; operational release gates are not satisfied.

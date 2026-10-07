# SafeSync reliability and performance changes

This change set strengthens session completion, configuration validation and
conflict handling, and reduces repeated disk work. It does not publish a release
or alter the native Syncthing submodule. The existing data format remains
compatible; older session journals resume conservatively from the close step.

## Session safety

- Finalization records durable checkpoints for closing ES-DE, exporting game
  metadata, publishing shared state and synchronizing files. Retry resumes the
  failed step. Neither an export failure nor a failed journal write can authorize
  switching devices. Every launch creates a journal, including online sessions.
- Starting ES-DE passes through the same serial queue as imports. A launch
  reservation prevents subsequent imports; an online launch also refreshes the
  synchronization gate. Repeated callbacks cannot advance finalization twice.
- Received metadata marks a pending import rather than modifying gamelists in a
  background callback. Safe Launch applies it after the full-sync gate. Changes
  arriving during an import require another start check and cannot be lost to an
  unconditional clearing of the pending flag.
- A missing process-list entry is no longer accepted as evidence that ES-DE
  stopped. Stock Android requires **App info → Force stop → return → Retry**.
  SafeSync verifies the system stopped flag. Save and close the emulator first;
  SafeSync enforces ES-DE's always-save mode but cannot save emulator RAM for you.
- Corrupt journals and shared snapshots produce explicit errors instead of
  appearing absent. Journals keep a private prior-checkpoint backup. Do not delete
  a damaged journal merely to unlock launch; pending changes require recovery.

## Configuration and conflicts

- Ignore-list writes wait for server acknowledgement and read back the effective
  rules. The separate shared-state folder allows `.esde-sync-global` and then
  ignores everything else. Existing raw XML/XCC inclusions remain below this
  catch-all and are therefore ineffective. ROM system allowlists are preserved;
  `gamelist.xml` stays first and local.
- Selected roots must be accessible, writable, correctly contained and shared
  with the primary device. ROM and shared-state roots cannot overlap. The role
  pickers exclude the folder already assigned to the opposite role.
- Logical setting and collection conflicts expose local/shared values. The
  chosen value is backed up before replacement. Changed hashes invalidate a stale
  decision. A change on only one side no longer creates a two-sided conflict.
- Unavailable themes retain the local value and a withheld snapshot, preventing
  a subsequent publish from treating that intentional difference as a conflict
  or promoting the local fallback to other devices.
- Missing local gamelists fail explicitly. Sidecars reject invalid types,
  negative counters, impossible timestamps, oversized content and escaped paths.
  Missing optional fields continue to preserve local XML.

## Performance and progress

- Export parses gamelists with SAX, retaining metadata but not the full DOM.
  Import still uses a DOM to preserve unshared XML, but a bounded streaming reader
  removes the full-file byte/string copies and separate DOCTYPE scan.
- Identical shared snapshots are not rewritten. Settings are indexed once per
  update. Observer exports invalidate diagnostics without immediately rescanning
  all sidecars; finalization and explicit diagnostics refresh those counts.
- Conflict inventories use a bounded, shell-free scan. Results are reused for
  the same local index revision for up to 30 seconds, with explicit invalidation
  after conflict resolution. Scan failures block readiness instead of appearing
  as empty results. Version history and symlinked subtrees are not traversed.
- Remote pending lists are paginated beyond 1,000 entries. A blocking item can
  stop pagination early; an unresolved limit or request failure never means ready.
- Upload/download rates use cumulative Syncthing traffic and monotonic elapsed
  time. Lightweight requests run separately from adaptive per-folder checks and
  stop with the screen lifecycle. They measure total Syncthing traffic, not a
  fabricated per-folder rate.
- Unknown-duration phases display an indeterminate bar and pending item count.
  Two minutes without visible progress produces a warning and continued polling,
  rather than terminating a healthy long transfer.

## Release checks

Local verification on 2026-10-07 passed Java/Kotlin compilation and 115 unit tests
(zero failures). Android lint reported zero errors, 46 warnings and one hint.
Actionlint accepted both modified release workflows; `git diff --check` passed.
The Windows verification used the existing in-process Java/JUnit helpers because
the normal worker launch is unreliable in this environment. It did not assemble
or publish an APK. No Android device was connected for the hardware checks below.

The private-signing workflow is prepared but not activated. Follow
[private signing migration](PRIVATE_SIGNING.md) to supply and back up the keys,
verify the installed signer, and test an in-place update before enabling it.
Old Android versions retain the public signer for compatibility.

Before releasing, run the unit suite, Android lint and workflow validation. On a
handheld, also verify these device-dependent cases:

1. Install over the current release without losing the Syncthing device ID or
   pending session. Finish first setup and select SafeSync as Home.
2. Exercise start sync, ES-DE launch, emulator save/exit, Home, Force stop, final
   sync and Done. Done must stay idle. Try rotating/backgrounding SafeSync during
   import and during launch reservation.
3. Disconnect the NAS during upload, restart SafeSync and reconnect. An unfinished
   export or publish must be retried before completion.
4. Test low storage, missing gamelist, unavailable theme, two-sided settings
   changes and a conflict copy disappearing before the decision.
5. Confirm readable rates and status in both light and dark mode. Check long
   transfers, zero traffic, primary-device disconnect and process restart.

Unit tests cover persisted resumption, failed steps, delayed/failed ignore
verification, stale conflict decisions, theme round trips, journal compatibility,
parser validation, root separation, conflict caching and rate calculation.
Device lifecycle tests and measured battery/memory benchmarks remain release
checks; structural reductions in parsing and disk writes are not measured speedup
claims.

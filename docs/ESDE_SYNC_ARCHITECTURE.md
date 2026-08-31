# ES-DE Gaming Sync architecture

## Invariants

1. `gamelist.xml` is local state. It is never synchronized or merged by this
   application. The Syncthing basename ignore rule `gamelist.xml` applies at any
   depth and is appended through the existing ignore REST API without discarding
   existing lines.
2. Cross-device state is one JSON sidecar per ES-DE `<game><path>` beneath the
   same system's `.esde-sync` directory.
3. Imports may change only `favorite`, `completed`, `playcount`, `playtime`,
   `lastplayed`, and `altemulator`. A missing JSON member means “leave local XML
   unchanged”.
4. The native Syncthing submodule is not modified. When the feature is disabled,
   the wrapper starts no observer and performs no metadata I/O.

## Layout and identity

For `ES-DE/gamelists/snes/gamelist.xml` and game path
`./RPG/Chrono Trigger.sfc`, synchronized state is stored at:

```text
ES-DE/gamelists/snes/.esde-sync/RPG/Chrono Trigger.sfc.esde.json
```

The stable identity is `snes|./RPG/Chrono Trigger.sfc`. The actual ES-DE path is
authoritative, so a multi-disc `.m3u` entry remains one game. Absolute paths,
Windows drive paths, NULs, empty path segments, `.` and `..` are rejected.
Canonical-path containment is checked before reading or writing a sidecar.

Sidecars use schema version 1 and nullable members. Unknown JSON members are
ignored for forward compatibility; unknown schema versions, malformed JSON,
files over 64 KiB, and filename/path mismatches are counted invalid.

## Import and export

`EsdeGamelistParser` uses a real XML DOM with DTDs, XInclude, external entities,
and entity expansion disabled. Work is system-scoped, indexed by path, and never
performed on the UI thread. Rewriting a changed document retains game order,
attributes, all unrelated and unknown elements, and only adds/changes the six v1
children. Before the first automatic XML modification per system, a copy is
stored under private app data. The newest five manual/automatic backups are
retained and cannot be synchronized by Syncthing.

`EsdeMetadataBridge` compares each parsed system with a private snapshot. A local
change writes only the affected game's sidecar; an unchanged 10,000-game list
writes nothing. Sidecars and XML use a flushed temporary sibling followed by a
rename transaction. Import saves the post-import snapshot before a debounced
observer export can run, so a received sidecar cannot create a feedback loop.

Sidecars that do not match a local `<game>` remain untouched and are reported as
unmatched. A later ES-DE scan/import can match them; the bridge never fabricates
partial `<game>` nodes.

## Runtime integration

`SyncthingService` owns one `EsdeSyncCoordinator` alongside `RestApi` and
`EventProcessor`. It uses one executor for every metadata transaction. The
coordinator exists for the service lifetime, observes preferences, and starts
per-system `FileObserver`s only when the feature and bootstrap are active.
`CLOSE_WRITE`, `MOVED_TO`, `CREATE`, `DELETE_SELF`, and `MOVE_SELF` are coalesced
for 900 ms per gamelist. New system directories are discovered by a parent
observer.

`EventProcessor` forwards successful `.esde-sync/**/*.esde.json` ItemFinished
paths. The coordinator confines the path to the configured gamelists root and
imports that system on its serial executor. Invalid bridge input is logged under
`ESDESync`; it cannot terminate the normal Syncthing service.

## Bootstrap

Automatic export is denied until `bootstrapComplete`:

- Existing sidecars: mark bootstrap as pending, let Safe Launch complete its
  full-sync gate, import them, create the local snapshot, then enable
  observation/export.
- No sidecars: the user must explicitly choose **Use this device as initial
  metadata source**. A full export then creates sidecars and enables observation.

Changing the ES-DE directory resets bootstrap authority.

## Safe Launch

The second launcher entry persists a session UUID, launch timestamp, whether
ES-DE was launched, offline override, pending changes, and the previous
Syncthing force state. It temporarily requests force-start, rescans only selected
folders, and evaluates one state machine:

```text
NOT_CONFIGURED → STARTING → WAITING_FOR_PRIMARY → RESCANNING → SYNCING
→ IMPORTING_METADATA → READY_TO_PLAY → ESDE_RUNNING → EXPORTING_METADATA
→ SYNCING_AFTER_PLAY → SAFE_TO_SWITCH
```

`OFFLINE_OVERRIDE` and `ERROR` are explicit branches. READY requires the primary
peer connected and unpaused; every selected folder present, unpaused, idle, with
zero needs/pull errors; aggregate primary completion 100% with zero remote bytes;
and zero discovered conflict files. Cached REST entries trigger their existing
fresh requests and the gate polls until they settle. A timeout never becomes
green. Users can always retry or start without synchronization.

On return from ES-DE, a one-second flush window precedes final export. Selected
folders are rescanned and must pass the same gate before `SAFE TO SWITCH DEVICE`
appears. Otherwise pending local changes stay visible for the next session. The
previous force state is restored only after a successful post-sync or explicit
session completion.

## Known v1 boundaries

- Syncthing sidecar conflicts are surfaced and block a green state; v1 does not
  guess how to merge simultaneous offline edits of the same game.
- Filesystem paths use the wrapper's existing all-files permission and folder
  picker because Android `FileObserver` cannot reliably watch arbitrary SAF
  providers.
- Completion is conservatively polled through the wrapper's folder status and
  primary-device completion caches. Their cache-miss paths issue REST reads;
  expensive completion queries are intentionally not fired continuously.
- Automatic ES-DE process-exit callbacks do not exist on Android. Returning to
  the still-persisted Safe Launch task is the supported post-sync trigger.

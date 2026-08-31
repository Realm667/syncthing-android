# ES-DE Gaming Sync architecture

## Invariants

1. `gamelist.xml` is local state. It is never synchronized or merged by this
   application. The Syncthing basename ignore rule `gamelist.xml` applies at any
   depth and is placed before broader include rules through the existing ignore
   REST API without discarding existing lines.
2. Cross-device state is one JSON sidecar per ES-DE `<game><path>` beneath the
   same system's `.esde-sync` directory.
3. Imports may change only `favorite`, `completed`, `playcount`, `playtime`,
   `lastplayed`, `altemulator`, `players`, and `rating`. A missing JSON member
   means “leave local XML unchanged”.
4. The native Syncthing submodule is not modified. When the feature is disabled,
   the wrapper starts no observer and performs no metadata I/O.

## Layout and identity

For either `ES-DE/gamelists/snes/gamelist.xml` or
`ROMs/snes/gamelist.xml` and game path `./RPG/Chrono Trigger.sfc`, synchronized
state is stored beneath that same system directory:

```text
ES-DE/gamelists/snes/.esde-sync/RPG/Chrono Trigger.sfc.esde.json
# or
ROMs/snes/.esde-sync/RPG/Chrono Trigger.sfc.esde.json
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
attributes, all unrelated and unknown elements, and only adds/changes the eight v1
children. Before the first automatic XML modification per system, a copy is
stored under private app data. The newest five manual/automatic backups are
retained and cannot be synchronized by Syncthing.

ES-DE may write an optional top-level `alternativeEmulator` element next to
`gameList`, producing an XML fragment with two roots. Both the Android bridge
and the NAS bootstrap accept exactly this known form, preserve it on writes and
continue to reject additional roots, DTDs and external entities.

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
- No sidecars and Android is authoritative: the user must explicitly choose
  **Use local Android gamelists as initial metadata source**. A full export then
  creates sidecars and enables observation.
- No sidecars and a NAS/desktop is authoritative: the Android action must not be
  used. `scripts/Initialize-EsdeSidecars.ps1` creates the initial sidecars next
  to the authoritative system gamelists; Android bootstraps by importing them
  only after Syncthing has fully distributed them.

Changing either the ES-DE application data directory or the configured gamelist
root resets bootstrap authority. The default gamelist root is
`<ES-DE data>/gamelists`; users of externally scraped legacy gamelists select
their `ROMs` root instead. Before Safe Launch uses that layout, the bridge backs
up `settings/es_settings.xml` privately and atomically enables
`LegacyGamelistFileLocation` without removing other ES-DE settings.
Every layout also enforces `SaveGamelistsMode=always` before launch so metadata is
persisted while ES-DE is backgrounded for the post-play export.

## Safe Launch

The second launcher entry also declares Android's Home category and persists a session UUID, launch timestamp, whether
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

## Global Shared Collections and settings

Global state is deliberately separate from per-system game sidecars. It lives below the configured
gamelist root so it is covered by the same explicitly selected Syncthing folder:

```text
<gamelist-root>/.esde-sync-global/
  collections/<collection-name>.xcc
  settings/shared-settings.json
```

`gamelist.xml` is not stored there and remains protected by the basename ignore rule. Collection
definitions are copied to `<ES-DE data directory>/collections` only before Safe Launch. The importer
accepts one bounded XML `<filter>` document, disables DTD/XXE processing, confines filenames to both
roots, validates the filename against the `name` attribute and supports `players`, `cheevos`,
`favorites` and `ratings`. Imported names are appended to ES-DE's real comma-separated
`CollectionSystemsCustom` value; existing enabled collections are retained.

Shared ES-DE settings use a bounded, schema-versioned JSON profile rather than synchronizing
`es_settings.xml`. `EsdeSharedSettingsCatalog` defines the positive list and XML types; selection is
opt-in per key. Unknown keys, wrong JSON types, paths, credentials, controller/audio/display/device
state, update/cache/debug values and the UI mode passkey are rejected. Imports update only present,
selected keys. Missing or unselected keys leave local XML unchanged. Theme and variant values are
skipped unless their required local theme resources can be verified.

Both features use the existing single-threaded coordinator. Writes are atomic and local targets are
backed up privately below app storage in `files/esde-sync/backups/shared`. Per-item private SHA-256
snapshots distinguish one-sided changes from ambiguous first-time or concurrent edits. Ambiguous
edits are reported and are not overwritten; absence from shared state never deletes local state.
Safe Launch imports global state after the Syncthing gate and before per-game metadata and blocks on
validation errors or conflicts unless the user consciously chooses “Start without sync”. After
ES-DE returns, selected global state is published before the final Syncthing rescan.

The per-game schema remains version 1 and additionally accepts optional `players` (a bounded
single number or range such as `1-2`) and
`rating` (0.0–1.0). Older sidecars remain valid, and absent optional fields never reset local
gamelist metadata. Achievement filters contain no account state and still depend on the local
ES-DE/RetroAchievements configuration.

## Known v1 boundaries

- Syncthing sidecar conflicts are surfaced and block a green state; v1 does not
  guess how to merge simultaneous offline edits of the same game.
- Filesystem paths use the wrapper's existing all-files permission and folder
  picker because Android `FileObserver` cannot reliably watch arbitrary SAF
  providers.
- Completion is conservatively polled through the wrapper's folder status and
  primary-device completion caches. Their cache-miss paths issue REST reads;
  expensive completion queries are intentionally not fired continuously.
- Android's unused-app protection is controlled only by the user. The app links
  directly to App info but cannot silently disable the switch during install.
- Automatic ES-DE process-exit callbacks do not exist on Android. Returning to
  the still-persisted Safe Launch task is the supported post-sync trigger.
- Theme availability is verified against user-visible theme resources below the configured ES-DE
  data directory. Directory IDs, `<themeName>` display names, variant IDs, and variant labels from
  bounded, securely parsed `capabilities.xml` files are accepted. An unverified theme is skipped
  with a non-blocking warning and never prevents Safe Launch.

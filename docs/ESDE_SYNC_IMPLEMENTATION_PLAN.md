# ES-DE Gaming Sync implementation plan

## Baseline verified on 2026-08-31

This branch is based on `researchxxl/syncthing-android` commit
`d86e8c27ec55d5e714341b28ee5223d997abf1ba`. The Android wrapper is a mixed
Java/Kotlin application. `SyncthingService`, `RestApi`, and `EventProcessor` are
Java; the settings UI is Kotlin/Compose. The embedded Syncthing tree is a Git
submodule and remains untouched.

The current REST wrapper already exposes configuration, per-folder cached
status, per-device completion, connections, ignore lists, and rescans. The
event processor receives `ItemFinished`, `LocalIndexUpdated`, `FolderSummary`,
`FolderCompletion`, and `StateChanged`. The existing force-start preference is
the source of truth for temporary Safe Launch activation.

Syncthing's documented basename ignore pattern `gamelist.xml` matches that file
at every depth. Ignore updates use `GET/POST /rest/db/ignores`; the existing list
is preserved, the rule is appended only when no equivalent active rule exists.

## Files and responsibilities

New production code lives under
`app/src/main/java/com/nutomic/syncthingandroid/esdesync/`:

- `EsdeMetadata` and `EsdeGameState`: nullable v1 fields and stable
  `system + ES-DE path` identity.
- `EsdePathPolicy`: normalization and traversal rejection for untrusted JSON.
- `EsdeGamelistParser`: XXE-safe XML parsing, indexed reads, and targeted edits
  of only the six supported tags.
- `EsdeSidecarStore`: bounded JSON input, schema validation, mirrored relative
  paths, unknown-field tolerance, and atomic writes.
- `EsdeBackupManager`: private, unsynchronized five-file backup rotation.
- `EsdeSnapshotStore`: private last-export state used to avoid rewriting
  thousands of unchanged sidecars.
- `EsdeMetadataBridge`: import/export transactions and matched/unmatched/invalid
  accounting.
- `EsdeFileObserver`: per-system observers plus root discovery and per-gamelist
  debounce.
- `EsdeSyncCoordinator`: one serialized executor, feedback-loop suppression,
  bootstrap gating, observer lifecycle, remote-sidecar event ingestion, and
  diagnostics.
- `EsdeSyncStateEvaluator`: a single enum-based Safe Launch state derived from
  service, peer, folder, completion, errors, and conflicts.
- `EsdeSyncSettings`: all preferences and session persistence in one place.
- `EsdeSafeLaunchActivity`: controller-friendly pre-sync, override, app launch,
  return detection, export, post-sync, and safe-to-switch UI.

Existing integration points:

- `EventProcessor.java`: forward completed `.esde-sync/**/*.esde.json` items.
- `SyncthingService.java`: own the coordinator for exactly the active service
  lifetime and expose it to the bound UI.
- `RestApi.java`: add a per-folder rescan method while retaining `rescanAll()`.
- `settings/*`: add a Gaming route and configuration/diagnostic actions.
- `AndroidManifest.xml`: add a second launcher activity and narrow launcher-app
  visibility query.
- `Constants.java` and `strings.xml`: preference keys and user-facing copy.
- `app/build.gradle.kts`: independent application ID and JVM test dependency.
- `.github/workflows/*`: run unit tests, build/sign on every branch push, retain
  the signed APK for 30 days, and publish a useful summary.

## Data and write rules

Each game sidecar contains `schemaVersion`, exact ES-DE `game` path, nullable
metadata fields, and `updatedAt`. Missing JSON members stay `null` and therefore
never erase local values. `altemulator` is opaque text. Sidecars are placed below
`<system>/.esde-sync/` using the normalized path without the leading `./`, with
`.esde.json` appended. Absolute paths, drive-qualified paths, NULs, and `..`
segments are rejected before any filesystem access.

Both sidecars and modified XML are written to a sibling temporary file, flushed,
synced where supported, and moved over the destination. XML is parsed with a
namespace-aware DOM factory with DTD and external entity support disabled. DOM
is intentionally system-scoped: it preserves game order, attributes, unknown
elements, and all non-v1 metadata while providing simple, auditable targeted
updates. A 10,000-game performance regression test guards the chosen tradeoff.

## Threading and feedback-loop prevention

All imports and exports run on one coordinator executor. File observer events are
debounced for 900 ms per `gamelist.xml`. After import, the coordinator records the
new snapshot before releasing the transaction; a resulting observer callback
therefore finds no changed metadata and emits no sidecar. Sidecars are compared
byte-for-byte at the semantic model level and are written only for changed games.
No XML or JSON I/O runs on the UI thread.

## Bootstrap

Enabling the feature does not grant export authority. If sidecars exist, the
coordinator marks a pending import; Safe Launch first passes the full-sync gate,
then imports, saves the snapshot, marks bootstrap complete, and only then starts
observers. If none exist, the UI requires explicit "use this device
as initial metadata source" confirmation before a full initial export. Until one
of these paths completes, automatic export stays disabled.

## Safe Launch state machine

`NOT_CONFIGURED -> STARTING -> WAITING_FOR_PRIMARY -> RESCANNING -> SYNCING ->
IMPORTING_METADATA -> READY_TO_PLAY -> ESDE_RUNNING -> EXPORTING_METADATA ->
SYNCING_AFTER_PLAY -> SAFE_TO_SWITCH`.

`OFFLINE_OVERRIDE` and `ERROR` are explicit branches. READY requires an active
service, connected and unpaused primary, unpaused selected folders, idle/clean
local folder status, zero need counts and pull errors, 100% remote completion,
zero remote need bytes, and no cached conflict paths. Status is re-polled after
rescans; timeout never silently becomes READY. Session ID, launch timestamp,
previous force state, and offline/pending flags survive activity recreation.

## Tests

JVM tests cover all nullable metadata cases, Unicode/XML escaping, nested and
multi-disc paths, unknown-tag preservation, sidecar round trips and malformed
input, traversal rejection, targeted import semantics, incremental export/no-op,
feedback-loop behavior, bootstrap choices, and every Safe Launch gate. CI runs
`testDebugUnitTest`, `lintDebug`, native build, and `assembleDebug` before signing.

## Deliberate compatibility boundaries

- No code or patch is added to the Syncthing submodule.
- Disabled ES-DE sync creates no observers, sidecars, XML writes, or launch gate.
- The bridge does not merge Syncthing conflicts; it reports and blocks READY,
  while still offering the explicit offline override.
- v1 supports filesystem paths selected through the wrapper's existing folder
  picker. This is compatible with `FileObserver` and the app's existing all-files
  access model; no hard-coded storage root is used.

# Build 2.1.4.11 implementation record

The previously planned SafeSync changes are implemented in this build:

- Safe Launch uses theme-aware Material colors in dark and light mode while
  preserving the `#9C001E` brand red and accessible green status treatment.
- Offline play creates a bounded, atomic private journal. Pending changes
  survive process/device restarts, trigger a network-constrained Android job
  and notification, and cannot produce `SAFE TO SWITCH DEVICE` until every
  recorded folder has reconciled without blocking conflicts.
- The ROM/gamelist folder is assigned by stable Syncthing folder ID. The
  basename ignore rule `gamelist.xml` is applied only there; an unambiguous
  legacy `Master / Roms` assignment is migrated once.
- ROMs/saves and optional ES-DE Settings & Collections are separate folder
  roles. The latter transports only validated data below
  `.esde-sync-global/`, can be disabled per device, and never gates Safe Launch
  while disabled.
- Legacy shared state can be validated, privately backed up and copied after
  explicit confirmation. Existing destination and source files are retained.
- Missing selected Collections and locally unavailable themes are warnings,
  not launch-blocking errors. Collection activation remains locally managed.
- First Setup explains and validates the two folder roles, immediate gamelist
  saving, receiver/source behavior, Home-app behavior and the complete
  post-play procedure.
- Safe Launch closes ES-DE before final export, distinguishes `DONE` from
  `START NEW SESSION`, presents single-file and batch conflict choices, and
  labels friendly folder names separately from technical IDs.

Verification is performed by the debug and tagged release GitHub workflows
with Java 21, Android SDK 37, NDK 29.0.14206865 and Go 1.27.0.

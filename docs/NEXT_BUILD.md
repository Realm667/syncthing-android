# Next build backlog

## Self-healing stale conflict fallback (implemented for the next build 2026-09-02)

- `RETRY` revalidates cached Syncthing conflict paths off the UI thread before starting the
  regular folder rescan.
- Conflict cache entries are removed only when their conflict copies no longer exist locally.
- Real conflict copies remain blocking and retain the existing single-file and batch choices.
- Cache updates go through `LocalCompletion`; mutating the defensive copy returned by
  `getFolderStatus()` is no longer mistaken for a persistent update.
- The Safe Launch screen reports how many stale entries were removed before reevaluating the gate.

## Power off from Safe Launch Idle (implemented for the next build 2026-09-02)

- Add a `POWER OFF DEVICE` button to the Safe Launch `IDLE` state only.
- Never show or enable the action while ES-DE is running, synchronization is
  active, changes are pending, or any folder has a blocking error/conflict.
- Require a clear confirmation dialog before requesting shutdown.
- Use a supported privileged/device-specific shutdown path only after detecting
  that the required system or root permission is actually available.
- On stock Android, where third-party applications cannot power off the device,
  show an accurate actionable explanation instead of reporting false success or
  weakening SafeSync's safety gate.
- Preserve `EsdeSyncState` as the sole source of Safe Launch UI state.

### Acceptance criteria

- The button is present only after `DONE` has transitioned SafeSync to `IDLE`.
- A cancelled confirmation makes no system change.
- A permitted shutdown request happens only after the completed synchronization
  journal has been cleared and ES-DE has been confirmed closed.
- Unsupported devices remain in `IDLE` and receive a clear permission/support
  message.

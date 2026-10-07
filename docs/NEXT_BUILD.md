# Next build backlog

## Live transfer speed during synchronization implemented 2026-10-07

- Show download and upload speed next to or below the Safe Launch progress bar
  during start synchronization and final synchronization, including deferred sync.
- Refresh once per second with readable units such as KB/s and MB/s, using actual
  Syncthing traffic counter differences and elapsed time.
- Keep the lightweight speed refresh separate from adaptive synchronization-gate
  polling; do not issue heavy per-folder requests every second.
- At zero transfer speed, retain an accurate scanning, checking, or waiting status
  when available, so zero traffic is not presented as a stalled process.
- Stop refreshes when the screen is inactive, synchronization has ended, or ES-DE
  is playing. Preserve `EsdeSyncState` as the sole source of launch readiness.

### Acceptance criteria

- Speed is visible and updates every second while synchronization is active.
- No transfer rate is invented for local processing or disconnected devices.
- The display works in light and dark mode and does not change the safety gate.

The implementation shows aggregate Syncthing upload/download traffic, explicitly
labelled as such, rather than claiming to measure only the selected gaming folders.
The first sample displays a measuring status; zero traffic displays a checking or
waiting status. Unknown-duration phases use an indeterminate bar, not invented
percentages. See [optimization notes](OPTIMIZATION_2026-10-07.md) for verification
and remaining release checks.

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

# Build 2.1.4.12 implementation record

- Safe Launch `RETRY` revalidates cached conflict paths off the UI thread. Entries are removed
  only when their conflict copies no longer exist in the configured folder root.
- Invalid, absolute and traversal paths remain rejected and cannot be silently treated as solved.
- Successful single-file and batch resolution now updates the real `LocalCompletion` cache rather
  than mutating the defensive copy returned by `getFolderStatus()`.
- A conflict copy that disappears during resolution triggers the same self-healing retry path.
- The `IDLE` screen offers `POWER OFF DEVICE` after `DONE`. A second policy check requires ES-DE
  to be closed, the session to be cleared and all offline/pending changes to be reconciled.
- Shutdown requires explicit confirmation. SafeSync first tries a firmware-provided protected
  shutdown activity and then the fixed root command `reboot -p`; unsupported devices remain idle
  with an accurate explanation.

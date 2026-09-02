# Build 2.1.4.13 implementation record

- `SET UP LATER` persists an explicit First Setup deferral. Safe Launch respects that choice and
  no longer reopens the wizard in a loop while setup remains incomplete.
- Entering First Setup explicitly clears the deferral, and completing the wizard clears it as well.
- First Setup now maintains a bounded 40-second initialization refresh window. It repeatedly asks
  the already-bound Syncthing service to re-evaluate the temporary force-start lease and invalidates
  the Compose service snapshot until both the local API and ES-DE coordinator are available.
- The Syncthing startup status row is actionable and restarts the bounded refresh window on demand.
- Primary device, Gaming Sync Folders, ROM folder, optional shared-state folder, and metadata-source
  controls therefore become available during the first run without restarting the application.
- A pure policy test covers automatic opening versus an explicitly deferred or completed setup.

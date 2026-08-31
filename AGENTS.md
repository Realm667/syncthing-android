# Repository guidance for ES-DE Gaming Sync

- Never modify `syncthing/src/github.com/syncthing/syncthing`; keep the native
  submodule upstream-compatible.
- Never synchronize or merge `gamelist.xml`. It stays local and must be covered
  by the Syncthing basename ignore rule `gamelist.xml`.
- Cross-device ES-DE state is stored as one bounded `.esde.json` sidecar per game
  beneath each system's `.esde-sync` directory.
- Remote sidecars are untrusted input: validate schema and size, reject traversal,
  disable XML external entities, and confine writes to configured roots.
- Sidecar fields win only when present. Missing fields never reset local XML.
- All bridge work is serialized off the main thread. Preserve debounce, snapshot
  comparisons, atomic writes, private backups, and import feedback suppression.
- Safe Launch UI state comes only from `EsdeSyncState`; do not add parallel gate
  booleans.
- Keep ES-DE changes isolated under `com.nutomic.syncthingandroid.esdesync` and
  make disabled behavior match upstream.
- Build with Java 21, Android SDK 37, NDK 29.0.14206865, Go 1.27.0, and initialized
  submodules. Useful commands are `./gradlew testDebugUnitTest`,
  `./gradlew lintDebug`, and `./gradlew assembleDebug` (or the Windows wrapper).
- Kotlin is preferred for new ES-DE code; keep Java interop explicit with
  `@JvmStatic`/simple public methods where needed. Use the existing Gson, Compose,
  Material3, AndroidX, and Dagger dependencies.

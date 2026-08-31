# Install and test the ES-DE Sync APK

## Get the current APK from GitHub Releases

The recommended persistent download is the newest entry under
[Realm667/syncthing-android Releases](https://github.com/Realm667/syncthing-android/releases).
Choose the universal APK when available, or the APK matching the handheld's
CPU architecture. `SHA256SUMS.txt` contains the published checksums.

GitHub checks the official Syncthing releases every six hours. When it detects a
new release, it updates the submodule pointer and app version directly on
`main`, creates a unique app tag, runs the full release build, signs the APK, and
publishes it automatically. Failed publication can be retried without replacing
an existing tag or release.

These release APKs use the stable public test key and the application ID
`com.github.danielgimmer.syncthingesdesync`. They can update earlier release
APKs from this fork, but they are deliberately not trusted production or store
builds. The debug APK described below has the separate `.debug` application ID
and is not updated by a release APK.

## Get a signed APK from GitHub Actions

1. Open the fork on GitHub and select **Actions**.
2. Open the newest successful **Build App** run for the branch or pull request.
3. Under **Artifacts**, download **syncthing-esde-sync-debug-apk**.
4. Unzip it. The APK is named
   `syncthing-esde-sync-debug_<version>_<git-sha>.apk` and is retained for 30 days.

Every push, pull request, and manual run executes unit tests, Android lint, the
native Syncthing build, the Android debug build, stable public debug signing, and
artifact upload. The public debug key is deliberately untrusted and must never be
used for a production release.

## Install

On the handheld, copy the APK to local storage, allow “install unknown apps” for
the file manager, open the APK, and install it. Or use Android platform tools:

```shell
adb install -r syncthing-esde-sync-debug_<version>_<git-sha>.apk
```

The stable debug signature permits `adb install -r` upgrades. The package is
`com.github.danielgimmer.syncthingesdesync.debug`, so it does not overwrite the
official Syncthing-Fork.

## Critical parallel-install warning

Do **not** let the original app and this fork synchronize the same local folders
at the same time. Fully stop/disable the original first. This fork intentionally
uses a separate Android package. A completed configuration migration preserves
the original Syncthing device identity; skipping migration creates a new one
that must be approved separately on the QNAP.

## Migrate an existing Syncthing-Fork installation

On a fresh installation, onboarding detects the original package
`com.github.catfriend1.syncthingfork` and offers a migration page. Android does
not permit one application to read another application's private files, so the
migration deliberately uses Syncthing-Fork's existing configuration archive:

1. Open the original app from the migration page and use **Settings → Import
   and Export → Export Configuration**.
2. Remember the export password and keep the default archive path, or enter the
   same custom path later.
3. Use the migration page's app-info button to force-stop the original app.
4. Open the import screen, enter the same path and password, and import.

The archive transfers the Syncthing configuration, device certificate and
identity, configured folders and devices, index database, and Android app
preferences. ES-DE-specific preferences that do not exist in the original app
retain their safe defaults. After a successful import, onboarding detects the
restored configuration and continues without generating a new identity.

## First setup

1. Migrate and stop the original Syncthing-Fork, or skip migration and stop it.
2. Start **Syncthing-Fork ES-DE Sync**, grant requested storage/notification
   access, and add the QNAP as a Syncthing peer.
3. Connect ROM, save, save-state, and ES-DE data folders and wait for the initial
   sync to finish.
4. Open **Settings → ES-DE Gaming Sync** and enable the feature.
5. Select the ES-DE application data directory (the directory containing
   `settings/es_settings.xml`).
6. Select the gamelist root. Choose `ES-DE/gamelists` for the central ES-DE
   layout, or the `ROMs` directory when every system stores
   `ROMs/<system>/gamelist.xml`. Safe Launch enables
   `LegacyGamelistFileLocation` for the latter layout and keeps a private backup
   of the ES-DE settings file.
7. Select the installed ES-DE launcher application.
8. Select the QNAP as **Primary Gaming Sync Device**.
9. Select only folders that must block launch (typically ROMs, saves, states, and
   ES-DE data).
10. Run **Check and add gamelist.xml ignore rule**. Existing ignore lines are
   preserved. Confirm `gamelist.xml` is ignored on every participating device.
11. If synchronized sidecars already exist, let the automatic import finish. If
    none exist anywhere, choose exactly one current device and explicitly press
    **Use this device as initial metadata source**.
12. Open the separate **ES-DE Safe Launch** icon, or select it as Android's Home
    app, and wait for **SAFE TO PLAY**.
13. In Android App info, turn off **Pause app activity if unused**. Android keeps
    this user-controlled and does not let the APK disable it during installation.

## Safe first test

Use copies/backups and only three SNES games: Chrono Trigger, Super Mario World,
and Zelda. On device A, fully sync, Safe Launch, mark Chrono Trigger favorite,
play briefly, exit ES-DE, and wait for **SAFE TO SWITCH DEVICE**. Verify
`.esde-sync/Chrono Trigger.sfc.esde.json` exists.

On device B, use Safe Launch and verify favorite, play count, play time, last
played, and alternate emulator match. Also change Zelda on A and Chrono Trigger
on B in sequence: they must update independent sidecars and never create a
`gamelist.sync-conflict-*` file.

When the QNAP is unavailable, use **Start without sync** only deliberately. The
app keeps local changes pending; fully synchronize this handheld before playing
on another one.

## Local build

Prerequisites match the checked-in catalog: Java 21, Android SDK 37, Android NDK
29.0.14206865, Go 1.27.0, Python 3.11+, and Git submodules.

```shell
git clone --recurse-submodules <your-fork-url>
cd syncthing-android
./gradlew testDebugUnitTest lintDebug buildNative assembleDebug
```

On Windows use `gradlew.bat` with the same tasks. Upstream deliberately makes the
debug variant unsigned; GitHub Actions applies the stable public test signature.

## Rollback

Disable **ES-DE Gaming Sync**. Observers stop and normal Syncthing operation is
independent of the bridge. Local gamelists and private backups remain. Sidecars
may remain in synchronized folders and are ignored by the disabled integration;
nothing is deleted automatically. Re-enable the original app only after this
fork is fully stopped.

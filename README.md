# Syncthing-Fork - A Syncthing Wrapper for Android

[![License: MPLv2](https://img.shields.io/badge/License-MPLv2-blue.svg)](https://opensource.org/licenses/MPL-2.0)
<a href="https://github.com/Realm667/syncthing-android/releases/latest" alt="GitHub release"><img src="https://img.shields.io/github/v/release/Realm667/syncthing-android" /></a>
<a href="https://tooomm.github.io/github-release-stats/?username=Realm667&repository=syncthing-android" alt="GitHub Stats"><img src="https://img.shields.io/github/downloads/Realm667/syncthing-android/total.svg" /></a>
<a href="https://f-droid.org/packages/com.github.catfriend1.syncthingfork" alt="F-Droid release"><img src="https://img.shields.io/f-droid/v/com.github.catfriend1.syncthingfork.svg" /></a>
<a href="https://fdroid-metrics.streamlit.app/package_details?package=com.github.catfriend1.syncthingfork"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Fmonthly%2Fcom.github.catfriend1.syncthingfork.json&query=%24.total_downloads&style=for-the-badge&logo=fdroid&label=F-Droid%20%F0%9F%93%A5%20last%20month" height="22" /></a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.github.catfriend1.syncthingfork%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fresearchxxl%2Fsyncthing-android%22%2C%22author%22%3A%22researchxxl%22%2C%22name%22%3A%22Syncthing-Fork%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22verifyLatestTag%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22com.github.catfriend1.syncthingfork%5C%22%7D%22%2C%22overrideSource%22%3Anull%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="22"></a>
<a href="https://hosted.weblate.org/projects/syncthing-fork/app/"><img src="https://hosted.weblate.org/widget/syncthing-fork/app/svg-badge.svg" alt="Translation status" /></a>
[![Build App](https://github.com/Realm667/syncthing-android/actions/workflows/build-app.yaml/badge.svg)](https://github.com/Realm667/syncthing-android/actions/workflows/build-app.yaml)

A wrapper of [Syncthing](https://github.com/syncthing/syncthing) for Android. Head to the "releases" section or F-Droid for builds. Please seek help on the forum and/or social media apps first before creating issues on the tracker.

<img src="app/src/main/play/listings/en-US/graphics/phone-screenshots/1.png" alt="screenshot 1" width="200" /> · <img src="app/src/main/play/listings/en-US/graphics/phone-screenshots/2.png" alt="screenshot 2" width="200" /> · <img src="app/src/main/play/listings/en-US/graphics/phone-screenshots/4.png" alt="screenshot 3" width="200" />

## Switching from the deprecated official version

Switching is easier than you may think! See our [wiki article](wiki/migration/Switching-from-the-deprecated-official-version.md) for detailed instructions.

## Wiki and Useful Articles

Our knowledge base is published [here](wiki#readme).

## Building and Development Notes

See [detailed info](wiki/developers/Building-and-Development.md).

## ES-DE Gaming Sync

This fork adds an opt-in metadata bridge for Android retro-gaming handhelds.
ES-DE normally stores every game's mutable state for a system in one
`gamelist.xml`; synchronizing that file from multiple handhelds creates coarse
file conflicts. The bridge keeps every `gamelist.xml` local and ignored, then
synchronizes one small `.esde.json` sidecar per game instead.

Both current ES-DE layouts are supported: the central
`ES-DE/gamelists/<system>/gamelist.xml` tree and the legacy/portable
`ROMs/<system>/gamelist.xml` layout used by external scrapers. Sidecars always
live below the same system directory in `.esde-sync`. For the ROM layout, Safe
Launch verifies and safely enables `LegacyGamelistFileLocation` in ES-DE's
`settings/es_settings.xml` before starting ES-DE.

Version 1 synchronizes `favorite`, `completed`, `playcount`, `playtime`,
`lastplayed`, and the exact opaque `altemulator` value. Missing sidecar fields do
not erase local values. ES-DE Safe Launch checks the selected Syncthing folders
and primary device before play, imports metadata, starts the chosen ES-DE app,
then exports and synchronizes changes after return. When the primary device is
unavailable, **Start without sync** remains available and marks local changes as
pending until a later successful synchronization.

Safe Launch declares Android's Home category, so it can be selected as the Home
app on handhelds that use ES-DE as their launcher. Some Android builds display
the package label **Syncthing-Fork ES-DE Sync** in the Home picker and collapse
the second launcher icon; the Gaming Sync settings therefore include direct
buttons to open Safe Launch and the system Home-app picker. Android does not allow an app
to disable its own “Pause app activity if unused” protection silently; the
Gaming Sync settings provide a direct link to App info for that user-controlled
switch.

Read [the architecture](docs/ESDE_SYNC_ARCHITECTURE.md), [implementation plan](docs/ESDE_SYNC_IMPLEMENTATION_PLAN.md),
and [test APK installation guide](docs/INSTALL_TEST_APK.md) before enabling it.

The fork checks the official Syncthing releases every six hours. A newly
published version is committed directly to `main`, assigned a unique app tag,
built, test-signed, and published with SHA-256 checksums under
[GitHub Releases](https://github.com/Realm667/syncthing-android/releases). The
public test signature is stable for upgrades but is not a trusted production or
store signature.

When the original Syncthing-Fork is installed beside a fresh ES-DE Sync
installation, onboarding offers a guided configuration migration. It uses the
original app's encrypted export/import archive because Android prevents direct
access to another package's private data, and preserves the Syncthing identity,
folders, devices, index, and compatible app settings.

> **Do not run this fork and the original Syncthing-Fork against the same local
> folders at the same time.** Stop or disable the original app first. This fork
> has its own application ID. A completed migration preserves the Syncthing
> identity; skipping it creates a new device that must be authorized on your NAS.

## Acknowledgments

This project was forked from [syncthing/syncthing-android](https://github.com/syncthing/syncthing-android).

Special thanks to the former maintainers:

- [Catfriend1](https://github.com/Catfriend1)
- [imsodin](https://github.com/imsodin)
- [nutomic](https://github.com/nutomic)

## Privacy Policy

See our document on privacy: [privacy-policy.md](privacy-policy.md).

## License

The project is licensed under [MPLv2](LICENSE).

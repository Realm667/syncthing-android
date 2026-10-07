# Private signing migration and activation gate

The release workflow supports a private key with a signing lineage from the
existing public test key. It is deliberately **not activated** until the key is
backed up and an in-place update has been tested. No production key is generated
or published by a build; a fresh key on every build would break future updates.

## One-time preparation

1. Create an offline, encrypted release keystore with alias `Syncthing-Fork`.
   Keep two independent encrypted backups of the keystore and password.
2. Recover the current public `androiddebugkey` from `common-sign.yaml` and
   verify its certificate against the APK actually installed on the handhelds.
3. Using SDK Build Tools 36 or later, create and preserve the signing lineage:

   ```sh
   apksigner rotate --out safesync.lineage \
     --old-signer --ks legacy.jks --ks-key-alias androiddebugkey --ks-pass pass:android \
     --new-signer --ks private.jks --ks-key-alias Syncthing-Fork --ks-pass env:SIGNING_PASSWORD
   ```

4. In the protected GitHub environment `release-signing`, configure secrets
   `SIGNING_KEYSTORE_JKS_BASE64`, `SIGNING_PASSWORD`,
   `SIGNING_LEGACY_KEYSTORE_BASE64`, and `SIGNING_LINEAGE_BASE64`.
   Configure environment variable `SIGNING_CERT_SHA256` to the SHA-256 of the new
   DER certificate, in hexadecimal without colons. Require environment approval.
5. Build/sign a candidate, verify its signatures, then install it **over** the
   existing release on Android 9–12 and Android 13+. Verify app data, device ID,
   pending journal and a second in-place update. Keep the old APK and backups.
6. Only after this succeeds, set repository variable
   `SAFESYNC_PRIVATE_SIGNING=true`. A missing secret or mismatched certificate
   then fails signing; there is no automatic fallback to public signing.

The workflow uses the old signer for pre-Android-9 compatibility and the new
signer from API 28 (`--rotation-min-sdk-version 28`). Legacy Android therefore
continues to use a publicly known key: this is not a security fix for old OSes.
Do not toggle back to public signing after migration. Do not assume that a valid
APK signature alone proves an installed device accepts the update.

Reference: [Android apksigner documentation](https://developer.android.com/tools/apksigner)
and [AOSP signer/lineage semantics](https://android.googlesource.com/platform/tools/apksig/+/master/src/apksigner/java/com/android/apksigner/help_sign.txt).

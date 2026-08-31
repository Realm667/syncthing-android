package com.nutomic.syncthingandroid.esdesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EsdeOnlineUpdatePolicyTest {
    @Test fun selectsNewestPublishedReleaseWithExactUniversalApk() {
        val releases = listOf(
            release("v2.1.4.9"),
            release("v2.1.5.0", draft = true),
            release("v2.1.4.10", prerelease = true),
            release("v2.1.4.11", universal = false),
        )

        val selected = EsdeOnlineUpdatePolicy.selectRelease(releases, "2.1.4.8")

        assertEquals("2.1.4.10", selected?.version)
        assertEquals("v2.1.4.10", selected?.tag)
    }

    @Test fun currentOrMalformedReleasesDoNotOfferAnUpdate() {
        assertNull(EsdeOnlineUpdatePolicy.selectRelease(listOf(release("v2.1.4.8")), "2.1.4.8"))
        assertNull(EsdeOnlineUpdatePolicy.selectRelease(listOf(release("latest")), "2.1.4.8"))
        assertNull(EsdeOnlineUpdatePolicy.selectRelease(listOf(release("v2.1.4.9")), "invalid"))
    }

    @Test fun parsesOnlyWellFormedSha256Entries() {
        val first = "a".repeat(64)
        val second = "B".repeat(64)
        val checksums = EsdeOnlineUpdatePolicy.parseChecksums(
            "$first  ./one.apk\n$second *two.apk\ninvalid  three.apk\n",
        )

        assertEquals(mapOf("one.apk" to first, "two.apk" to second.lowercase()), checksums)
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        universal: Boolean = true,
    ): GitHubRelease {
        val version = tag.removePrefix("v")
        val universalName = "com.github.danielgimmer.syncthingesdesync_release_v$version.apk"
        val apkName = if (universal) universalName else universalName.removeSuffix(".apk") + "_arm64-v8a.apk"
        return GitHubRelease(
            tagName = tag,
            htmlUrl = "https://github.com/Realm667/syncthing-android/releases/tag/$tag",
            draft = draft,
            prerelease = prerelease,
            assets = listOf(
                GitHubAsset(apkName, "https://github.com/Realm667/syncthing-android/releases/download/$tag/$apkName", 10),
                GitHubAsset("SHA256SUMS.txt", "https://github.com/Realm667/syncthing-android/releases/download/$tag/SHA256SUMS.txt", 100),
            ),
        )
    }
}

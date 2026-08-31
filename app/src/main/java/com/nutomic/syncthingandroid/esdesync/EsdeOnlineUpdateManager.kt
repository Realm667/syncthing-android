package com.nutomic.syncthingandroid.esdesync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class EsdeOnlineRelease(
    val version: String,
    val tag: String,
    val pageUrl: String,
    val apk: EsdeReleaseAsset,
    val checksums: EsdeReleaseAsset,
)

internal data class EsdeReleaseAsset(val name: String, val url: String, val size: Long)

internal sealed interface EsdeUpdateCheckResult {
    data class Available(val release: EsdeOnlineRelease) : EsdeUpdateCheckResult
    data object Current : EsdeUpdateCheckResult
}

internal enum class EsdeInstallLaunch { STARTED, PERMISSION_REQUIRED }

internal class EsdeOnlineUpdateManager(
    private val cacheRoot: File,
    private val gson: Gson = Gson(),
) {
    fun check(currentVersion: String): EsdeUpdateCheckResult {
        require(EsdeOnlineUpdatePolicy.parseVersion(currentVersion) != null) { "Invalid installed app version" }
        val json = getText(RELEASES_URL, MAX_RELEASE_JSON_BYTES)
        val releases = gson.fromJson(json, Array<GitHubRelease>::class.java).orEmpty().toList()
        val release = EsdeOnlineUpdatePolicy.selectRelease(releases, currentVersion)
        return release?.let(EsdeUpdateCheckResult::Available) ?: EsdeUpdateCheckResult.Current
    }

    fun downloadVerified(release: EsdeOnlineRelease): File {
        require(release.apk.size in 1..MAX_APK_BYTES) { "Release APK has an invalid size" }
        require(release.checksums.size in 1..MAX_CHECKSUM_BYTES) { "Checksum file has an invalid size" }
        val checksumText = getText(release.checksums.url, MAX_CHECKSUM_BYTES)
        val expected = EsdeOnlineUpdatePolicy.parseChecksums(checksumText)[release.apk.name]
            ?: throw IllegalArgumentException("Release checksum is missing")

        val directory = File(cacheRoot, "online-updates")
        require(directory.exists() || directory.mkdirs()) { "Cannot create update cache" }
        val target = File(directory, release.apk.name)
        val partial = File(directory, "${release.apk.name}.part")
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val connection = openFollowingRedirects(release.apk.url)
        try {
            require(connection.responseCode in 200..299) { "APK download failed (${connection.responseCode})" }
            val declared = contentLength(connection)
            require(declared <= 0 || declared <= MAX_APK_BYTES) { "Release APK is too large" }
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_APK_BYTES) { "Release APK exceeded the size limit" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        require(total == release.apk.size) { "Downloaded APK size does not match GitHub" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        try {
            require(actual.equals(expected, ignoreCase = true)) { "Downloaded APK checksum does not match" }
            if (target.exists()) require(target.delete()) { "Cannot replace cached update" }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                require(partial.delete()) { "Cannot clean up partial update" }
            }
        } catch (error: Exception) {
            partial.delete()
            target.delete()
            throw error
        }
        return target
    }

    fun launchInstaller(context: Context, apk: File): EsdeInstallLaunch {
        val updateDirectory = File(cacheRoot, "online-updates").canonicalFile
        val installApk = apk.canonicalFile
        require(installApk.isFile && installApk.parentFile == updateDirectory) {
            "Invalid update APK path"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(permissionIntent)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return EsdeInstallLaunch.PERMISSION_REQUIRED
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", installApk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return EsdeInstallLaunch.STARTED
    }

    private fun getText(url: String, maxBytes: Long): String {
        val connection = openFollowingRedirects(url)
        try {
            require(connection.responseCode in 200..299) { "GitHub request failed (${connection.responseCode})" }
            val declared = contentLength(connection)
            require(declared <= 0 || declared <= maxBytes) { "GitHub response is too large" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size().toLong() + read <= maxBytes) { "GitHub response exceeded the size limit" }
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            require(current.protocol.equals("https", ignoreCase = true) && isAllowedHost(current.host)) {
                "Untrusted update URL"
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
                ?: throw IllegalStateException("GitHub redirect has no destination")
            connection.disconnect()
            require(redirect < MAX_REDIRECTS) { "Too many GitHub redirects" }
            current = URL(current, location)
        }
        error("Too many GitHub redirects")
    }

    private fun contentLength(connection: HttpURLConnection): Long =
        connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

    private fun isAllowedHost(host: String): Boolean = host.equals("api.github.com", true) ||
        host.equals("github.com", true) || host.endsWith(".githubusercontent.com", true)

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Realm667/syncthing-android/releases?per_page=20"
        private const val USER_AGENT = "Syncthing-ESDE-SafeSync-Updater"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val NETWORK_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_RELEASE_JSON_BYTES = 2L * 1024L * 1024L
        private const val MAX_CHECKSUM_BYTES = 64L * 1024L
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal object EsdeOnlineUpdatePolicy {
    private val VERSION = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$")
    private val CHECKSUM = Regex("^([0-9A-Fa-f]{64})\\s+\\*?\\.?/?([^/].*)$")
    private const val RELEASE_APPLICATION_ID = "com.github.danielgimmer.syncthingesdesync"

    fun parseVersion(value: String): List<Int>? = VERSION.matchEntire(value.trim())
        ?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 4 }

    fun selectRelease(releases: List<GitHubRelease>, currentVersion: String): EsdeOnlineRelease? {
        val current = parseVersion(currentVersion) ?: return null
        return releases.asSequence()
            .filterNot(GitHubRelease::draft)
            .mapNotNull { release ->
                val version = parseVersion(release.tagName) ?: return@mapNotNull null
                val versionText = version.joinToString(".")
                val apkName = "${RELEASE_APPLICATION_ID}_release_v$versionText.apk"
                val apk = release.assets.singleOrNull { it.name == apkName } ?: return@mapNotNull null
                val checksums = release.assets.singleOrNull { it.name == "SHA256SUMS.txt" } ?: return@mapNotNull null
                version to EsdeOnlineRelease(
                    version = versionText,
                    tag = release.tagName,
                    pageUrl = release.htmlUrl,
                    apk = EsdeReleaseAsset(apk.name, apk.downloadUrl, apk.size),
                    checksums = EsdeReleaseAsset(checksums.name, checksums.downloadUrl, checksums.size),
                )
            }
            .filter { (version) -> compareVersions(version, current) > 0 }
            .maxWithOrNull { left, right -> compareVersions(left.first, right.first) }
            ?.second
    }

    fun parseChecksums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val match = CHECKSUM.matchEntire(line.trim()) ?: return@mapNotNull null
        match.groupValues[2].substringAfterLast('/') to match.groupValues[1].lowercase()
    }.toMap()

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        left.zip(right).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        return left.size.compareTo(right.size)
    }
}

internal data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

internal data class GitHubAsset(
    val name: String = "",
    @SerializedName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

package com.nutomic.syncthingandroid.esdesync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Package-level migration helpers.
 *
 * Android intentionally prevents this app from reading another package's private files. Migration
 * therefore uses Syncthing-Fork's existing encrypted configuration export and import mechanism.
 */
object LegacySyncthingMigration {
    const val LEGACY_PACKAGE_NAME = "com.github.catfriend1.syncthingfork"

    @JvmStatic
    fun shouldOffer(hasLocalConfig: Boolean, legacyPackageInstalled: Boolean): Boolean =
        !hasLocalConfig && legacyPackageInstalled

    @JvmStatic
    @Suppress("DEPRECATION")
    fun isLegacyPackageInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(LEGACY_PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @JvmStatic
    fun launchLegacyApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(LEGACY_PACKAGE_NAME)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    @JvmStatic
    fun openLegacyAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", LEGACY_PACKAGE_NAME, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

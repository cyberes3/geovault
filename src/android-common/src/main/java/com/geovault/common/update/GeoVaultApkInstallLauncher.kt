package com.geovault.common.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Launches the system package installer for an APK file served via this library's [FileProvider].
 */
object GeoVaultApkInstallLauncher {
    private const val FILE_PROVIDER_SUFFIX = "geovault.updatefileprovider"

    fun providerAuthority(packageName: String): String = "$packageName.$FILE_PROVIDER_SUFFIX"

    /**
     * Ensures [apkFile] parses as this app's package and has a higher [android.content.pm.PackageInfo.longVersionCode]
     * than the running install. Otherwise Play Store / package installer returns **App not installed** with
     * `INSTALL_FAILED_VERSION_DOWNGRADE` or a signature / package mismatch, which is opaque to users.
     *
     * On failure, [Result.failure] carries an [IllegalStateException] with message
     * `version_downgrade`, `apk_package_mismatch`, `apk_signing_mismatch`, or
     * `apk_parse_failed` for localized UI mapping.
     */
    fun verifyDownloadedApkCanReplaceCurrentInstall(context: Context, apkFile: File): Result<Unit> {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val signingFlags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
        )
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, signingFlags)
            ?: run {
                Log.w(UpdateCheckLog.TAG, "getPackageArchiveInfo returned null for ${apkFile.path}")
                return Result.failure(IllegalStateException("apk_parse_failed"))
            }
        val appInfo = archiveInfo.applicationInfo
            ?: return Result.failure(IllegalStateException("apk_parse_failed"))
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath

        if (archiveInfo.packageName != appContext.packageName) {
            Log.w(
                UpdateCheckLog.TAG,
                "APK packageName=${archiveInfo.packageName} does not match app ${appContext.packageName}",
            )
            return Result.failure(IllegalStateException("apk_package_mismatch"))
        }

        val installedInfo = pm.getPackageInfo(appContext.packageName, signingFlags)
        val apkVc = archiveInfo.longVersionCode
        val installedVc = installedInfo.longVersionCode
        if (apkVc <= installedVc) {
            Log.w(
                UpdateCheckLog.TAG,
                "APK versionCode $apkVc is not greater than installed $installedVc; blocking install",
            )
            return Result.failure(IllegalStateException("version_downgrade"))
        }

        val apkSigning = archiveInfo.signingInfo
        val installedSigning = installedInfo.signingInfo
        if (apkSigning == null || installedSigning == null ||
            !ApkSigningCertificates.match(
                ApkSigningCertificates.currentSignerCerts(apkSigning),
                ApkSigningCertificates.lineageCerts(installedSigning),
            )
        ) {
            Log.w(
                UpdateCheckLog.TAG,
                "APK signing certificates do not match the installed app; blocking install",
            )
            return Result.failure(IllegalStateException("apk_signing_mismatch"))
        }
        return Result.success(Unit)
    }

    fun launchInstall(context: Context, apkFile: File): Result<Unit> {
        val appContext = context.applicationContext
        val authority = providerAuthority(appContext.packageName)
        val uri = try {
            FileProvider.getUriForFile(appContext, authority, apkFile)
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "FileProvider.getUriForFile failed: ${e.message}")
            return Result.failure(e)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pm = appContext.packageManager
        if (intent.resolveActivity(pm) == null) {
            return Result.failure(IllegalStateException("no_install_handler"))
        }
        return try {
            appContext.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(UpdateCheckLog.TAG, "startActivity for APK install failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Opens the per-app **Install unknown apps** screen ([Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES])
     * for this package so the user can allow APK installs from this app.
     */
    fun openInstallFromUnknownSourcesSettings(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        context.applicationContext.packageManager.canRequestPackageInstalls()
}

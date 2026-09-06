package com.geovault.common.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.geovault.common.update.ApkDownloadState
import com.geovault.common.update.ApkReleaseDownloader
import com.geovault.common.update.ApkUpdateFailureMessages
import com.geovault.common.update.ApkUpdateSession
import com.geovault.common.update.GeoVaultApkInstallLauncher
import com.geovault.common.update.GeoVaultApkUpdateDownloadCache
import com.geovault.common.update.VersionCheckResult
import kotlinx.coroutines.flow.StateFlow

class GeoVaultAppUpdateViewModel(
    application: Application,
    update: VersionCheckResult.UpdateAvailable,
) : AndroidViewModel(application) {

    private val session = ApkUpdateSession(
        scope = viewModelScope,
        downloadUrl = update.apkDownloadUrl,
        knownTotalBytes = update.apkSizeBytes,
        destination = GeoVaultApkUpdateDownloadCache.destinationFile(application, update),
        download = { url, knownTotalBytes, destination, onProgress ->
            ApkReleaseDownloader().download(
                url = url,
                knownTotalBytes = knownTotalBytes,
                destination = destination,
                onProgress = onProgress,
            )
        },
        verifyApk = { file ->
            GeoVaultApkInstallLauncher.verifyDownloadedApkCanReplaceCurrentInstall(application, file)
        },
        launchInstall = { file ->
            GeoVaultApkInstallLauncher.launchInstall(application, file)
        },
        classifyFailure = { error -> ApkUpdateFailureMessages.classify(application, error) },
        canRequestPackageInstalls = {
            GeoVaultApkInstallLauncher.canRequestPackageInstalls(application)
        },
        openInstallSettings = {
            GeoVaultApkInstallLauncher.openInstallFromUnknownSourcesSettings(application)
        },
    )

    val downloadState: StateFlow<ApkDownloadState> = session.state

    fun onInstallClick() {
        session.onInstallClick()
    }

    fun cancelActiveDownload() {
        session.cancelActiveDownload()
    }

    fun onDismiss() {
        session.onDismiss()
    }

    fun onHostResumed() {
        session.onHostResumed()
    }

    override fun onCleared() {
        session.onCleared()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val update: VersionCheckResult.UpdateAvailable,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            if (!modelClass.isAssignableFrom(GeoVaultAppUpdateViewModel::class.java)) {
                error("Unknown ViewModel class ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return GeoVaultAppUpdateViewModel(application, update) as T
        }
    }
}

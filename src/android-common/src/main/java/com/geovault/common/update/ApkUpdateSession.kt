package com.geovault.common.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ApkUpdateSession(
    private val scope: CoroutineScope,
    private val downloadUrl: String,
    private val knownTotalBytes: Long?,
    private val destination: File,
    private val download: suspend (
        url: String,
        knownTotalBytes: Long?,
        destination: File,
        onProgress: (ApkDownloadProgress) -> Unit,
    ) -> Result<Unit>,
    private val verifyApk: (File) -> Result<Unit>,
    private val launchInstall: (File) -> Result<Unit>,
    private val classifyFailure: (Throwable) -> String,
    private val canRequestPackageInstalls: () -> Boolean,
    private val openInstallSettings: () -> Unit,
) {

    private val _state = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle())
    val state: StateFlow<ApkDownloadState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var awaitingInstallPermissionResume: Boolean = false

    fun onCleared() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun cancelActiveDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (destination.exists()) {
            destination.delete()
        }
        _state.value = ApkDownloadState.Idle(installPermissionDenied = currentPermissionDenied())
    }

    fun onDismiss() {
        awaitingInstallPermissionResume = false
        val phase = _state.value
        if (phase is ApkDownloadState.Downloading || phase is ApkDownloadState.Connecting) {
            cancelActiveDownload()
        }
        _state.value = ApkDownloadState.Idle()
    }

    fun onInstallClick() {
        when (_state.value) {
            is ApkDownloadState.Downloading,
            is ApkDownloadState.Connecting,
            is ApkDownloadState.OpeningInstaller,
            -> return
            else -> Unit
        }
        if (!canRequestPackageInstalls()) {
            awaitingInstallPermissionResume = true
            _state.value = ApkDownloadState.Idle()
            openInstallSettings()
            return
        }
        startDownloadThenInstall()
    }

    fun onHostResumed() {
        if (!awaitingInstallPermissionResume) return
        awaitingInstallPermissionResume = false
        if (!canRequestPackageInstalls()) {
            _state.value = ApkDownloadState.Idle(installPermissionDenied = true)
        } else {
            startDownloadThenInstall()
        }
    }

    private fun startDownloadThenInstall() {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.value = ApkDownloadState.Connecting
            val result = download(
                downloadUrl,
                knownTotalBytes,
                destination,
            ) { progress ->
                _state.value = ApkDownloadState.Downloading(progress)
            }
            if (!result.isSuccess) {
                val err = result.exceptionOrNull() ?: Exception("unknown")
                if (err is CancellationException) {
                    _state.value = ApkDownloadState.Idle()
                    return@launch
                }
                _state.value = ApkDownloadState.Failed(classifyFailure(err))
                return@launch
            }
            _state.value = ApkDownloadState.OpeningInstaller
            val verifyResult = verifyApk(destination)
            if (verifyResult.isFailure) {
                _state.value = ApkDownloadState.Failed(classifyFailure(verifyResult.exceptionOrNull()!!))
                return@launch
            }
            val installResult = launchInstall(destination)
            if (installResult.isFailure) {
                _state.value = ApkDownloadState.Failed(classifyFailure(installResult.exceptionOrNull()!!))
            } else {
                _state.value = ApkDownloadState.InstallLaunched
            }
        }
    }

    private fun currentPermissionDenied(): Boolean = _state.value.showsInstallPermissionDenied
}

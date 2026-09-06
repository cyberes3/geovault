package com.geovault.common.update

sealed interface ApkDownloadState {
    val showsDownloadProgress: Boolean
        get() = this is Connecting || this is Downloading || this is OpeningInstaller

    val installEnabled: Boolean
        get() = this is Idle || this is Failed

    val showsInstallPermissionDenied: Boolean
        get() = when (val state = this) {
            is Idle -> state.installPermissionDenied
            is Failed -> state.installPermissionDenied
            else -> false
        }

    data class Idle(
        val installPermissionDenied: Boolean = false,
    ) : ApkDownloadState

    data object Connecting : ApkDownloadState

    data class Downloading(
        val progress: ApkDownloadProgress,
    ) : ApkDownloadState

    data object OpeningInstaller : ApkDownloadState

    data class Failed(
        val message: String,
        val installPermissionDenied: Boolean = false,
    ) : ApkDownloadState

    data object InstallLaunched : ApkDownloadState
}

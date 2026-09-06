package com.geovault.common.ui.update

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.R
import com.geovault.common.ui.components.GeoVaultInfoDialog
import com.geovault.common.ui.components.GeoVaultPrimaryButton
import com.geovault.common.update.ApkDownloadState
import com.geovault.common.update.UpdateDownloadProgressMath
import com.geovault.common.update.VersionCheckResult
import com.geovault.common.util.GeoVaultFileSizeFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun GeoVaultAppUpdateAvailableDialog(
    update: VersionCheckResult.UpdateAvailable,
    onDismissRequest: () -> Unit,
    onOpenReleaseInBrowser: (String) -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: GeoVaultAppUpdateViewModel = viewModel(
        key = "apk-update-${update.releaseCommitSha}",
        factory = GeoVaultAppUpdateViewModel.Factory(application, update),
    )
    val downloadState by viewModel.downloadState.collectAsState()
    GeoVaultAppUpdateAvailableDialogContent(
        update = update,
        downloadState = downloadState,
        onInstallClick = viewModel::onInstallClick,
        onCancelDownload = viewModel::cancelActiveDownload,
        onDismissSession = viewModel::onDismiss,
        onHostResumed = viewModel::onHostResumed,
        onDismissRequest = onDismissRequest,
        onOpenReleaseInBrowser = onOpenReleaseInBrowser,
    )
}

@Composable
internal fun GeoVaultAppUpdateAvailableDialogContent(
    update: VersionCheckResult.UpdateAvailable,
    downloadState: ApkDownloadState,
    onInstallClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissSession: () -> Unit,
    onHostResumed: () -> Unit,
    onDismissRequest: () -> Unit,
    onOpenReleaseInBrowser: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val onDismissUpdated by rememberUpdatedState(onDismissRequest)
    val releaseUrlState by rememberUpdatedState(update.releaseUrl)
    val onHostResumedState by rememberUpdatedState(onHostResumed)

    LaunchedEffect(downloadState) {
        if (downloadState is ApkDownloadState.InstallLaunched) {
            onDismissSession()
            onDismissUpdated()
        }
    }

    DisposableEffect(lifecycleOwner, update.releaseCommitSha) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onHostResumedState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val title = stringResource(R.string.gv_update_dialog_title)
    val publishedLabel = stringResource(R.string.gv_update_label_published)
    val nameLabel = stringResource(R.string.gv_update_label_release_name)
    val unknownPublished = stringResource(R.string.gv_update_published_unknown)
    val publishedValue = formatPublishedAt(update.releasePublishedAtIso, unknownPublished)
    val releaseDisplayName = displayReleaseName(update)
    val indeterminateA11y = stringResource(R.string.gv_update_progress_a11y_indeterminate)
    val installLabel = stringResource(R.string.gv_update_install)
    val viewLabel = stringResource(R.string.gv_update_view)

    GeoVaultInfoDialog(
        title = title,
        onDismissRequest = {
            onDismissSession()
            onDismissUpdated()
        },
        closeButtonText = stringResource(R.string.gv_update_close),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$publishedLabel: $publishedValue",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$nameLabel: $releaseDisplayName",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (!downloadState.showsDownloadProgress) {
                UpdateDetailsFooter(
                    update = update,
                    downloadState = downloadState,
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GeoVaultPrimaryButton(
                    text = installLabel,
                    onClick = onInstallClick,
                    enabled = downloadState.installEnabled,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Outlined.GetApp,
                    leadingIconContentDescription = installLabel,
                )
                GeoVaultPrimaryButton(
                    text = viewLabel,
                    onClick = { onOpenReleaseInBrowser(releaseUrlState) },
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    leadingIconContentDescription = viewLabel,
                )
            }
            if (downloadState.showsInstallPermissionDenied) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.gv_update_install_permission_still_denied),
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colors.error,
                )
            }
            when (val state = downloadState) {
                is ApkDownloadState.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colors.error,
                    )
                }
                else -> Unit
            }
        }
    }

    if (downloadState.showsDownloadProgress) {
        val progressCloseLabel = when (downloadState) {
            ApkDownloadState.OpeningInstaller -> stringResource(R.string.gv_update_close)
            else -> stringResource(R.string.gv_update_cancel_download)
        }
        GeoVaultInfoDialog(
            title = stringResource(R.string.gv_update_download_dialog_title),
            onDismissRequest = onCancelDownload,
            closeButtonText = progressCloseLabel,
        ) {
            UpdateDownloadProgressContent(
                downloadState = downloadState,
                indeterminateA11y = indeterminateA11y,
            )
        }
    }
}

@Composable
private fun apkSizeHintText(update: VersionCheckResult.UpdateAvailable): String {
    return when {
        update.apkSizeBytes != null && update.apkSizeBytes > 0L ->
            stringResource(
                R.string.gv_update_size_about,
                GeoVaultFileSizeFormat.humanBytes(update.apkSizeBytes),
            )
        else -> stringResource(R.string.gv_update_size_unknown_hint)
    }
}

@Composable
private fun UpdateDetailsFooter(
    update: VersionCheckResult.UpdateAvailable,
    downloadState: ApkDownloadState,
) {
    val sizeHint = apkSizeHintText(update)
    Column(modifier = Modifier.fillMaxWidth()) {
        when (downloadState) {
            is ApkDownloadState.Idle,
            is ApkDownloadState.Failed,
            -> {
                Text(
                    text = sizeHint,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface,
                )
            }
            else -> Unit
        }
    }
}

private data class DownloadProgressUi(
    val headline: String,
    /** `null` = indeterminate bar; otherwise 0f–1f. */
    val progressFraction: Float?,
    val progressA11y: String,
    val percentText: String,
    val percentDimmed: Boolean,
    val speedText: String,
    val speedDimmed: Boolean,
    val amountCentered: String,
    val amountDimmed: Boolean,
    val etaCentered: String,
    val etaDimmed: Boolean,
)

@Composable
private fun downloadProgressUi(
    downloadState: ApkDownloadState,
    indeterminateA11y: String,
): DownloadProgressUi {
    val dash = stringResource(R.string.gv_update_progress_em_dash)
    val zero = GeoVaultFileSizeFormat.humanBytes(0L)
    val estimating = stringResource(R.string.gv_update_progress_estimating_time)
    return when (downloadState) {
        ApkDownloadState.Connecting -> DownloadProgressUi(
            headline = stringResource(R.string.gv_update_status_connecting),
            progressFraction = null,
            progressA11y = indeterminateA11y,
            percentText = stringResource(R.string.gv_update_progress_percent, 0),
            percentDimmed = true,
            speedText = stringResource(R.string.gv_update_progress_speed, zero),
            speedDimmed = true,
            amountCentered = stringResource(R.string.gv_update_progress_downloaded_of_total, zero, dash),
            amountDimmed = true,
            etaCentered = estimating,
            etaDimmed = true,
        )
        is ApkDownloadState.Downloading -> {
            val p = downloadState.progress
            val total = p.totalBytes
            if (total != null && total > 0L) {
                val frac = (p.bytesReceived.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                val pct = ((p.bytesReceived * 100L) / total).toInt().coerceIn(0, 100)
                val pctA11y = stringResource(R.string.gv_update_progress_a11y, pct)
                val remaining = total - p.bytesReceived
                val etaSec = UpdateDownloadProgressMath.etaSecondsRemaining(
                    remaining.coerceAtLeast(0L),
                    p.smoothedBytesPerSecond,
                )
                val etaLine = if (etaSec != null && remaining > 0L) {
                    val etaFormatted = formatEtaString(etaSec)
                    stringResource(R.string.gv_update_progress_eta, etaFormatted)
                } else {
                    estimating
                }
                DownloadProgressUi(
                    headline = stringResource(R.string.gv_update_status_downloading),
                    progressFraction = frac,
                    progressA11y = pctA11y,
                    percentText = stringResource(R.string.gv_update_progress_percent, pct),
                    percentDimmed = false,
                    speedText = stringResource(
                        R.string.gv_update_progress_speed,
                        GeoVaultFileSizeFormat.humanBytes(p.smoothedBytesPerSecond),
                    ),
                    speedDimmed = false,
                    amountCentered = stringResource(
                        R.string.gv_update_progress_downloaded_of_total,
                        GeoVaultFileSizeFormat.humanBytes(p.bytesReceived),
                        GeoVaultFileSizeFormat.humanBytes(total),
                    ),
                    amountDimmed = false,
                    etaCentered = etaLine,
                    etaDimmed = false,
                )
            } else {
                DownloadProgressUi(
                    headline = stringResource(R.string.gv_update_status_downloading),
                    progressFraction = null,
                    progressA11y = indeterminateA11y,
                    percentText = dash,
                    percentDimmed = true,
                    speedText = stringResource(
                        R.string.gv_update_progress_speed,
                        GeoVaultFileSizeFormat.humanBytes(p.smoothedBytesPerSecond),
                    ),
                    speedDimmed = false,
                    amountCentered = stringResource(
                        R.string.gv_update_progress_downloaded_of_total,
                        GeoVaultFileSizeFormat.humanBytes(p.bytesReceived),
                        dash,
                    ),
                    amountDimmed = false,
                    etaCentered = estimating,
                    etaDimmed = false,
                )
            }
        }
        ApkDownloadState.OpeningInstaller -> DownloadProgressUi(
            headline = stringResource(R.string.gv_update_status_download_complete),
            progressFraction = 1f,
            progressA11y = stringResource(R.string.gv_update_progress_a11y, 100),
            percentText = stringResource(R.string.gv_update_progress_percent, 100),
            percentDimmed = false,
            speedText = dash,
            speedDimmed = true,
            amountCentered = dash,
            amountDimmed = true,
            etaCentered = stringResource(R.string.gv_update_status_opening_installer),
            etaDimmed = false,
        )
        else -> DownloadProgressUi(
            headline = stringResource(R.string.gv_update_status_connecting),
            progressFraction = null,
            progressA11y = indeterminateA11y,
            percentText = dash,
            percentDimmed = true,
            speedText = dash,
            speedDimmed = true,
            amountCentered = dash,
            amountDimmed = true,
            etaCentered = dash,
            etaDimmed = true,
        )
    }
}

@Composable
private fun downloadProgressCaptionColor(dimmed: Boolean): Color =
    if (dimmed) MaterialTheme.colors.onSurface.copy(alpha = 0.38f) else MaterialTheme.colors.onSurface

@Composable
private fun UpdateDownloadProgressContent(
    downloadState: ApkDownloadState,
    indeterminateA11y: String,
) {
    val ui = downloadProgressUi(downloadState, indeterminateA11y)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = ui.headline,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val barModifier = Modifier
                .weight(1f)
                .height(4.dp)
                .semantics { contentDescription = ui.progressA11y }
            val frac = ui.progressFraction
            if (frac == null) {
                LinearProgressIndicator(modifier = barModifier)
            } else {
                LinearProgressIndicator(progress = frac, modifier = barModifier)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = ui.percentText,
                style = MaterialTheme.typography.caption,
                color = downloadProgressCaptionColor(ui.percentDimmed),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = ui.speedText,
                style = MaterialTheme.typography.caption,
                color = downloadProgressCaptionColor(ui.speedDimmed),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = ui.amountCentered,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 18.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption,
            color = downloadProgressCaptionColor(ui.amountDimmed),
        )
        Text(
            text = ui.etaCentered,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 18.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption,
            color = downloadProgressCaptionColor(ui.etaDimmed),
        )
    }
}

@Composable
private fun formatEtaString(secondsIn: Long): String {
    val s = secondsIn.coerceAtLeast(1L)
    val m = s / 60
    val secRem = s % 60
    return if (m > 0L) {
        stringResource(R.string.gv_update_eta_min_sec, m, secRem)
    } else {
        stringResource(R.string.gv_update_eta_sec, secRem)
    }
}

private fun formatPublishedAt(iso: String, unknown: String): String {
    val trimmed = iso.trim()
    if (trimmed.isBlank()) return unknown
    return try {
        val instant = Instant.parse(trimmed)
        val zdt = instant.atZone(ZoneId.systemDefault())
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(zdt)
    } catch (_: Exception) {
        trimmed
    }
}

private fun displayReleaseName(update: VersionCheckResult.UpdateAvailable): String {
    return update.releaseTitle.trim().ifBlank {
        update.releaseTag.trim().ifBlank { update.versionLabel }
    }
}

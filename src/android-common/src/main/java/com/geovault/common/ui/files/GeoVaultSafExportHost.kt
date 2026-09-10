package com.geovault.common.ui.files

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.files.GeoVaultSafDocumentWriter
import com.geovault.common.files.GeoVaultSafExportRequest
import com.geovault.common.files.GeoVaultSafExportSession
import com.geovault.common.files.GeoVaultSafPendingExportStore
import com.geovault.common.ui.navigation.findComponentActivity

interface GeoVaultSafExport {
    fun launch(
        request: GeoVaultSafExportRequest,
        writeFailedMessage: String = GeoVaultSafExportSession.DEFAULT_WRITE_FAILED_MESSAGE,
        onWriteFailed: ((String) -> Unit)? = null,
    )
}

val LocalGeoVaultSafExport = staticCompositionLocalOf<GeoVaultSafExport> {
    error("GeoVault SAF export is not available. Wrap content with GeoVaultSafExportHost.")
}

/**
 * Registers the process-wide SAF create-document contract and exposes [LocalGeoVaultSafExport].
 *
 * Installed from [com.geovault.common.ui.theme.GeoVaultTheme]. Without a [androidx.activity.ComponentActivity]
 * the local is still provided; [GeoVaultSafExport.launch] errors so previews cannot silently no-op a Save.
 */
@Composable
fun GeoVaultSafExportHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    if (context.findComponentActivity() == null) {
        CompositionLocalProvider(LocalGeoVaultSafExport provides UnavailableSafExport) {
            content()
        }
        return
    }
    GeoVaultSafExportHostActive(content)
}

@Composable
private fun GeoVaultSafExportHostActive(content: @Composable () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val session = remember(appContext) { createSession(appContext) }
    val launcher = rememberLauncherForActivityResult(
        GeoVaultCreateDocumentWithMimeContract(),
    ) { uri ->
        session.complete(uri)
    }
    val export = remember(session, launcher, appContext) {
        ActivitySafExport(session, launcher, appContext)
    }
    CompositionLocalProvider(LocalGeoVaultSafExport provides export) {
        content()
    }
}

private fun createSession(appContext: Context): GeoVaultSafExportSession {
    return GeoVaultSafExportSession(
        store = GeoVaultSafPendingExportStore.inCache(appContext.cacheDir),
        writer = GeoVaultSafDocumentWriter(),
        resolver = appContext.contentResolver,
        notifySuccess = { uri, staged ->
            ExportedFileToast.show(
                context = appContext,
                destinationUri = uri,
                fallbackBaseName = staged.fallbackBaseName,
                extensionWithoutDot = staged.extensionWithoutDot,
            )
        },
        notifyFailure = { message, listener ->
            if (listener != null) {
                listener(message)
            } else {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        },
    )
}

private class ActivitySafExport(
    private val session: GeoVaultSafExportSession,
    private val launcher: ManagedActivityResultLauncher<GeoVaultCreateDocumentRequest, Uri?>,
    private val appContext: Context,
) : GeoVaultSafExport {
    override fun launch(
        request: GeoVaultSafExportRequest,
        writeFailedMessage: String,
        onWriteFailed: ((String) -> Unit)?,
    ) {
        try {
            session.stage(request, writeFailedMessage, onWriteFailed)
            launcher.launch(
                GeoVaultCreateDocumentRequest(
                    suggestedName = request.suggestedFileName,
                    mimeType = request.mimeType,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "SAF export stage failed", t)
            session.cancel()
            if (onWriteFailed != null) {
                onWriteFailed(writeFailedMessage)
            } else {
                Toast.makeText(appContext, writeFailedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private const val TAG: String = "GeoVaultSafExport"

private object UnavailableSafExport : GeoVaultSafExport {
    override fun launch(
        request: GeoVaultSafExportRequest,
        writeFailedMessage: String,
        onWriteFailed: ((String) -> Unit)?,
    ) {
        error("SAF export requires a ComponentActivity")
    }
}

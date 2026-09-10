package com.geovault.common.files

import android.content.ContentResolver
import android.net.Uri
import com.geovault.common.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one in-flight SAF Save: stage to disk, then write when the picker returns a URI.
 *
 * Disk is the source of truth. Same-process [onWriteFailed] is a volatile listener set at
 * [stage]; after process death the host Toasts the persisted
 * [GeoVaultStagedSafExport.writeFailedMessage].
 */
class GeoVaultSafExportSession(
    private val store: GeoVaultSafPendingExportStore,
    private val writer: GeoVaultSafDocumentWriter,
    private val resolver: ContentResolver,
    private val notifySuccess: (Uri, GeoVaultStagedSafExport) -> Unit,
    private val notifyFailure: (String, ((String) -> Unit)?) -> Unit,
    private val ioScope: CoroutineScope = processIoScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    @Volatile
    private var writeFailedListener: ((String) -> Unit)? = null

    fun stage(
        request: GeoVaultSafExportRequest,
        writeFailedMessage: String,
        onWriteFailed: ((String) -> Unit)? = null,
    ) {
        writeFailedListener = onWriteFailed
        store.stage(request, writeFailedMessage)
    }

    fun complete(uri: Uri?) {
        if (uri == null) {
            cancel()
            return
        }
        val listener = writeFailedListener
        writeFailedListener = null
        ioScope.launch {
            val staged = store.consume()
            if (staged == null) {
                writer.deleteDocument(resolver, uri)
                reportFailure(DEFAULT_WRITE_FAILED_MESSAGE, listener)
                return@launch
            }
            try {
                writer.write(resolver, uri, staged.payloadFile)
                store.clear()
                withContext(mainDispatcher) {
                    notifySuccess(uri, staged)
                }
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                writer.deleteDocument(resolver, uri)
                store.clear()
                reportFailure(staged.writeFailedMessage, listener)
            }
        }
    }

    fun cancel() {
        writeFailedListener = null
        store.clear()
    }

    private suspend fun reportFailure(message: String, listener: ((String) -> Unit)?) {
        withContext(mainDispatcher) {
            notifyFailure(message, listener)
        }
    }

    companion object {
        const val DEFAULT_WRITE_FAILED_MESSAGE: String = "Export failed"

        private val processIoScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.settings.GeoVaultCachedDocumentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

data class UploaderSettings(
    val suffixEnabled: Boolean = true,
)

fun UploaderOptionsDocument.toSettings(): UploaderSettings {
    return UploaderSettings(suffixEnabled = addFilenameSuffix)
}

class UploaderSettingsStore(
    context: Context,
    fileName: String = UploaderOptionsDocument.FILE_NAME,
    legacyFileName: String = UploaderOptionsDocument.LEGACY_FILE_NAME,
) {
    private val cached = GeoVaultCachedDocumentStore(
        context = context.applicationContext,
        fileName = fileName,
        documentSerializer = UploaderOptionsDocument.serializer(),
        defaultValue = UploaderOptionsDocument(),
        currentVersion = UploaderOptionsDocument.SCHEMA_VERSION,
        legacyFileName = legacyFileName,
        legacyMapper = UploaderOptionsDocument::fromLegacy,
    )

    val settings: Flow<UploaderSettings> = cached.data.map { document -> document.toSettings() }

    fun snapshot(): UploaderSettings = cached.data.value.toSettings()

    fun preloadOnLaunch() {
        cached.preloadBlocking()
    }

    suspend fun awaitReady() {
        cached.awaitReady()
    }

    suspend fun setSuffixEnabled(enabled: Boolean) {
        cached.update { current -> current.copy(addFilenameSuffix = enabled) }
    }

    fun clearAll() {
        runBlocking {
            cached.awaitReady()
            cached.update { UploaderOptionsDocument() }
        }
    }
}

package com.geovault.tracker.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.settings.GeoVaultDocumentStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrackerSettingsDataStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = GeoVaultDocumentStore(
        context = appContext,
        fileName = TrackerSettingsDocument.FILE_NAME,
        documentSerializer = TrackerSettingsDocument.serializer(),
        defaultValue = TrackerSettingsDocument(),
        currentVersion = TrackerSettingsDocument.SCHEMA_VERSION,
        legacyMapper = TrackerSettingsDocument::fromLegacy,
    )
    private val migrateMutex = Mutex()

    @Volatile
    private var legacyMigrated = false

    fun observeRecord(): Flow<TrackerSettingsRecord> {
        return flow {
            migrateFromPreferencesDataStoreIfNeeded()
            emitAll(store.data.map { it.toRecord() })
        }
    }

    suspend fun readRecord(): TrackerSettingsRecord = observeRecord().first()

    suspend fun writeSettings(settings: TrackerSettings) {
        updateRecord(reason = "write_settings") { current ->
            current.copy(settings = settings)
        }
    }

    suspend fun setWasTrackingBeforeExit(value: Boolean) {
        updateRecord(reason = "set_was_tracking_before_exit") { current ->
            current.copy(wasTrackingBeforeExit = value)
        }
    }

    suspend fun clearWasTrackingBeforeExit() {
        updateRecord(reason = "clear_was_tracking_before_exit") { current ->
            current.copy(wasTrackingBeforeExit = false)
        }
    }

    suspend fun updateRecord(
        reason: String,
        transform: (TrackerSettingsRecord) -> TrackerSettingsRecord
    ) {
        migrateFromPreferencesDataStoreIfNeeded()
        store.update { current ->
            val currentRecord = current.toRecord()
            val next = transform(currentRecord)
            if (next == currentRecord) {
                GeoVaultCaptureLog.i(
                    TAG,
                    "datastore_write_ignored reason=$reason cause=no_op schema=${currentRecord.schemaVersion} " +
                        "wasTrackingBeforeExit=${currentRecord.wasTrackingBeforeExit} settings=${settingsSummary(currentRecord.settings)}"
                )
                return@update current
            }
            GeoVaultCaptureLog.i(
                TAG,
                "datastore_write reason=$reason schema=${next.schemaVersion} wasTrackingBeforeExit=${next.wasTrackingBeforeExit} settings=${settingsSummary(next.settings)}"
            )
            TrackerSettingsDocument.fromRecord(next)
        }
    }

    suspend fun resetToDefaults(schemaVersion: Int) {
        GeoVaultCaptureLog.w(TAG, "datastore_reset_to_defaults schema=$schemaVersion")
        migrateFromPreferencesDataStoreIfNeeded()
        store.update { TrackerSettingsDocument() }
    }

    private suspend fun migrateFromPreferencesDataStoreIfNeeded() {
        if (legacyMigrated) return
        migrateMutex.withLock {
            if (legacyMigrated) return
            val newFile = File(appContext.filesDir, "datastore/${TrackerSettingsDocument.FILE_NAME}")
            if (!newFile.exists() || newFile.length() == 0L) {
                readLegacyPreferencesOrNull()?.let { legacy ->
                    store.update { legacy }
                }
            }
            legacyMigrated = true
        }
    }

    private suspend fun readLegacyPreferencesOrNull(): TrackerSettingsDocument? {
        val file = appContext.preferencesDataStoreFile(TrackerSettingsDocument.LEGACY_DATASTORE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        val legacyStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { file },
        )
        val prefs = legacyStore.data.first()
        if (prefs.asMap().isEmpty()) return null
        return TrackerSettingsDocument.fromLegacyPreferences(prefs)
    }

    private fun settingsSummary(settings: TrackerSettings): String {
        return "startOnBoot=${settings.startOnBoot},startOnLaunch=${settings.startTrackingOnLaunch},extended=${settings.sendExtendedData},sigMotion=${settings.significantDataOnly},sparse=${settings.sparseTracking},lowAccFallback=${settings.lowAccuracyFallbackEnabled},keepScreenOn=${settings.keepScreenOnWhileViewingMap},groupFitActiveOnly=${settings.groupModeFitOnlyActiveTrackers},lowAccTimeout=${settings.lowAccuracyFallbackTimeoutSec}"
    }

    companion object {
        private const val TAG = "TrackerSettingsStore"
    }
}

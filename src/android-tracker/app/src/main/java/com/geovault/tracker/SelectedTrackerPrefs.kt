package com.geovault.tracker

import android.content.Context
import com.geovault.common.settings.GeoVaultDocumentStore
import com.geovault.tracker.settings.TrackerSelectionDocument
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object SelectedTrackerPrefs {
    private val lock = Any()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: GeoVaultDocumentStore<TrackerSelectionDocument>? = null

    @Volatile
    private var cached = TrackerSelectionDocument()

    fun selectedTrackerId(context: Context): String = cached(context).selectedTrackerId

    fun selectedTrackerName(context: Context): String = cached(context).selectedTrackerName

    fun setSelectedTracker(context: Context, trackerId: String, trackerName: String?): Boolean {
        val next = TrackerSelectionDocument(
            selectedTrackerId = trackerId.trim(),
            selectedTrackerName = trackerName?.trim().orEmpty(),
        )
        return persist(context, next)
    }

    fun clearSelectedTracker(context: Context) {
        persist(context, TrackerSelectionDocument())
    }

    fun updateSelectedTrackerName(context: Context, trackerName: String?) {
        persist(
            context,
            cached(context).copy(selectedTrackerName = trackerName?.trim().orEmpty()),
        )
    }

    private fun cached(context: Context): TrackerSelectionDocument {
        ensureStore(context)
        return cached
    }

    private fun persist(context: Context, next: TrackerSelectionDocument): Boolean {
        val documentStore = ensureStore(context)
        cached = next
        return runCatching {
            runBlocking(Dispatchers.IO) {
                documentStore.update { next }
            }
            true
        }.getOrDefault(false)
    }

    private fun ensureStore(context: Context): GeoVaultDocumentStore<TrackerSelectionDocument> {
        store?.let { return it }
        synchronized(lock) {
            store?.let { return it }
            val appContext = context.applicationContext
            val documentStore = GeoVaultDocumentStore(
                context = appContext,
                fileName = TrackerSelectionDocument.FILE_NAME,
                documentSerializer = TrackerSelectionDocument.serializer(),
                defaultValue = TrackerSelectionDocument(),
                currentVersion = TrackerSelectionDocument.SCHEMA_VERSION,
                legacyMapper = TrackerSelectionDocument::fromLegacy,
            )
            runBlocking(Dispatchers.IO) {
                migrateFromSharedPreferencesIfNeeded(appContext, documentStore)
                cached = documentStore.get()
            }
            appScope.launch {
                documentStore.data.collect { cached = it }
            }
            store = documentStore
            return documentStore
        }
    }

    private suspend fun migrateFromSharedPreferencesIfNeeded(
        context: Context,
        documentStore: GeoVaultDocumentStore<TrackerSelectionDocument>,
    ) {
        val newFile = File(context.filesDir, "datastore/${TrackerSelectionDocument.FILE_NAME}")
        if (newFile.exists() && newFile.length() > 0L) return
        val prefs = context.getSharedPreferences(
            TrackerSelectionDocument.LEGACY_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val hasLegacy = prefs.contains(TrackerSelectionDocument.KEY_SELECTED_TRACKER_ID) ||
            prefs.contains(TrackerSelectionDocument.KEY_SELECTED_TRACKER_NAME)
        if (!hasLegacy) return
        documentStore.update { TrackerSelectionDocument.fromLegacyPreferences(prefs) }
        prefs.edit()
            .remove(TrackerSelectionDocument.KEY_SELECTED_TRACKER_ID)
            .remove(TrackerSelectionDocument.KEY_SELECTED_TRACKER_NAME)
            .apply()
    }
}

package com.geovault.tracker.map

import android.app.Application
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.history.TrackerHistoryIntentDispatcher
import com.geovault.tracker.history.TrackerHistoryRepository
import com.geovault.tracker.presentation.LiveTrackStreamingReconciler
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository

/**
 * The map's DI graph: repositories and services resolved once from [TrackerAppServices]/
 * [AppDatabase] and never reassigned for the lifetime of [TrackerMapRuntime]. Grouped into its
 * own holder -- rather than ~9 individual vals directly on [TrackerMapRuntime] -- so the DI graph
 * has an identity separate from runtime orchestration state, and so tests get a single seam to
 * substitute fakes instead of having to reach into the runtime itself.
 */
internal class TrackerMapDependencies(application: Application) {
    internal val appContext = application.applicationContext
    internal val dao = AppDatabase.getDatabase(application).locationDao()
    internal val trackerManagementRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    internal val trackerManagementStateStore: TrackerManagementStateStore =
        TrackerAppServices.from(application).trackerManagementStateStore()
    internal val trackerSettingsRepository: TrackerSettingsRepository =
        TrackerAppServices.from(application).trackerSettingsRepository()
    internal val liveStreamSubscriptionRepository: LiveStreamSubscriptionRepository =
        TrackerAppServices.from(application).liveStreamSubscriptionRepository()
    internal val streamingReconciler = LiveTrackStreamingReconciler(liveStreamSubscriptionRepository)
    internal val historyRepository: TrackerHistoryRepository =
        TrackerAppServices.from(application).trackerHistoryRepository()
    internal val historyIntentDispatcher = TrackerHistoryIntentDispatcher(historyRepository)
}

package com.geovault.tracker.navigation

import androidx.fragment.app.Fragment
import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

interface TrackerNavHost {
    val hasPendingInitialTrackForMap: Boolean
    val isServerAccessible: Boolean

    fun setInitialTrackForMap(tracker: Tracker?)
    fun getAndClearInitialTrackForMap(): Tracker?
    fun getAndClearInitialGroupAndZoomForMap(): Pair<Group?, String?>
    fun setCurrentTab(index: Int, forceRefreshMap: Boolean = false, delayMs: Long = 0)

    fun openMapForGroup(group: Group, zoomToTrackerId: String? = null, returnToTabOnly: Boolean = false)
    fun openMapAllTrackers()
    fun openTrackersAndScrollTo(trackerId: String?)
    fun openSharedAndScrollTo(trackerId: String?, groupId: String? = null)
    fun openGroupMembersAndScrollTo(group: Group, trackerId: String?)

    fun showNewTrackerFragment()
    fun showEditTrackerFragment(tracker: Tracker)
    fun refreshMapAfterTrackerSettingsSaved(trackerId: String)
    fun showEditSharedTrackerFragment(tracker: Tracker)
    fun showEditSharedGroupFragment(group: Group)
    fun showGroupsFragment()
    fun showHiddenTrackersFragment()
    fun showTrackerParamsFragment(
        trackerId: String,
        trackerName: String?,
        lastUpdateMs: Long? = null,
        positionLat: Double? = null,
        positionLon: Double? = null
    )
    fun showSnackbar(message: String)
    fun hasLocationPermission(): Boolean
    fun hasBackgroundLocationPermission(): Boolean
    fun hasNotificationPermission(): Boolean
    fun hasBatteryOptimizationExemption(): Boolean
    fun hasExactAlarmPermission(): Boolean
    fun hasAllRequiredPermissions(): Boolean
    fun requestLocationPermission()
    fun requestBackgroundLocationPermission()
    fun requestNotificationPermission()
    fun requestBatteryOptimizationExemption()
    fun requestExactAlarmPermission()
    fun toggleTracking()
}

fun Fragment.navHost(): TrackerNavHost? = activity as? TrackerNavHost

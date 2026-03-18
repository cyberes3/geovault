package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.GroupPatchRequest
import com.google.android.material.button.MaterialButton

class HiddenTrackersFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var showAllButton: MaterialButton

    private sealed class HiddenItem {
        abstract val name: String
        data class Tracker(val id: String, override val name: String) : HiddenItem()
        /** source: "list" = owner hidden_in_list (unhide via PATCH group), "map" = in hidden_group_ids (unhide via patchMapVisibility) */
        data class Group(val id: String, override val name: String, val source: String) : HiddenItem()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_hidden_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.hiddenTrackersSwipeRefresh)
        loadingOverlay = view.findViewById(R.id.hiddenTrackersLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.hiddenTrackersLoadingSpinner)
        listContainer = view.findViewById(R.id.hiddenTrackersList)
        emptyText = view.findViewById(R.id.hiddenTrackersEmpty)
        showAllButton = view.findViewById(R.id.hiddenTrackersShowAll)
        val closeButton = view.findViewById<ImageButton>(R.id.hiddenTrackersCloseButton)

        closeButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        swipeRefresh.setColorSchemeResources(R.color.primary_blue)

        swipeRefresh.setOnRefreshListener { loadAll() }
        loadAll()
    }

    private fun isHiddenInList(tracker: Tracker): Boolean {
        return (tracker.settings?.get("hidden_in_list") as? Boolean) == true
    }

    private fun loadAll() {
        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.start()
        var trackersDone = false
        var groupsDone = false
        fun maybeFinish() {
            if (!trackersDone || !groupsDone) return
            loadingOverlay.visibility = View.GONE
            loadingSpinner.stop(hide = false)
            swipeRefresh.isRefreshing = false
        }
        var hiddenTrackers: List<HiddenItem.Tracker> = emptyList()
        var hiddenGroups: List<HiddenItem.Group> = emptyList()
        fun bindCombined() {
            val items = (hiddenTrackers as List<HiddenItem>) + hiddenGroups
                .sortedBy { it.name.lowercase() }
            bindList(items)
        }
        fun checkBind() {
            if (trackersDone && groupsDone) bindCombined()
        }
        TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
            if (!isAdded) return@getTrackers
            hiddenTrackers = (list ?: emptyList())
                .filter { it.isOwner() && isHiddenInList(it) }
                .map { HiddenItem.Tracker(id = it.id, name = it.name) }
            requireActivity().runOnUiThread {
                trackersDone = true
                checkBind()
                maybeFinish()
            }
        }
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
            TrackerRepository.getGroups(requireContext(), forceRefresh = true) { list ->
                if (!isAdded) return@getGroups
                val groupsList = list ?: emptyList()
                val listHidden = groupsList
                    .filter { it.is_owner == true && it.hidden_in_list == true }
                    .map { HiddenItem.Group(id = it.id, name = it.name, source = "list") }
                val listHiddenIds = listHidden.map { it.id }.toSet()
                val mapHidden = groupsList
                    .filter { it.id in hiddenGroupIds && it.id !in listHiddenIds }
                    .map { HiddenItem.Group(id = it.id, name = it.name, source = "map") }
                hiddenGroups = listHidden + mapHidden
                requireActivity().runOnUiThread {
                    groupsDone = true
                    checkBind()
                    maybeFinish()
                }
            }
        }
    }

    private fun bindList(items: List<HiddenItem>) {
        listContainer.removeAllViews()
        if (items.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            showAllButton.visibility = View.GONE
            return
        }
        emptyText.visibility = View.GONE
        showAllButton.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        showAllButton.isEnabled = true

        val trackerIds = items.filterIsInstance<HiddenItem.Tracker>().map { it.id }
        val groupItems = items.filterIsInstance<HiddenItem.Group>()

        for (item in items) {
            val layoutId = when (item) {
                is HiddenItem.Tracker -> R.layout.item_hidden_tracker_row
                is HiddenItem.Group -> R.layout.item_hidden_group_row
            }
            val row = layoutInflater.inflate(layoutId, listContainer, false)
            row.findViewById<TextView>(R.id.hiddenTrackerName).text = item.name
            val showBtn = row.findViewById<ImageButton>(R.id.hiddenTrackerShow)
            showBtn.setOnClickListener {
                showBtn.isEnabled = false
                when (item) {
                    is HiddenItem.Tracker -> unhideTracker(
                        item.id,
                        onSuccess = {
                            listContainer.removeView(row)
                            if (listContainer.childCount == 0) {
                                emptyText.visibility = View.VISIBLE
                                showAllButton.visibility = View.GONE
                            }
                        },
                        onFailure = { showBtn.isEnabled = true }
                    )
                    is HiddenItem.Group -> if (item.source == "list") {
                        unhideGroup(
                            item.id,
                            onSuccess = {
                                listContainer.removeView(row)
                                if (listContainer.childCount == 0) {
                                    emptyText.visibility = View.VISIBLE
                                    showAllButton.visibility = View.GONE
                                }
                            },
                            onFailure = { showBtn.isEnabled = true }
                        )
                    } else {
                        unhideGroupFromMap(
                            item.id,
                            onSuccess = {
                                listContainer.removeView(row)
                                if (listContainer.childCount == 0) {
                                    emptyText.visibility = View.VISIBLE
                                    showAllButton.visibility = View.GONE
                                }
                            },
                            onFailure = { showBtn.isEnabled = true }
                        )
                    }
                }
            }
            listContainer.addView(row)
        }

        showAllButton.setOnClickListener {
            showAllButton.isEnabled = false
            for (i in 0 until listContainer.childCount) {
                listContainer.getChildAt(i).findViewById<ImageButton>(R.id.hiddenTrackerShow)?.isEnabled = false
            }
            unhideAllTrackers(trackerIds = trackerIds, onComplete = {
                unhideAllGroups(groupItems, onComplete = { loadAll() })
            })
        }
    }

    private fun unhideTracker(
        trackerId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        TrackerRepository.updateTrackerSettings(
            requireContext(),
            trackerId,
            TrackerSettingsRequest(hidden_in_list = false)
        ) { updated, errorMessage ->
            if (!isAdded) return@updateTrackerSettings
            requireActivity().runOnUiThread {
                if (updated != null) {
                    TrackersListFragment.pendingFullRefresh = true
                    onSuccess?.invoke()
                } else {
                    navHost()?.showSnackbar(
                        errorMessage ?: getString(R.string.failed_to_load_tracker)
                    )
                    onFailure?.invoke()
                }
            }
        }
    }

    private fun unhideAllTrackers(trackerIds: List<String>, onComplete: () -> Unit, index: Int = 0) {
        if (index >= trackerIds.size) {
            TrackersListFragment.pendingFullRefresh = true
            onComplete()
            return
        }
        TrackerRepository.updateTrackerSettings(
            requireContext(),
            trackerIds[index],
            TrackerSettingsRequest(hidden_in_list = false)
        ) { _, _ ->
            if (!isAdded) return@updateTrackerSettings
            requireActivity().runOnUiThread {
                unhideAllTrackers(trackerIds, onComplete, index + 1)
            }
        }
    }

    private fun unhideGroup(
        groupId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        TrackerRepository.patchGroup(requireContext(), groupId, GroupPatchRequest(hidden_in_list = false)) { updated, errorMessage ->
            if (!isAdded) return@patchGroup
            requireActivity().runOnUiThread {
                if (updated != null) {
                    onSuccess?.invoke()
                } else {
                    navHost()?.showSnackbar(
                        errorMessage ?: getString(R.string.failed_to_load_tracker)
                    )
                    onFailure?.invoke()
                }
            }
        }
    }

    private fun unhideGroupFromMap(
        groupId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val current = (visibility?.hidden_group_ids ?: emptyList()).toMutableList()
            val newList = current.filter { it != groupId }
            if (newList.size == current.size) {
                requireActivity().runOnUiThread { onSuccess?.invoke() }
                return@getMapVisibility
            }
            TrackerRepository.patchMapVisibility(
                requireContext(),
                com.geovault.tracker.MapVisibilityRequest(hidden_group_ids = newList)
            ) { updated ->
                if (!isAdded) return@patchMapVisibility
                requireActivity().runOnUiThread {
                    if (updated != null) onSuccess?.invoke()
                    else {
                        navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                        onFailure?.invoke()
                    }
                }
            }
        }
    }

    private fun unhideAllGroups(groups: List<HiddenItem.Group>, onComplete: () -> Unit, index: Int = 0) {
        if (index >= groups.size) {
            onComplete()
            return
        }
        val item = groups[index]
        val doNext = {
            requireActivity().runOnUiThread {
                unhideAllGroups(groups, onComplete, index + 1)
            }
        }
        if (item.source == "list") {
            TrackerRepository.patchGroup(requireContext(), item.id, GroupPatchRequest(hidden_in_list = false)) { _, _ ->
                if (!isAdded) return@patchGroup
                doNext()
            }
        } else {
            TrackerRepository.getMapVisibility(requireContext()) { visibility ->
                if (!isAdded) return@getMapVisibility
                val current = (visibility?.hidden_group_ids ?: emptyList()).filter { it != item.id }
                TrackerRepository.patchMapVisibility(
                    requireContext(),
                    com.geovault.tracker.MapVisibilityRequest(hidden_group_ids = current)
                ) { _ ->
                    if (!isAdded) return@patchMapVisibility
                    doNext()
                }
            }
        }
    }
}

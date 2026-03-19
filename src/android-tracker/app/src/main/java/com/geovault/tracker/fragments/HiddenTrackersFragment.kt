package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.common.NaturalSort
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HiddenTrackersFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var showAllButton: MaterialButton

    @Inject
    lateinit var trackerManagementRepository: TrackerManagementRepository

    @Inject
    lateinit var groupManagementRepository: GroupManagementRepository

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
        viewLifecycleOwner.lifecycleScope.launch {
            val trackerList = when (val result = trackerManagementRepository.loadTrackers(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> emptyList()
            }
            val visibility = when (val result = trackerManagementRepository.loadMapVisibility(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> null
            }
            val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
            val groupsList = when (val result = groupManagementRepository.loadGroups(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> emptyList()
            }

            val hiddenTrackers = trackerList
                .filter { it.isOwner() && isHiddenInList(it) }
                .map { HiddenItem.Tracker(id = it.id, name = it.name) }

            val listHidden = groupsList
                .filter { it.is_owner == true && it.hidden_in_list == true }
                .map { HiddenItem.Group(id = it.id, name = it.name, source = "list") }
            val listHiddenIds = listHidden.map { it.id }.toSet()
            val mapHidden = groupsList
                .filter { it.id in hiddenGroupIds && it.id !in listHiddenIds }
                .map { HiddenItem.Group(id = it.id, name = it.name, source = "map") }
            val hiddenGroups = listHidden + mapHidden

            val items = (hiddenTrackers as List<HiddenItem>) + hiddenGroups
            bindList(items.sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() }))
            loadingOverlay.visibility = View.GONE
            loadingSpinner.stop(hide = false)
            swipeRefresh.isRefreshing = false
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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = trackerManagementRepository.updateTrackerSettings(
                trackerId,
                TrackerSettingsRequest(hidden_in_list = false)
            )
            if (result is RepositoryResult.Success) {
                requireActivity().supportFragmentManager.setFragmentResult(
                    TrackersListFragment.REQUEST_REFRESH_LIST,
                    Bundle()
                )
                onSuccess?.invoke()
            } else {
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                onFailure?.invoke()
            }
        }
    }

    private fun unhideAllTrackers(trackerIds: List<String>, onComplete: () -> Unit, index: Int = 0) {
        viewLifecycleOwner.lifecycleScope.launch {
            for (id in trackerIds.drop(index)) {
                trackerManagementRepository.updateTrackerSettings(
                    id,
                    TrackerSettingsRequest(hidden_in_list = false)
                )
            }
            requireActivity().supportFragmentManager.setFragmentResult(
                TrackersListFragment.REQUEST_REFRESH_LIST,
                Bundle()
            )
            onComplete()
        }
    }

    private fun unhideGroup(
        groupId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = groupManagementRepository.patchGroup(groupId, GroupPatchRequest(hidden_in_list = false))
            if (result is RepositoryResult.Success) {
                onSuccess?.invoke()
            } else {
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                onFailure?.invoke()
            }
        }
    }

    private fun unhideGroupFromMap(
        groupId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val visibility = when (val result = trackerManagementRepository.loadMapVisibility(forceRefresh = true)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> null
            }
            val current = (visibility?.hidden_group_ids ?: emptyList()).toMutableList()
            val newList = current.filter { it != groupId }
            if (newList.size == current.size) {
                onSuccess?.invoke()
                return@launch
            }
            val result = trackerManagementRepository.patchMapVisibility(
                MapVisibilityRequest(hidden_group_ids = newList)
            )
            if (result is RepositoryResult.Success) onSuccess?.invoke() else {
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                onFailure?.invoke()
            }
        }
    }

    private fun unhideAllGroups(groups: List<HiddenItem.Group>, onComplete: () -> Unit, index: Int = 0) {
        viewLifecycleOwner.lifecycleScope.launch {
            var currentHiddenGroupIds: List<String>? = null
            for (item in groups.drop(index)) {
                if (item.source == "list") {
                    groupManagementRepository.patchGroup(item.id, GroupPatchRequest(hidden_in_list = false))
                } else {
                    if (currentHiddenGroupIds == null) {
                        currentHiddenGroupIds = when (val result = trackerManagementRepository.loadMapVisibility(forceRefresh = true)) {
                            is RepositoryResult.Success -> result.data.hidden_group_ids
                            is RepositoryResult.Failure -> emptyList()
                        }
                    }
                    currentHiddenGroupIds = currentHiddenGroupIds.orEmpty().filter { it != item.id }
                }
            }
            currentHiddenGroupIds?.let { remainingHidden ->
                trackerManagementRepository.patchMapVisibility(
                    MapVisibilityRequest(hidden_group_ids = remainingHidden)
                )
            }
            onComplete()
        }
    }
}

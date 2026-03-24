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
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerSettingsRequest
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

    private fun isHidden(tracker: Tracker): Boolean {
        return (tracker.settings?.get("hidden") as? Boolean) == true
    }

    private fun isHiddenOwnedGroup(group: Group): Boolean {
        return group.is_owner == true && group.hidden == true
    }

    private fun loadAll(showLoadingOverlay: Boolean = true) {
        if (showLoadingOverlay) {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val trackerList = when (val result = trackerManagementRepository.loadTrackers(forceRefresh = false)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> emptyList()
            }
            val groupList = when (val result = groupManagementRepository.loadGroups(forceRefresh = false)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> emptyList()
            }

            val hiddenTrackers = trackerList
                .filter { it.isOwner() && isHidden(it) }
                .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
            val hiddenGroups = groupList
                .filter { isHiddenOwnedGroup(it) }
                .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })

            bindList(hiddenTrackers, hiddenGroups)
            if (showLoadingOverlay) {
                loadingOverlay.visibility = View.GONE
                loadingSpinner.stop(hide = false)
            }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun bindList(trackers: List<Tracker>, groups: List<Group>) {
        listContainer.removeAllViews()
        if (trackers.isEmpty() && groups.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            showAllButton.visibility = View.GONE
            return
        }
        emptyText.visibility = View.GONE
        val totalHidden = trackers.size + groups.size
        showAllButton.visibility = if (totalHidden > 1) View.VISIBLE else View.GONE
        showAllButton.isEnabled = true

        val trackerIds = trackers.map { it.id }
        val groupIds = groups.map { it.id }

        fun addSectionTitle(textRes: Int) {
            val density = resources.displayMetrics.density
            val padTop = (16 * density).toInt()
            val padBottom = (8 * density).toInt()
            val title = TextView(requireContext()).apply {
                setText(textRes)
                textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(0, padTop, 0, padBottom)
            }
            listContainer.addView(title)
        }

        if (trackers.isNotEmpty()) {
            if (groups.isNotEmpty()) {
                addSectionTitle(R.string.hidden_list_section_trackers)
            }
            for (tracker in trackers) {
                val row = layoutInflater.inflate(R.layout.item_hidden_tracker_row, listContainer, false)
                row.findViewById<TextView>(R.id.hiddenTrackerName).text = tracker.name
                val showBtn = row.findViewById<ImageButton>(R.id.hiddenTrackerShow)
                showBtn.setOnClickListener {
                    showBtn.isEnabled = false
                    unhideTracker(
                        tracker.id,
                        onSuccess = { loadAll(showLoadingOverlay = false) },
                        onFailure = { showBtn.isEnabled = true }
                    )
                }
                listContainer.addView(row)
            }
        }

        if (groups.isNotEmpty()) {
            if (trackers.isNotEmpty()) {
                addSectionTitle(R.string.hidden_groups)
            }
            for (group in groups) {
                val row = layoutInflater.inflate(R.layout.item_hidden_tracker_row, listContainer, false)
                row.findViewById<TextView>(R.id.hiddenTrackerName).text = group.name
                val showBtn = row.findViewById<ImageButton>(R.id.hiddenTrackerShow)
                showBtn.setOnClickListener {
                    showBtn.isEnabled = false
                    unhideGroup(
                        group.id,
                        onSuccess = { loadAll(showLoadingOverlay = false) },
                        onFailure = { showBtn.isEnabled = true }
                    )
                }
                listContainer.addView(row)
            }
        }

        showAllButton.setOnClickListener {
            showAllButton.isEnabled = false
            for (i in 0 until listContainer.childCount) {
                listContainer.getChildAt(i).findViewById<ImageButton>(R.id.hiddenTrackerShow)?.isEnabled = false
            }
            unhideAllHidden(trackerIds, groupIds, onComplete = { loadAll() })
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
                TrackerSettingsRequest(hidden = false)
            )
            if (result is RepositoryResult.Success) {
                onSuccess?.invoke()
            } else {
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                onFailure?.invoke()
            }
        }
    }

    private fun unhideGroup(
        groupId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = groupManagementRepository.patchGroup(
                groupId,
                GroupPatchRequest(hidden = false)
            )
            if (result is RepositoryResult.Success) {
                onSuccess?.invoke()
            } else {
                navHost()?.showSnackbar(getString(R.string.failed_to_save_group))
                onFailure?.invoke()
            }
        }
    }

    private fun unhideAllHidden(trackerIds: List<String>, groupIds: List<String>, onComplete: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            for (id in trackerIds) {
                trackerManagementRepository.updateTrackerSettings(
                    id,
                    TrackerSettingsRequest(hidden = false)
                )
            }
            for (id in groupIds) {
                groupManagementRepository.patchGroup(id, GroupPatchRequest(hidden = false))
            }
            onComplete()
        }
    }
}

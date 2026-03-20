package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.parseHexToColor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupTrackersListFragment : Fragment() {
    private val viewModel: GroupTrackersViewModel by viewModels()

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var addButton: com.google.android.material.button.MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner

    private var group: Group? = null
    private var preloadedAddableTrackers: List<Tracker>? = null
    private val removingTrackIds = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_group_trackers_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listContainer = view.findViewById(R.id.groupTrackersListContainer)
        emptyView = view.findViewById(R.id.groupTrackersListEmpty)
        addButton = view.findViewById(R.id.groupTrackersListAddButton)
        loadingOverlay = view.findViewById(R.id.groupTrackersListLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.groupTrackersListLoadingSpinner)
        view.findViewById<View>(R.id.groupTrackersListCloseButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        group = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        if (group == null) {
            parentFragmentManager.popBackStack()
            return
        }
        view.findViewById<TextView>(R.id.groupTrackersListTitle).text = getString(R.string.group_tracks)
        addButton.visibility = if (group?.is_owner == true) View.VISIBLE else View.GONE
        addButton.setOnClickListener {
            group?.let { g ->
                parentFragmentManager.beginTransaction()
                    .add(
                        R.id.fragment_overlay_container,
                        AddGroupTrackersFragment.newInstance(
                            g.id,
                            g.track_ids ?: emptyList()
                        ),
                        "add_group_trackers"
                    )
                    .addToBackStack("add_group_trackers")
                    .commit()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    setLoadingState(state.isLoading)
                    val current = state.group
                    if (current != null) {
                        group = current
                        addButton.visibility = if (current.is_owner == true) View.VISIBLE else View.GONE
                        bindTrackers(current, state.trackers, state.isLoading)
                    }
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let { navHost()?.showSnackbar(it) }
                }
            }
        }
        viewModel.load(group!!.id)
    }

    override fun onResume() {
        super.onResume()
        group?.id?.let { viewModel.load(it) }
    }

    private fun bindTrackers(g: Group, allTrackers: List<Tracker>, isLoading: Boolean) {
        if (isLoading && allTrackers.isEmpty()) {
            emptyView.visibility = View.GONE
            if (listContainer.childCount == 0) {
                listContainer.visibility = View.GONE
            }
            return
        }

        val trackIds = g.track_ids ?: emptyList()
        val isOwner = g.is_owner == true
        if (trackIds.isEmpty()) {
            listContainer.removeAllViews()
            emptyView.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            return
        }

        val idToTracker = allTrackers.associateBy { it.id }
        val ordered = trackIds.mapNotNull { idToTracker[it] }
        listContainer.removeAllViews()
        if (ordered.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        listContainer.visibility = View.VISIBLE
        for (tracker in ordered) {
            addTrackerCard(g, tracker, isOwner)
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
        } else {
            loadingOverlay.visibility = View.GONE
            loadingSpinner.stop(hide = false)
        }
    }

    private fun addTrackerCard(g: Group, tracker: Tracker, isOwner: Boolean) {
        val card = layoutInflater.inflate(R.layout.item_group_tracker_card, listContainer, false)
        card.findViewById<TextView>(R.id.groupTrackerName).text = tracker.name
        val ownerView = card.findViewById<TextView>(R.id.groupTrackerOwner)
        if (tracker.isOwner()) {
            ownerView.visibility = View.GONE
        } else {
            ownerView.visibility = View.VISIBLE
            ownerView.text = tracker.owner_email?.takeIf { it.isNotBlank() } ?: ""
        }
        card.findViewById<ImageView>(R.id.groupTrackerChevronIcon).setColorFilter(
            parseHexToColor(tracker.color, card.context)
        )
        val menuBtn = card.findViewById<ImageButton>(R.id.groupTrackerMenu)
        val removeContainer = card.findViewById<View>(R.id.groupTrackerRemoveContainer)
        val removeBtn = card.findViewById<ImageButton>(R.id.groupTrackerRemove)
        val removeSpinner = card.findViewById<LoadingSpinner>(R.id.groupTrackerRemoveSpinner)
        menuBtn.visibility = View.GONE
        if (isOwner) {
            removeContainer.visibility = View.VISIBLE
            val isRemoving = tracker.id in removingTrackIds
            removeBtn.visibility = if (isRemoving) View.GONE else View.VISIBLE
            removeSpinner.visibility = if (isRemoving) View.VISIBLE else View.GONE
            if (isRemoving) removeSpinner.start() else removeSpinner.stop(hide = true)
            removeBtn.setOnClickListener {
                if (tracker.id in removingTrackIds) return@setOnClickListener
                removingTrackIds.add(tracker.id)
                removeBtn.visibility = View.GONE
                removeSpinner.visibility = View.VISIBLE
                removeSpinner.start()
                removeTrack(g.id, tracker.id) { success ->
                    if (!success && isAdded) {
                        removeSpinner.stop(hide = true)
                        removeSpinner.visibility = View.GONE
                        removeBtn.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            removeContainer.visibility = View.GONE
            removeBtn.visibility = View.GONE
            removeSpinner.stop(hide = true)
            removeSpinner.visibility = View.GONE
        }
        card.isClickable = false
        card.isFocusable = false
        listContainer.addView(card)
    }

    private fun removeTrack(groupId: String, trackId: String, onDone: (Boolean) -> Unit) {
        viewModel.removeTrack(groupId, trackId) { success ->
            removingTrackIds.remove(trackId)
            if (success) {
                val updatedGroup = viewModel.uiState.value.group
                if (updatedGroup != null) {
                    group = updatedGroup
                    bindTrackers(updatedGroup, viewModel.uiState.value.trackers, isLoading = false)
                }
            } else {
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
            }
            onDone(success)
        }
    }

    companion object {
        const val REQUEST_GROUP_TRACK_REMOVED = "group_track_removed"
        const val KEY_GROUP_ID = "group_id"
        private const val ARG_GROUP = "group"
        private const val ARG_PRELOADED_TRACKERS = "arg_preloaded_trackers"

        fun newInstance(group: Group, preloadedTrackers: List<Tracker>? = null): GroupTrackersListFragment {
            return GroupTrackersListFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_GROUP, group)
                    if (!preloadedTrackers.isNullOrEmpty()) {
                        putParcelableArrayList(ARG_PRELOADED_TRACKERS, ArrayList(preloadedTrackers))
                    }
                }
            }
        }
    }
}

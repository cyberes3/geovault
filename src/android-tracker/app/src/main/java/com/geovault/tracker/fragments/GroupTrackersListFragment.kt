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
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.parseHexToColor

class GroupTrackersListFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var addButton: com.google.android.material.button.MaterialButton

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
        view.findViewById<View>(R.id.groupTrackersListCloseButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        group = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        if (group == null) {
            parentFragmentManager.popBackStack()
            return
        }
        arguments?.getParcelableArrayList(ARG_PRELOADED_TRACKERS, Tracker::class.java)?.let {
            preloadedAddableTrackers = it
        }
        view.findViewById<TextView>(R.id.groupTrackersListTitle).text = getString(R.string.group_tracks)
        addButton.visibility = if (group?.is_owner == true) View.VISIBLE else View.GONE
        // Render immediately from passed-in group + cache to avoid initial pop-in.
        bindTrackers(group!!)
        addButton.setOnClickListener {
            group?.let { g ->
                parentFragmentManager.beginTransaction()
                    .add(
                        R.id.fragment_overlay_container,
                        AddGroupTrackersFragment.newInstance(
                            g.id,
                            g.track_ids ?: emptyList(),
                            preloadedAddableTrackers
                        ),
                        "add_group_trackers"
                    )
                    .addToBackStack(null)
                    .commit()
            }
        }
        parentFragmentManager.setFragmentResultListener(
            AddGroupTrackersFragment.REQUEST_GROUP_TRACK_ADDED,
            viewLifecycleOwner
        ) { _, bundle ->
            val groupId = bundle.getString(AddGroupTrackersFragment.KEY_GROUP_ID) ?: return@setFragmentResultListener
            if (groupId != group?.id) return@setFragmentResultListener
            loadGroupAndBind(groupId)
        }
        loadGroupAndBind(group!!.id)
        if (preloadedAddableTrackers == null) preloadAddableTrackers()
    }

    private fun preloadAddableTrackers() {
        val g = group ?: return
        val alreadyInGroup = (g.track_ids ?: emptyList()).toSet()
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val addable = (list ?: emptyList())
                .filter { tracker ->
                    tracker.id !in alreadyInGroup &&
                        if (tracker.isOwner()) {
                            ((tracker.settings?.get("hidden_in_list") as? Boolean) != true)
                        } else {
                            ((tracker.settings?.get("allow_group_reshare") as? Boolean) == true) &&
                                tracker.visibility == "public"
                        }
                }
            requireActivity().runOnUiThread {
                preloadedAddableTrackers = addable
            }
        }
    }

    private fun loadGroupAndBind(groupId: String) {
        TrackerRepository.getGroup(requireContext(), groupId) { g ->
            if (!isAdded) return@getGroup
            requireActivity().runOnUiThread {
                group = g
                if (g != null) {
                    view?.findViewById<TextView>(R.id.groupTrackersListTitle)?.text = getString(R.string.group_tracks)
                    addButton.visibility = if (g.is_owner == true) View.VISIBLE else View.GONE
                    bindTrackers(g)
                }
            }
        }
    }

    private fun bindTrackers(g: Group) {
        val trackIds = g.track_ids ?: emptyList()
        val isOwner = g.is_owner == true
        if (trackIds.isEmpty()) {
            listContainer.removeAllViews()
            emptyView.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            return
        }

        fun renderFrom(allTrackers: List<Tracker>) {
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

        // Use cache first for instant display if available.
        TrackerRepository.getTrackersCache()?.let { cached ->
            renderFrom(cached)
        }

        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            requireActivity().runOnUiThread {
                renderFrom(list ?: emptyList())
            }
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
        val removeBtn = card.findViewById<ImageButton>(R.id.groupTrackerRemove)
        val removeSpinner = card.findViewById<LoadingSpinner>(R.id.groupTrackerRemoveSpinner)
        menuBtn.visibility = View.GONE
        if (isOwner) {
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
            removeBtn.visibility = View.GONE
            removeSpinner.stop(hide = true)
            removeSpinner.visibility = View.GONE
        }
        card.isClickable = true
        card.setOnClickListener {
            (activity as? MainActivity)?.openMapForGroup(g, tracker.id)
        }
        listContainer.addView(card)
    }

    private fun removeTrack(groupId: String, trackId: String, onDone: (Boolean) -> Unit) {
        TrackerRepository.removeGroupTrack(requireContext(), groupId, trackId) { success ->
            if (!isAdded) return@removeGroupTrack
            requireActivity().runOnUiThread {
                removingTrackIds.remove(trackId)
                if (success) {
                    parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                    parentFragmentManager.setFragmentResult(
                        REQUEST_GROUP_TRACK_REMOVED,
                        Bundle().apply { putString(KEY_GROUP_ID, groupId) }
                    )
                    loadGroupAndBind(groupId)
                } else {
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
                onDone(success)
            }
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

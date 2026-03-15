package com.geovault.tracker.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository

class AddGroupTrackersFragment : Fragment() {
    private enum class RowState { IDLE, ADDING }
    private data class AddableTrack(val tracker: Tracker) {
        val id: String get() = tracker.id
        val name: String get() = tracker.name
        val ownerEmail: String get() = tracker.owner_email?.takeIf { it.isNotBlank() } ?: ""
    }

    private lateinit var loadingView: View
    private lateinit var spinner: LoadingSpinner
    private lateinit var emptyView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText

    private var allItems: List<AddableTrack> = emptyList()
    private val rowStates = mutableMapOf<String, RowState>()
    private var query: String = ""
    private val existingTrackIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existingTrackIds.addAll(arguments?.getStringArrayList(ARG_EXISTING_TRACK_IDS) ?: emptyList())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_add_group_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingView = view.findViewById(R.id.addGroupTrackersLoading)
        spinner = view.findViewById(R.id.addGroupTrackersSpinner)
        emptyView = view.findViewById(R.id.addGroupTrackersEmpty)
        listContainer = view.findViewById(R.id.addGroupTrackersList)
        searchInput = view.findViewById(R.id.addGroupTrackersSearch)

        view.findViewById<View>(R.id.addGroupTrackersCloseButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                renderList()
            }
        })

        val preloaded = arguments?.getParcelableArrayList(ARG_PRELOADED_TRACKERS, Tracker::class.java)
        if (!preloaded.isNullOrEmpty()) {
            allItems = preloaded
                .filter { it.id !in existingTrackIds }
                .filter { tracker -> canShowInAddList(tracker) }
                .map { AddableTrack(it) }
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() }
            loadingView.visibility = View.GONE
            spinner.stop(hide = true)
            renderList()
        } else {
            loadCandidates()
        }
    }

    private fun loadCandidates() {
        loadingView.visibility = View.VISIBLE
        spinner.start()
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val addable = (list ?: emptyList())
                .filter { tracker ->
                    tracker.id !in existingTrackIds && canShowInAddList(tracker)
                }
                .map { tracker ->
                    AddableTrack(tracker)
                }
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() }
            requireActivity().runOnUiThread {
                allItems = addable
                spinner.stop(hide = true)
                loadingView.visibility = View.GONE
                renderList()
            }
        }
    }

    private fun renderList() {
        val normalized = query.trim().lowercase()
        val filtered = allItems.filter { item ->
            item.id !in existingTrackIds && (
                normalized.isBlank() ||
                    item.name.lowercase().contains(normalized) ||
                    item.ownerEmail.lowercase().contains(normalized)
                )
        }
        listContainer.removeAllViews()
        listContainer.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        for (item in filtered) {
            val row = layoutInflater.inflate(R.layout.item_add, listContainer, false)
            val typeIcon = row.findViewById<ImageView>(R.id.availableTrackerTypeIcon)
            typeIcon.setImageResource(R.drawable.ic_chevron_track)
            typeIcon.setColorFilter(requireContext().getColor(R.color.primary_blue))
            row.findViewById<TextView>(R.id.availableTrackerName).text = item.name
            val ownerView = row.findViewById<TextView>(R.id.availableTrackerOwner)
            if (item.tracker.isOwner()) {
                ownerView.visibility = View.GONE
            } else {
                ownerView.visibility = View.VISIBLE
                ownerView.text = item.ownerEmail
            }
            val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
            val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
            val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
            val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
            checkBtn.visibility = View.GONE
            deleteBtn.visibility = View.GONE

            fun applyRowState(state: RowState) {
                rowStates[item.id] = state
                addBtn.visibility = if (state == RowState.IDLE) View.VISIBLE else View.GONE
                spinnerView.visibility = if (state == RowState.ADDING) View.VISIBLE else View.GONE
                if (state == RowState.ADDING) spinnerView.start() else spinnerView.stop(hide = true)
            }

            applyRowState(rowStates[item.id] ?: RowState.IDLE)
            addBtn.setOnClickListener {
                if (rowStates[item.id] == RowState.ADDING || item.id in existingTrackIds) return@setOnClickListener
                applyRowState(RowState.ADDING)
                TrackerRepository.addGroupTrack(requireContext(), requireGroupId(), item.id) callback@{ updated, errorMessage ->
                    if (!isAdded) return@callback
                    requireActivity().runOnUiThread {
                        if (updated != null) {
                            existingTrackIds.add(item.id)
                            rowStates.remove(item.id)
                            parentFragmentManager.setFragmentResult(
                                REQUEST_GROUP_TRACK_ADDED,
                                Bundle().apply {
                                    putString(KEY_GROUP_ID, requireGroupId())
                                    putString(KEY_TRACK_ID, item.id)
                                    putParcelable(KEY_TRACKER, item.tracker)
                                }
                            )
                            parentFragmentManager.setFragmentResult(
                                REQUEST_GROUP_TRACK_ADDED_LIST,
                                Bundle().apply {
                                    putString(KEY_GROUP_ID, requireGroupId())
                                }
                            )
                            parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                            renderList()
                        } else {
                            applyRowState(RowState.IDLE)
                            (activity as? MainActivity)?.showSnackbar(
                                errorMessage?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.failed_to_load_tracker)
                            )
                        }
                    }
                }
            }
            listContainer.addView(row)
        }
    }

    /**
     * Keep Add Trackers list aligned with backend group_add_track eligibility to avoid 403s.
     * For non-owner tracks we only show public + allow_group_reshare candidates.
     */
    private fun canShowInAddList(tracker: Tracker): Boolean {
        if (tracker.isOwner()) {
            return (tracker.settings?.get("hidden_in_list") as? Boolean) != true
        }
        val allowReshare = (tracker.settings?.get("allow_group_reshare") as? Boolean) == true
        val isPublic = tracker.visibility == "public"
        return allowReshare && isPublic
    }

    private fun requireGroupId(): String {
        return requireArguments().getString(ARG_GROUP_ID)
            ?: error("Missing group id")
    }

    companion object {
        const val REQUEST_GROUP_TRACK_ADDED = "group_track_added"
        const val REQUEST_GROUP_TRACK_ADDED_LIST = "group_track_added_list"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TRACKER = "tracker"
        private const val ARG_GROUP_ID = "arg_group_id"
        private const val ARG_EXISTING_TRACK_IDS = "arg_existing_track_ids"
        private const val ARG_PRELOADED_TRACKERS = "arg_preloaded_trackers"

        fun newInstance(
            groupId: String,
            existingTrackIds: List<String>,
            preloadedTrackers: List<Tracker>? = null
        ): AddGroupTrackersFragment {
            return AddGroupTrackersFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putStringArrayList(ARG_EXISTING_TRACK_IDS, ArrayList(existingTrackIds))
                    if (!preloadedTrackers.isNullOrEmpty()) {
                        putParcelableArrayList(ARG_PRELOADED_TRACKERS, ArrayList(preloadedTrackers))
                    }
                }
            }
        }
    }
}

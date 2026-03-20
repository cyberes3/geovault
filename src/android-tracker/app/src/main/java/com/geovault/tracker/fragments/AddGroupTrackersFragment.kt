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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.common.LoadingSpinner
import com.geovault.common.NaturalSort
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddGroupTrackersFragment : Fragment() {
    private val viewModel: AddGroupTrackersViewModel by viewModels()
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
    private var isLoading: Boolean = true
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    isLoading = state.isLoading
                    if (state.isLoading) {
                        loadingView.visibility = View.VISIBLE
                        spinner.start()
                    } else {
                        loadingView.visibility = View.GONE
                        spinner.stop(hide = true)
                    }
                    allItems = state.candidates
                        .filter { it.id !in existingTrackIds }
                        .map { AddableTrack(it) }
                        .distinctBy { it.id }
                        .sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
                    renderList()
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let { navHost()?.showSnackbar(it) }
                }
            }
        }
        viewModel.load(requireGroupId())
    }

    private fun renderList() {
        if (isLoading) {
            emptyView.visibility = View.GONE
            if (allItems.isEmpty()) {
                listContainer.visibility = View.GONE
            }
            return
        }

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
                viewModel.addTracker(requireGroupId(), item.id) { success ->
                    if (!isAdded) return@addTracker
                    if (success) {
                        existingTrackIds.add(item.id)
                        rowStates.remove(item.id)
                        renderList()
                    } else {
                        applyRowState(RowState.IDLE)
                    }
                }
            }
            listContainer.addView(row)
        }
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
        const val KEY_GROUP = "group"
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

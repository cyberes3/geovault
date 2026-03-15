package com.geovault.tracker.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class PublicTrackersFragment : Fragment() {

    private enum class RowState { IDLE, ADDING, ADDED_CHECK, ADDED_DELETE }

    companion object {
        private const val CHECK_DISPLAY_MS = 2500L
    }

    private lateinit var loadingView: View
    private lateinit var spinner: LoadingSpinner
    private lateinit var emptyView: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText

    private val rowStates = mutableMapOf<String, RowState>()
    private val handler = Handler(Looper.getMainLooper())
    private val transitionRunnables = mutableMapOf<String, Runnable>()
    private var publicTrackersData: List<AvailableToAddItem> = emptyList()
    private var publicGroupsData: List<AvailableToAddGroup> = emptyList()
    private var searchQuery: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_public_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingView = view.findViewById(R.id.publicTrackersLoading)
        spinner = view.findViewById(R.id.publicTrackersSpinner)
        emptyView = view.findViewById(R.id.publicTrackersEmpty)
        swipeRefresh = view.findViewById(R.id.publicTrackersSwipeRefresh)
        listContainer = view.findViewById(R.id.publicTrackersList)
        searchInput = view.findViewById(R.id.publicTrackersSearch)

        view.findViewById<View>(R.id.publicTrackersCloseButton).setOnClickListener { parentFragmentManager.popBackStack() }
        swipeRefresh.setOnRefreshListener {
            loadingView.visibility = View.VISIBLE
            spinner.start()
            loadPublic(forceRefresh = true, fromSwipeRefresh = false)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                renderList()
            }
        })

        loadPublic(forceRefresh = false, fromSwipeRefresh = false)
    }

    override fun onDestroyView() {
        transitionRunnables.values.forEach { handler.removeCallbacks(it) }
        transitionRunnables.clear()
        super.onDestroyView()
    }

    private fun loadPublic(forceRefresh: Boolean, fromSwipeRefresh: Boolean) {
        TrackerRepository.getAvailableToAdd(requireContext(), forceRefresh = forceRefresh) { response ->
            if (!isAdded) return@getAvailableToAdd
            if (response == null) {
                requireActivity().runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    spinner.stop(hide = true)
                    loadingView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                }
                return@getAvailableToAdd
            }
            requireActivity().runOnUiThread {
                swipeRefresh.isRefreshing = false
                if (!fromSwipeRefresh) {
                    spinner.stop(hide = true)
                    loadingView.visibility = View.GONE
                }
                publicTrackersData = response.public ?: emptyList()
                publicGroupsData = response.public_groups ?: emptyList()
                val hasContent = publicTrackersData.isNotEmpty() || publicGroupsData.isNotEmpty()
                emptyView.visibility = if (hasContent) View.GONE else View.VISIBLE
                listContainer.visibility = if (hasContent) View.VISIBLE else View.GONE
                renderList()
            }
        }
    }

    private fun renderList() {
        val q = searchQuery.trim().lowercase()
        fun itemMatches(item: AvailableToAddItem): Boolean {
            if (q.isBlank()) return true
            val owner = item.owner_email ?: ""
            return item.name.lowercase().contains(q) || owner.lowercase().contains(q)
        }
        fun groupMatches(group: AvailableToAddGroup): Boolean {
            if (q.isBlank()) return true
            val owner = group.owner_email ?: ""
            return group.name.lowercase().contains(q) || owner.lowercase().contains(q)
        }
        val trackers = publicTrackersData.filter { itemMatches(it) }
        val groups = publicGroupsData.filter { groupMatches(it) }

        listContainer.removeAllViews()
        for (item in trackers) {
            addTrackerRow(listContainer, item)
        }
        for (group in groups) {
            addGroupRow(listContainer, group)
        }
    }

    private fun setRowState(row: View, key: String, state: RowState) {
        rowStates[key] = state
        val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
        val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
        val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
        val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
        when (state) {
            RowState.IDLE -> {
                addBtn.visibility = View.VISIBLE
                spinnerView.visibility = View.GONE
                spinnerView.stop(hide = true)
                checkBtn.visibility = View.GONE
                deleteBtn.visibility = View.GONE
            }
            RowState.ADDING -> {
                addBtn.visibility = View.GONE
                spinnerView.visibility = View.VISIBLE
                spinnerView.start()
                checkBtn.visibility = View.GONE
                deleteBtn.visibility = View.GONE
            }
            RowState.ADDED_CHECK -> {
                addBtn.visibility = View.GONE
                spinnerView.visibility = View.GONE
                spinnerView.stop(hide = true)
                checkBtn.visibility = View.VISIBLE
                deleteBtn.visibility = View.GONE
            }
            RowState.ADDED_DELETE -> {
                addBtn.visibility = View.GONE
                spinnerView.visibility = View.GONE
                spinnerView.stop(hide = true)
                checkBtn.visibility = View.GONE
                deleteBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun transitionToDeleteAfterCheck(row: View, key: String) {
        transitionRunnables.remove(key)?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!isAdded) return@Runnable
            transitionRunnables.remove(key)
            setRowState(row, key, RowState.ADDED_DELETE)
        }
        transitionRunnables[key] = runnable
        handler.postDelayed(runnable, CHECK_DISPLAY_MS)
    }

    private fun notifySharedTabAdded(trackers: List<Tracker>, groups: List<Group>) {
        if (trackers.isEmpty() && groups.isEmpty()) return
        val bundle = Bundle().apply {
            if (trackers.isNotEmpty()) putParcelableArrayList("trackers", ArrayList(trackers))
            if (groups.isNotEmpty()) putParcelableArrayList("groups", ArrayList(groups))
        }
        parentFragmentManager.setFragmentResult(SharedTrackersFragment.REQUEST_ADD_SHARED_ITEMS, bundle)
    }

    private fun addTrackerRow(parent: LinearLayout, item: AvailableToAddItem) {
        val row = layoutInflater.inflate(R.layout.item_add, parent, false)
        val typeIcon = row.findViewById<ImageView>(R.id.availableTrackerTypeIcon)
        typeIcon.setImageResource(R.drawable.ic_chevron_track)
        typeIcon.setColorFilter(requireContext().getColor(R.color.primary_blue))
        row.findViewById<TextView>(R.id.availableTrackerName).text = item.name
        row.findViewById<TextView>(R.id.availableTrackerOwner).text =
            item.owner_email?.takeIf { it.isNotBlank() } ?: ""
        val key = "t:${item.id}"
        val initialState = rowStates[key] ?: RowState.IDLE
        rowStates[key] = initialState
        val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
        val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
        val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
        val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
        addBtn.setOnClickListener {
            if (rowStates[key] != RowState.IDLE) return@setOnClickListener
            setRowState(row, key, RowState.ADDING)
            TrackerRepository.subscribeTracker(requireContext(), item.id) { tracker ->
                if (!isAdded) return@subscribeTracker
                requireActivity().runOnUiThread {
                    if (tracker != null) {
                        setRowState(row, key, RowState.ADDED_CHECK)
                        transitionToDeleteAfterCheck(row, key)
                        notifySharedTabAdded(listOf(tracker), emptyList())
                        parentFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, Bundle())
                    } else {
                        setRowState(row, key, RowState.IDLE)
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
        deleteBtn.setOnClickListener {
            if (rowStates[key] != RowState.ADDED_DELETE) return@setOnClickListener
            TrackerRepository.unsubscribeTracker(requireContext(), item.id) { success ->
                if (!isAdded) return@unsubscribeTracker
                requireActivity().runOnUiThread {
                    if (success) {
                        publicTrackersData = publicTrackersData.filter { it.id != item.id }
                        rowStates.remove(key)
                        transitionRunnables.remove(key)?.let { handler.removeCallbacks(it) }
                        parent.removeView(row)
                        parentFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, Bundle())
                    } else {
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
        setRowState(row, key, initialState)
        parent.addView(row)
    }

    private fun addGroupRow(parent: LinearLayout, group: AvailableToAddGroup) {
        val row = layoutInflater.inflate(R.layout.item_add, parent, false)
        val typeIcon = row.findViewById<ImageView>(R.id.availableTrackerTypeIcon)
        typeIcon.setImageResource(R.drawable.ic_groups)
        typeIcon.setColorFilter(requireContext().getColor(R.color.text_secondary))
        row.findViewById<TextView>(R.id.availableTrackerName).text = group.name
        row.findViewById<TextView>(R.id.availableTrackerOwner).text =
            (group.owner_email?.takeIf { it.isNotBlank() } ?: "") + " (group)"
        val key = "g:${group.id}"
        val initialState = rowStates[key] ?: RowState.IDLE
        rowStates[key] = initialState
        val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
        val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
        val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
        val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
        addBtn.setOnClickListener {
            if (rowStates[key] != RowState.IDLE) return@setOnClickListener
            setRowState(row, key, RowState.ADDING)
            val trackIds = group.track_ids ?: emptyList()
            if (trackIds.isEmpty()) {
                setRowState(row, key, RowState.IDLE)
                (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                return@setOnClickListener
            }
            var failed = false
            var done = 0
            val addedTrackers = mutableListOf<Tracker>()
            for (trackId in trackIds) {
                TrackerRepository.subscribeTracker(requireContext(), trackId) { tracker ->
                    if (!isAdded) return@subscribeTracker
                    requireActivity().runOnUiThread {
                        done++
                        if (tracker == null) failed = true else addedTrackers.add(tracker)
                        if (done == trackIds.size) {
                            if (!failed) {
                                setRowState(row, key, RowState.ADDED_CHECK)
                                transitionToDeleteAfterCheck(row, key)
                                notifySharedTabAdded(addedTrackers, emptyList())
                                parentFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, Bundle())
                            } else {
                                setRowState(row, key, RowState.IDLE)
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                            }
                        }
                    }
                }
            }
        }
        deleteBtn.setOnClickListener {
            if (rowStates[key] != RowState.ADDED_DELETE) return@setOnClickListener
            TrackerRepository.leaveGroup(requireContext(), group.id) { success ->
                if (!isAdded) return@leaveGroup
                requireActivity().runOnUiThread {
                    if (success) {
                        publicGroupsData = publicGroupsData.filter { it.id != group.id }
                        rowStates.remove(key)
                        transitionRunnables.remove(key)?.let { handler.removeCallbacks(it) }
                        parent.removeView(row)
                        parentFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, Bundle())
                    } else {
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
        setRowState(row, key, initialState)
        parent.addView(row)
    }
}

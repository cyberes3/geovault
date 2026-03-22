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
import androidx.lifecycle.lifecycleScope
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
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

    @Inject
    lateinit var trackerManagementRepository: TrackerManagementRepository

    @Inject
    lateinit var groupManagementRepository: GroupManagementRepository

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
        if (!fromSwipeRefresh) {
            loadingView.visibility = View.VISIBLE
            spinner.start()
            emptyView.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val response = when (val result = trackerManagementRepository.loadAvailableToAdd(forceRefresh = forceRefresh)) {
                is RepositoryResult.Success -> result.data
                is RepositoryResult.Failure -> null
            }
            if (!isAdded) return@launch
            swipeRefresh.isRefreshing = false
            if (response == null) {
                spinner.stop(hide = true)
                loadingView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                listContainer.visibility = View.GONE
                return@launch
            }
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
            viewLifecycleOwner.lifecycleScope.launch {
                val tracker = when (val result = trackerManagementRepository.subscribeTracker(item.id)) {
                    is RepositoryResult.Success -> result.data
                    is RepositoryResult.Failure -> null
                }
                if (!isAdded) return@launch
                if (tracker != null) {
                    setRowState(row, key, RowState.ADDED_CHECK)
                    transitionToDeleteAfterCheck(row, key)
                } else {
                    setRowState(row, key, RowState.IDLE)
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
            }
        }
        deleteBtn.setOnClickListener {
            if (rowStates[key] != RowState.ADDED_DELETE) return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                val result = trackerManagementRepository.unsubscribeTracker(item.id)
                if (!isAdded) return@launch
                if (result is RepositoryResult.Success) {
                    publicTrackersData = publicTrackersData.filter { it.id != item.id }
                    rowStates.remove(key)
                    transitionRunnables.remove(key)?.let { handler.removeCallbacks(it) }
                    parent.removeView(row)
                } else {
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
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
            group.owner_email?.takeIf { it.isNotBlank() } ?: ""
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
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val addedTrackers = mutableListOf<Tracker>()
                var failed = false
                for (trackId in trackIds) {
                    when (val result = trackerManagementRepository.subscribeTracker(trackId)) {
                        is RepositoryResult.Success -> addedTrackers.add(result.data)
                        is RepositoryResult.Failure -> failed = true
                    }
                }
                if (!isAdded) return@launch
                if (!failed) {
                    setRowState(row, key, RowState.ADDED_CHECK)
                    transitionToDeleteAfterCheck(row, key)
                } else {
                    setRowState(row, key, RowState.IDLE)
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
            }
        }
        deleteBtn.setOnClickListener {
            if (rowStates[key] != RowState.ADDED_DELETE) return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                val result = groupManagementRepository.leaveGroup(group.id)
                if (!isAdded) return@launch
                if (result is RepositoryResult.Success) {
                    publicGroupsData = publicGroupsData.filter { it.id != group.id }
                    rowStates.remove(key)
                    transitionRunnables.remove(key)?.let { handler.removeCallbacks(it) }
                    parent.removeView(row)
                } else {
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
            }
        }
        setRowState(row, key, initialState)
        parent.addView(row)
    }
}

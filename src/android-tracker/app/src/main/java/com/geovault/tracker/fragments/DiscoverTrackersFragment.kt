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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.navigation.navHost
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DiscoverTrackersFragment : Fragment() {
    private val viewModel: DiscoverTrackersViewModel by viewModels()

    private enum class RowState { IDLE, ADDING, ADDED_CHECK, ADDED_DELETE }

    companion object {
        private const val CHECK_DISPLAY_MS = 2500L
    }

    private lateinit var loadingView: View
    private lateinit var spinner: LoadingSpinner
    private lateinit var emptyView: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var viewPager: ViewPager2
    private var pagerAdapter: DiscoverPagerAdapter? = null

    private val rowStates = mutableMapOf<String, RowState>()
    private val handler = Handler(Looper.getMainLooper())
    private val transitionRunnables = mutableMapOf<String, Runnable>()
    private var onMyMapTrackersData: List<AvailableToAddItem> = emptyList()
    private var onMyMapGroupsData: List<AvailableToAddGroup> = emptyList()
    private var incomingTrackersData: List<AvailableToAddItem> = emptyList()
    private var incomingSharedGroupsData: List<AvailableToAddGroup> = emptyList()
    private var onMyMapQuery: String = ""
    private var incomingQuery: String = ""
    private var onMyMapSearchInput: EditText? = null
    private var incomingSearchInput: EditText? = null

    @Inject
    lateinit var trackerManagementRepository: TrackerManagementRepository

    @Inject
    lateinit var groupManagementRepository: GroupManagementRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_discover_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadingView = view.findViewById(R.id.discoverLoading)
        spinner = view.findViewById(R.id.discoverSpinner)
        emptyView = view.findViewById(R.id.discoverEmpty)
        tabLayout = view.findViewById(R.id.discoverTabLayout)
        swipeRefresh = view.findViewById(R.id.discoverSwipeRefresh)
        viewPager = view.findViewById(R.id.discoverViewPager)

        view.findViewById<View>(R.id.discoverCloseButton).setOnClickListener { parentFragmentManager.popBackStack() }
        swipeRefresh.setOnRefreshListener {
            loadingView.visibility = View.VISIBLE
            spinner.start()
            viewModel.load(forceRefresh = true)
        }

        pagerAdapter = DiscoverPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = 1
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.discover_tab_on_my_map) else getString(R.string.discover_tab_incoming)
        }.attach()
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val hadQuery = onMyMapQuery.isNotBlank() || incomingQuery.isNotBlank()
                onMyMapQuery = ""
                incomingQuery = ""
                onMyMapSearchInput?.setText("")
                incomingSearchInput?.setText("")
                if (hadQuery) {
                    val onMyMap = pagerAdapter?.getPageView(0)
                    val incoming = pagerAdapter?.getPageView(1)
                    if (onMyMap != null && incoming != null) renderTabContent(onMyMap, incoming)
                }
            }
        })

        tabLayout.visibility = View.GONE
        swipeRefresh.visibility = View.GONE
        spinner.start()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    applyLoadedState(state)
                }
            }
        }
        viewModel.load(forceRefresh = false)
    }

    override fun onDestroyView() {
        transitionRunnables.values.forEach { handler.removeCallbacks(it) }
        transitionRunnables.clear()
        onMyMapSearchInput = null
        incomingSearchInput = null
        super.onDestroyView()
    }

    private fun applyLoadedState(state: DiscoverTrackersUiState) {
        if (!isAdded) return
        swipeRefresh.isRefreshing = false
        pagerAdapter?.getPageView(1)?.findViewById<SwipeRefreshLayout>(R.id.discoverIncomingSwipeRefresh)?.isRefreshing = false
        if (state.isLoading) {
            loadingView.visibility = View.VISIBLE
            spinner.start()
            return
        }
        spinner.stop(hide = true)
        loadingView.visibility = View.GONE
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { navHost()?.showSnackbar(it) }

        onMyMapTrackersData = state.onMyMapTrackers
        onMyMapGroupsData = state.onMyMapGroups
        incomingTrackersData = state.incomingTrackers
        incomingSharedGroupsData = state.incomingSharedGroups
        pruneTransientState()
        val onMyMapHasContent = onMyMapTrackersData.isNotEmpty() || onMyMapGroupsData.isNotEmpty()
        val incomingHasContent = incomingTrackersData.isNotEmpty() || incomingSharedGroupsData.isNotEmpty()
        if (!onMyMapHasContent && !incomingHasContent) {
            emptyView.visibility = View.VISIBLE
            tabLayout.visibility = View.GONE
            swipeRefresh.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        tabLayout.visibility = View.VISIBLE
        swipeRefresh.visibility = View.VISIBLE
        val currentTab = viewPager.currentItem.coerceIn(0, 1)
        val tabToSelect = when {
            !onMyMapHasContent && incomingHasContent -> 1
            onMyMapHasContent && !incomingHasContent -> 0
            else -> currentTab
        }
        viewPager.setCurrentItem(tabToSelect, false)
        viewPager.post {
            if (!isAdded) return@post
            val onMyMapPage = pagerAdapter?.getPageView(0)
            val incomingPage = pagerAdapter?.getPageView(1)
            if (onMyMapPage == null || incomingPage == null) return@post
            bindSearchInputs(onMyMapPage, incomingPage)
            renderTabContent(onMyMapPage, incomingPage)
        }
    }

    private fun pruneTransientState() {
        val validKeys = mutableSetOf<String>()
        validKeys.addAll(onMyMapTrackersData.map { "t:${it.id}" })
        validKeys.addAll(incomingTrackersData.map { "t:${it.id}" })
        validKeys.addAll(onMyMapGroupsData.map { "g:${it.id}" })
        validKeys.addAll(incomingSharedGroupsData.map { "g:${it.id}" })
        rowStates.keys.retainAll(validKeys)
        val staleTransitions = transitionRunnables.keys.filter { it !in validKeys }
        for (key in staleTransitions) {
            transitionRunnables.remove(key)?.let { handler.removeCallbacks(it) }
        }
    }

    private fun bindSearchInputs(onMyMapPage: View, incomingPage: View) {
        val onMyMapSearch = onMyMapPage.findViewById<EditText>(R.id.discoverOnMyMapSearch)
        if (onMyMapSearchInput !== onMyMapSearch) {
            onMyMapSearchInput = onMyMapSearch
            onMyMapSearch.setText(onMyMapQuery)
            onMyMapSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val next = s?.toString() ?: ""
                    if (onMyMapQuery == next) return
                    onMyMapQuery = next
                    val onMyMap = pagerAdapter?.getPageView(0)
                    val incoming = pagerAdapter?.getPageView(1)
                    if (onMyMap != null && incoming != null) renderTabContent(onMyMap, incoming)
                }
            })
        }
        val incomingSearch = incomingPage.findViewById<EditText>(R.id.discoverIncomingSearch)
        if (incomingSearchInput !== incomingSearch) {
            incomingSearchInput = incomingSearch
            incomingSearch.setText(incomingQuery)
            incomingSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val next = s?.toString() ?: ""
                    if (incomingQuery == next) return
                    incomingQuery = next
                    val onMyMap = pagerAdapter?.getPageView(0)
                    val incoming = pagerAdapter?.getPageView(1)
                    if (onMyMap != null && incoming != null) renderTabContent(onMyMap, incoming)
                }
            })
        }
        val incomingSwipeRefresh = incomingPage.findViewById<SwipeRefreshLayout>(R.id.discoverIncomingSwipeRefresh)
        incomingSwipeRefresh?.setOnRefreshListener {
            viewModel.load(forceRefresh = true)
        }
    }

    private fun renderTabContent(onMyMapPage: View, incomingPage: View) {
        val onMapQuery = onMyMapQuery.trim().lowercase()
        val incomingQueryNormalized = incomingQuery.trim().lowercase()

        fun itemMatches(item: AvailableToAddItem, q: String): Boolean {
            if (q.isBlank()) return true
            val owner = item.owner_email ?: ""
            return item.name.lowercase().contains(q) || owner.lowercase().contains(q)
        }
        fun groupMatches(group: AvailableToAddGroup, q: String): Boolean {
            if (q.isBlank()) return true
            val owner = group.owner_email ?: ""
            return group.name.lowercase().contains(q) || owner.lowercase().contains(q)
        }

        val onMyMapTrackers = onMyMapTrackersData.filter { itemMatches(it, onMapQuery) }
        val onMyMapGroups = onMyMapGroupsData.filter { groupMatches(it, onMapQuery) }
        val incomingTrackers = incomingTrackersData.filter { itemMatches(it, incomingQueryNormalized) }
        val incomingSharedGroups = incomingSharedGroupsData.filter { groupMatches(it, incomingQueryNormalized) }

        val publicList = onMyMapPage.findViewById<LinearLayout>(R.id.discoverPublicList)
        val publicGroupsList = onMyMapPage.findViewById<LinearLayout>(R.id.discoverPublicGroupsList)
        val onMyMapEmpty = onMyMapPage.findViewById<TextView>(R.id.discoverOnMyMapEmpty)
        val incomingList = incomingPage.findViewById<LinearLayout>(R.id.discoverIncomingList)
        val incomingEmpty = incomingPage.findViewById<TextView>(R.id.discoverIncomingEmpty)

        publicList.removeAllViews()
        publicGroupsList.removeAllViews()
        incomingList.removeAllViews()

        if (onMyMapTrackers.isNotEmpty()) {
            publicList.visibility = View.VISIBLE
            for (item in onMyMapTrackers) {
                addItemRow(publicList, item, isIncoming = false, sectionHeader = null)
            }
        } else {
            publicList.visibility = View.GONE
        }

        if (onMyMapGroups.isNotEmpty()) {
            publicGroupsList.visibility = View.VISIBLE
            for (group in onMyMapGroups) {
                addGroupRow(publicGroupsList, group, acceptAsGroup = false, sectionHeader = null, sectionList = publicGroupsList, initiallyAdded = true)
            }
        } else {
            publicGroupsList.visibility = View.GONE
        }

        if (incomingTrackers.isNotEmpty()) {
            incomingList.visibility = View.VISIBLE
            for (item in incomingTrackers) {
                addItemRow(incomingList, item, isIncoming = true, sectionHeader = null)
            }
        }

        if (incomingSharedGroups.isNotEmpty()) {
            incomingList.visibility = View.VISIBLE
            for (group in incomingSharedGroups) {
                addGroupRow(incomingList, group, acceptAsGroup = true, sectionHeader = null, sectionList = incomingList)
            }
        } else {
            if (incomingTrackers.isEmpty()) {
                incomingList.visibility = View.GONE
            }
        }

        val onMyMapHasContent = onMyMapTrackers.isNotEmpty() || onMyMapGroups.isNotEmpty()
        val incomingHasContent = incomingTrackers.isNotEmpty() || incomingSharedGroups.isNotEmpty()
        onMyMapEmpty.visibility = if (onMyMapHasContent) View.GONE else View.VISIBLE
        incomingEmpty.visibility = if (incomingHasContent) View.GONE else View.VISIBLE
    }

    private fun setRowState(row: View, key: String, state: RowState) {
        rowStates[key] = state
        val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
        val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
        val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
        val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
        addBtn.visibility = if (state == RowState.IDLE) View.VISIBLE else View.GONE
        spinnerView.visibility = if (state == RowState.ADDING) View.VISIBLE else View.GONE
        if (state == RowState.ADDING) spinnerView.start() else spinnerView.stop(hide = true)
        checkBtn.visibility = if (state == RowState.ADDED_CHECK) View.VISIBLE else View.GONE
        deleteBtn.visibility = if (state == RowState.ADDED_DELETE) View.VISIBLE else View.GONE
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

    /** Moves an incoming tracker into on-my-map data and removes it from incoming; no network call. */
    private fun moveIncomingTrackerToOnMyMap(item: AvailableToAddItem) {
        incomingTrackersData = incomingTrackersData.filter { it.id != item.id }
        if (onMyMapTrackersData.none { it.id == item.id }) {
            onMyMapTrackersData = onMyMapTrackersData + item
        }
        transitionRunnables.remove("t:${item.id}")?.let { handler.removeCallbacks(it) }
        rowStates["t:${item.id}"] = RowState.ADDED_DELETE
    }

    /** Moves an incoming group into on-my-map data and removes it from incoming; no network call. */
    private fun moveIncomingGroupToOnMyMap(group: AvailableToAddGroup, trackIds: List<String>) {
        val withTrackIds = AvailableToAddGroup(id = group.id, name = group.name, owner_email = group.owner_email, track_ids = trackIds)
        if (onMyMapGroupsData.none { it.id == group.id }) {
            onMyMapGroupsData = onMyMapGroupsData + withTrackIds
        }
        incomingSharedGroupsData = incomingSharedGroupsData.filter { it.id != group.id }
        transitionRunnables.remove("g:${group.id}")?.let { handler.removeCallbacks(it) }
        rowStates["g:${group.id}"] = RowState.ADDED_DELETE
    }

    private fun rerenderTabs() {
        val onMyMap = pagerAdapter?.getPageView(0)
        val incoming = pagerAdapter?.getPageView(1)
        if (onMyMap != null && incoming != null) renderTabContent(onMyMap, incoming)
    }

    private fun removeRowAndMaybeHideSection(parent: LinearLayout, row: View, header: TextView?, list: LinearLayout) {
        parent.removeView(row)
        if (parent.childCount == 0) {
            header?.visibility = View.GONE
            list.visibility = View.GONE
        }
    }

    private fun addGroupRow(
        parent: LinearLayout,
        group: AvailableToAddGroup,
        acceptAsGroup: Boolean,
        sectionHeader: TextView?,
        sectionList: LinearLayout,
        initiallyAdded: Boolean = false
    ) {
        val row = layoutInflater.inflate(R.layout.item_add, parent, false)
        val typeIcon = row.findViewById<ImageView>(R.id.availableTrackerTypeIcon)
        typeIcon.setImageResource(R.drawable.ic_groups)
        typeIcon.setColorFilter(requireContext().getColor(R.color.text_secondary))
        row.findViewById<TextView>(R.id.availableTrackerName).text = group.name
        row.findViewById<TextView>(R.id.availableTrackerOwner).text =
            (group.owner_email?.takeIf { it.isNotBlank() } ?: "") + " (group)"
        val key = "g:${group.id}"
        val initialState = rowStates[key] ?: if (initiallyAdded) RowState.ADDED_DELETE else RowState.IDLE
        rowStates[key] = initialState
        val addBtn = row.findViewById<ImageButton>(R.id.availableTrackerAdd)
        val spinnerView = row.findViewById<LoadingSpinner>(R.id.availableTrackerSpinner)
        val checkBtn = row.findViewById<ImageButton>(R.id.availableTrackerCheck)
        val deleteBtn = row.findViewById<ImageButton>(R.id.availableTrackerDelete)
        addBtn.setOnClickListener {
            if (rowStates[key] != RowState.IDLE) return@setOnClickListener
            if (!acceptAsGroup) return@setOnClickListener
            setRowState(row, key, RowState.ADDING)
            viewLifecycleOwner.lifecycleScope.launch {
                val accepted = when (val result = groupManagementRepository.acceptGroupShare(group.id)) {
                    is RepositoryResult.Success -> result.data
                    is RepositoryResult.Failure -> null
                }
                if (accepted != null) {
                    moveIncomingGroupToOnMyMap(group, accepted.track_ids ?: emptyList())
                    setRowState(row, key, RowState.ADDED_CHECK)
                    transitionToDeleteAfterCheck(row, key)
                    rerenderTabs()
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
                if (result is RepositoryResult.Success) {
                    removeRowAndMaybeHideSection(parent, row, sectionHeader, sectionList)
                } else {
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
            }
        }
        setRowState(row, key, initialState)
        parent.addView(row)
    }

    private fun addItemRow(parent: LinearLayout, item: AvailableToAddItem, isIncoming: Boolean, sectionHeader: TextView?) {
        val row = layoutInflater.inflate(R.layout.item_add, parent, false)
        val typeIcon = row.findViewById<ImageView>(R.id.availableTrackerTypeIcon)
        typeIcon.setImageResource(R.drawable.ic_chevron_track)
        typeIcon.setColorFilter(requireContext().getColor(R.color.primary_blue))
        row.findViewById<TextView>(R.id.availableTrackerName).text = item.name
        row.findViewById<TextView>(R.id.availableTrackerOwner).text =
            item.owner_email?.takeIf { it.isNotBlank() } ?: ""
        val key = "t:${item.id}"
        val initialState = rowStates[key] ?: if (isIncoming) RowState.IDLE else RowState.ADDED_DELETE
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
                if (tracker != null) {
                    moveIncomingTrackerToOnMyMap(item)
                    setRowState(row, key, RowState.ADDED_CHECK)
                    transitionToDeleteAfterCheck(row, key)
                    rerenderTabs()
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
                if (result is RepositoryResult.Success) {
                    removeRowAndMaybeHideSection(parent, row, sectionHeader, parent)
                } else {
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
            }
        }
        setRowState(row, key, initialState)
        parent.addView(row)
    }

    private class DiscoverPagerAdapter(fragment: Fragment) : RecyclerView.Adapter<DiscoverPagerAdapter.PageViewHolder>() {
        private val inflater = LayoutInflater.from(fragment.requireContext())
        private val pageViews = arrayOfNulls<View>(2)

        fun getPageView(position: Int): View? = pageViews.getOrNull(position)

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val layoutId = if (viewType == 0) R.layout.discover_page_on_my_map else R.layout.discover_page_incoming
            val view = inflater.inflate(layoutId, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            pageViews[position] = holder.itemView
        }

        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }
}

package com.geovault.tracker.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import android.util.DisplayMetrics
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.parseHexToColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedTrackersFragment : Fragment() {

    companion object {
        /** Pass added trackers/groups for optimistic update; use keys "trackers" and "groups" (ArrayList<Tracker>/ArrayList<Group>). */
        const val REQUEST_ADD_SHARED_ITEMS = "shared_add_items"
    }

    private lateinit var addFab: FloatingActionButton
    private lateinit var publicFab: FloatingActionButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private var adapter: SharedItemsAdapter? = null
    private var pendingScrollToTrackerId: String? = null
    private var pendingScrollToGroupId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_shared_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addFab = view.findViewById(R.id.sharedAddTrackerFab)
        publicFab = view.findViewById(R.id.sharedPublicTrackersFab)
        swipeRefresh = view.findViewById(R.id.sharedSwipeRefresh)
        recyclerView = view.findViewById(R.id.sharedRecyclerView)
        emptyView = view.findViewById(R.id.sharedEmpty)
        loadingOverlay = view.findViewById(R.id.sharedLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.sharedLoadingSpinner)

        addFab.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .add(R.id.fragment_overlay_container, DiscoverTrackersFragment(), "discover_trackers")
                .addToBackStack(null)
                .commit()
        }
        publicFab.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .add(R.id.fragment_overlay_container, PublicTrackersFragment(), "public_trackers")
                .addToBackStack(null)
                .commit()
        }
        swipeRefresh.setOnRefreshListener { loadTrackers() }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    adapter?.setHighlightedTrackerId(null)
                }
            }
        })
        adapter = SharedItemsAdapter(
            emptyList(),
            emptySet(),
            null,
            onTrackerAction = { tracker, action ->
                when (action) {
                    TrackerAction.EDIT -> (activity as? MainActivity)?.let { if (tracker.isOwner()) it.showEditTrackerFragment(tracker) else it.showEditSharedTrackerFragment(tracker) }
                    TrackerAction.VIEW_ON_MAP -> viewOnMap(tracker)
                    TrackerAction.VIEW_PARAMS -> (activity as? MainActivity)?.showTrackerParamsFragment(
                        tracker.id,
                        tracker.name,
                        lastUpdateMs = tracker.last_point?.let { c ->
                            if (c.size >= 3) {
                                val t = (c[2] as? Number)?.toLong() ?: return@let null
                                if (t < 1e12) t * 1000 else t
                            } else null
                        },
                        positionLat = tracker.last_point?.let { c -> if (c.size >= 2) (c[1] as? Number)?.toDouble() else null },
                        positionLon = tracker.last_point?.let { c -> if (c.size >= 2) (c[0] as? Number)?.toDouble() else null }
                    )
                }
            },
            onGroupCardClick = { group ->
                requireActivity().supportFragmentManager.beginTransaction()
                    .add(R.id.fragment_overlay_container, GroupActionsFragment.newInstance(group), "group_actions")
                    .addToBackStack(null)
                    .commit()
            },
            onGroupEditClick = { group ->
                (activity as? MainActivity)?.let { act ->
                    if (group.is_owner != true) {
                        act.showEditSharedGroupFragment(group)
                    } else {
                        requireActivity().supportFragmentManager.beginTransaction()
                            .add(R.id.fragment_overlay_container, GroupDetailBottomSheet.newInstance(group), "group_detail")
                            .addToBackStack("group_detail")
                            .commit()
                    }
                }
            }
        )
        recyclerView.adapter = adapter

        parentFragmentManager.setFragmentResultListener(TrackersListFragment.REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, _ ->
            loadTrackers()
        }
        parentFragmentManager.setFragmentResultListener(GroupsListFragment.REQUEST_GROUPS_REFRESH, viewLifecycleOwner) { _, _ ->
            loadTrackers()
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_ADD_SHARED_ITEMS, viewLifecycleOwner) { _, bundle ->
            addSharedItemsFromBundle(bundle)
        }

        loadTrackers()
    }

    override fun onPause() {
        super.onPause()
        adapter?.setHighlightedTrackerId(null)
    }

    /** Merges trackers/groups from Add Trackers into the current list (optimistic), then refetches in background. */
    private fun addSharedItemsFromBundle(bundle: Bundle) {
        val trackers = bundle.getParcelableArrayList("trackers", Tracker::class.java) ?: emptyList()
        val groups = bundle.getParcelableArrayList("groups", Group::class.java) ?: emptyList()
        if (trackers.isEmpty() && groups.isEmpty()) return
        val current = adapter?.getItems()?.toMutableList() ?: mutableListOf()
        val existingTrackerIds = current.filterIsInstance<SharedListItem.TrackerItem>().map { it.tracker.id }.toSet()
        val existingGroupIds = current.filterIsInstance<SharedListItem.GroupItem>().map { it.group.id }.toSet()
        for (t in trackers) if (t.id !in existingTrackerIds) current.add(SharedListItem.TrackerItem(t))
        for (g in groups) if (g.id !in existingGroupIds) current.add(SharedListItem.GroupItem(g))
        val sorted = current.sortedBy { it.sortName.lowercase(Locale.getDefault()) }
        adapter?.setItems(sorted, adapter?.getHiddenTrackIds() ?: emptySet())
        emptyView.visibility = View.GONE
        loadTrackers(showOverlay = false)
    }

    private fun loadTrackers(showOverlay: Boolean = true) {
        if (pendingScrollToTrackerId == null && pendingScrollToGroupId == null) {
            adapter?.setHighlightedTrackerId(null)
        }
        if (showOverlay) {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
        } else {
            loadingOverlay.visibility = View.GONE
            swipeRefresh.isRefreshing = false
        }
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
            val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
            TrackerRepository.getGroups(requireContext(), forceRefresh = true) { groups ->
                if (!isAdded) return@getGroups
                TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
                    if (!isAdded) return@getTrackers
                    requireActivity().runOnUiThread {
                        // Only show shared groups the user has accepted (via Discover -> Add / accept-share).
                        val sharedGroups = (groups ?: emptyList())
                            .filter { it.is_owner != true && it.visibility == "shared" && it.id !in hiddenGroupIds }
                            .filter { it.is_accepted == true }
                        val trackIdsInSharedGroups = sharedGroups
                            .flatMap { it.track_ids ?: emptyList() }
                            .toSet()
                        val sharedTrackers = (list ?: emptyList())
                            .filter { !it.isOwner() && (it.visibility == "shared" || it.visibility == "public") }
                            .filter { it.id !in hiddenTrackIds && it.id !in trackIdsInSharedGroups }
                        val combined = (sharedGroups.map { SharedListItem.GroupItem(it) } +
                            sharedTrackers.map { SharedListItem.TrackerItem(it) })
                            .sortedBy { it.sortName.lowercase(Locale.getDefault()) }
                        adapter?.setItems(combined, hiddenTrackIds)
                        emptyView.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
                        loadingOverlay.visibility = View.GONE
                        loadingSpinner.stop(hide = false)
                        swipeRefresh.isRefreshing = false
                        applyScrollAndHighlightIfPending()
                    }
                }
            }
        }
    }

    fun requestScrollToTrackerId(trackerId: String?) {
        pendingScrollToGroupId = null
        pendingScrollToTrackerId = trackerId
        if (trackerId == null) {
            adapter?.setHighlightedTrackerId(null)
            return
        }
        // If target is already in the list, scroll immediately to avoid refresh delay.
        if ((adapter?.itemCount ?: 0) > 0 && adapter?.indexOfTrackerId(trackerId) ?: -1 >= 0) {
            applyScrollAndHighlightIfPending()
            return
        }
        loadTrackers(showOverlay = false)
    }

    fun requestScrollToGroupId(groupId: String?) {
        pendingScrollToTrackerId = null
        pendingScrollToGroupId = groupId
        if (groupId == null) return
        if ((adapter?.itemCount ?: 0) > 0 && adapter?.indexOfGroupId(groupId) ?: -1 >= 0) {
            applyScrollAndHighlightIfPending()
            return
        }
        loadTrackers(showOverlay = false)
    }

    private fun applyScrollAndHighlightIfPending() {
        val ad = adapter ?: return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val trackerId = pendingScrollToTrackerId
        val groupId = pendingScrollToGroupId
        val index = when {
            trackerId != null -> ad.indexOfTrackerId(trackerId)
            groupId != null -> ad.indexOfGroupId(groupId)
            else -> -1
        }
        if (index < 0) return

        // Set highlight immediately so it is applied when the item is bound (before or during scroll).
        if (trackerId != null) {
            ad.setHighlightedTrackerId(trackerId)
        } else if (groupId != null) {
            ad.setHighlightedGroupId(groupId)
        }
        pendingScrollToTrackerId = null
        pendingScrollToGroupId = null

        fun doScroll() {
            if (!isAdded || recyclerView.width <= 0 || recyclerView.height <= 0) return
            val offsetPx = (recyclerView.height / 3).coerceAtLeast(0)
            val scroller = object : LinearSmoothScroller(requireContext()) {
                override fun calculateDyToMakeVisible(view: View, snapPreference: Int): Int {
                    val rv = view.parent as? RecyclerView ?: return super.calculateDyToMakeVisible(view, snapPreference)
                    return calculateDtToFit(view.top, view.bottom, offsetPx, rv.height, SNAP_TO_START)
                }
                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                    return 15f / displayMetrics.densityDpi
                }
            }
            scroller.setTargetPosition(index)
            layoutManager.startSmoothScroll(scroller)
        }

        recyclerView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                recyclerView.viewTreeObserver.removeOnPreDrawListener(this)
                if (isAdded && recyclerView.width > 0 && recyclerView.height > 0) {
                    doScroll()
                }
                return true
            }
        })
    }

    private fun viewOnMap(tracker: Tracker) {
        TrackerRepository.clearCurrentTrackerCache()
        (activity as? MainActivity)?.setInitialTrackForMap(tracker)
        (activity as? MainActivity)?.setCurrentTab(1, forceRefreshMap = true, delayMs = 50)
    }

    private sealed class SharedListItem(val sortName: String) {
        class TrackerItem(val tracker: Tracker) : SharedListItem(tracker.name)
        class GroupItem(val group: Group) : SharedListItem(group.name)
    }

    private enum class TrackerAction { EDIT, VIEW_ON_MAP, VIEW_PARAMS }

    private class SharedItemsAdapter(
        private var items: List<SharedListItem>,
        private var hiddenTrackIds: Set<String>,
        private var highlightedTrackerId: String? = null,
        private var highlightedGroupId: String? = null,
        private val onTrackerAction: (Tracker, TrackerAction) -> Unit,
        private val onGroupCardClick: (Group) -> Unit,
        private val onGroupEditClick: (Group) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun setItems(list: List<SharedListItem>, hidden: Set<String> = emptySet()) {
            items = list
            hiddenTrackIds = hidden
            notifyDataSetChanged()
        }

        fun getItems(): List<SharedListItem> = items
        fun getHiddenTrackIds(): Set<String> = hiddenTrackIds

        fun indexOfTrackerId(id: String): Int = items.indexOfFirst { it is SharedListItem.TrackerItem && it.tracker.id == id }

        fun indexOfGroupId(id: String): Int = items.indexOfFirst { it is SharedListItem.GroupItem && it.group.id == id }

        fun setHighlightedTrackerId(id: String?) {
            if (highlightedTrackerId == id && highlightedGroupId == null) return
            val oldTrackerId = highlightedTrackerId
            val oldGroupId = highlightedGroupId
            highlightedTrackerId = id
            highlightedGroupId = null
            if (oldTrackerId != null) {
                val oldIndex = indexOfTrackerId(oldTrackerId)
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
            }
            if (oldGroupId != null) {
                val oldIndex = indexOfGroupId(oldGroupId)
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
            }
            if (id != null) {
                val newIndex = indexOfTrackerId(id)
                if (newIndex >= 0) notifyItemChanged(newIndex)
            }
        }

        fun setHighlightedGroupId(id: String?) {
            if (highlightedGroupId == id && highlightedTrackerId == null) return
            val oldTrackerId = highlightedTrackerId
            val oldGroupId = highlightedGroupId
            highlightedGroupId = id
            highlightedTrackerId = null
            if (oldTrackerId != null) {
                val oldIndex = indexOfTrackerId(oldTrackerId)
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
            }
            if (oldGroupId != null) {
                val oldIndex = indexOfGroupId(oldGroupId)
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
            }
            if (id != null) {
                val newIndex = indexOfGroupId(id)
                if (newIndex >= 0) notifyItemChanged(newIndex)
            }
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is SharedListItem.TrackerItem -> TYPE_TRACKER
            is SharedListItem.GroupItem -> TYPE_GROUP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_GROUP) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_card, parent, false)
                GroupViewHolder(view, onGroupCardClick, onGroupEditClick, { highlightedGroupId })
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tracker_card, parent, false)
                TrackerViewHolder(view, onTrackerAction, { hiddenTrackIds }, { highlightedTrackerId })
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SharedListItem.GroupItem -> (holder as GroupViewHolder).bind(item.group)
                is SharedListItem.TrackerItem -> (holder as TrackerViewHolder).bind(item.tracker)
            }
        }

        override fun getItemCount(): Int = items.size

        class TrackerViewHolder(
            itemView: View,
            private val onAction: (Tracker, TrackerAction) -> Unit,
            private val getHiddenTrackIds: () -> Set<String>,
            private val getHighlightedTrackerId: () -> String?
        ) : RecyclerView.ViewHolder(itemView) {
            private val trackerName: TextView = itemView.findViewById(R.id.trackerName)
            private val trackerOwner: TextView = itemView.findViewById(R.id.trackerOwner)
            private val trackerSelectedCheck: ImageView = itemView.findViewById(R.id.trackerSelectedCheck)
            private val trackerChevronIcon: ImageView = itemView.findViewById(R.id.trackerChevronIcon)
            private val trackerMetaRow: View = itemView.findViewById(R.id.trackerMetaRow)
            private val trackerLastUpdate: TextView = itemView.findViewById(R.id.trackerLastUpdate)
            private val trackerSeparator: TextView = itemView.findViewById(R.id.trackerSeparator)
            private val trackerPosition: TextView = itemView.findViewById(R.id.trackerPosition)
            private val btnViewParams: MaterialButton = itemView.findViewById(R.id.btnViewParams)
            private val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEdit)
            private val btnViewOnMap: MaterialButton = itemView.findViewById(R.id.btnViewOnMap)

            fun bind(tracker: Tracker) {
                val selectedId = itemView.context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tracker_id", "") ?: ""
                trackerSelectedCheck.visibility = if (tracker.id == selectedId) View.VISIBLE else View.GONE
                trackerName.text = tracker.name
                val ownerText = tracker.owner_email?.takeIf { it.isNotBlank() }
                if (ownerText != null) {
                    trackerOwner.visibility = View.VISIBLE
                    trackerOwner.text = ownerText
                    setMetaRowBottomMarginDp(2)
                } else {
                    trackerOwner.visibility = View.GONE
                    setMetaRowBottomMarginDp(12)
                }
                val color = parseHexToColor(tracker.color, itemView.context)
                trackerChevronIcon.setColorFilter(color)
                val lastCoord = tracker.last_point
                val lastUpdateMs = when {
                    lastCoord != null && lastCoord.size >= 3 -> {
                        val t = lastCoord[2]
                        (t as? Number)?.toLong()?.let { n -> if (n < 1e12) n * 1000 else n }
                    }
                    else -> null
                }
                val lastPosition = if (lastCoord != null && lastCoord.size >= 2) {
                    val lon = (lastCoord[0] as? Number)?.toDouble()
                    val lat = (lastCoord[1] as? Number)?.toDouble()
                    if (lat != null && lon != null) Pair(lat, lon) else null
                } else null
                trackerLastUpdate.text = if (lastUpdateMs != null) {
                    LIST_DATE_FORMAT.format(Date(lastUpdateMs))
                } else {
                    itemView.context.getString(R.string.waiting_for_data)
                }
                if (lastPosition != null) {
                    trackerSeparator.visibility = View.VISIBLE
                    trackerPosition.text = "%.4f, %.4f".format(Locale.US, lastPosition.first, lastPosition.second)
                } else {
                    trackerSeparator.visibility = View.GONE
                    trackerPosition.text = ""
                }
                val hasPoints = lastPosition != null
                btnViewOnMap.isEnabled = hasPoints
                btnViewOnMap.alpha = if (hasPoints) 1f else 0.4f
                btnViewParams.setOnClickListener { onAction(tracker, TrackerAction.VIEW_PARAMS) }
                btnEdit.setOnClickListener { onAction(tracker, TrackerAction.EDIT) }
                btnViewOnMap.setOnClickListener { onAction(tracker, TrackerAction.VIEW_ON_MAP) }
                (itemView as? MaterialCardView)?.let { card ->
                    val defaultStrokePx = itemView.resources.getDimensionPixelSize(R.dimen.card_stroke_width)
                    val highlight = tracker.id == getHighlightedTrackerId()
                    card.setStrokeWidth(defaultStrokePx)
                    card.strokeColor = ContextCompat.getColor(itemView.context, R.color.card_stroke_color)
                    card.setCardBackgroundColor(
                        ContextCompat.getColor(
                            itemView.context,
                            if (highlight) R.color.highlight_card_background else R.color.surface
                        )
                    )
                }
            }

            private fun setMetaRowBottomMarginDp(dp: Int) {
                val lp = trackerMetaRow.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                val px = (dp * itemView.resources.displayMetrics.density).toInt()
                if (lp.bottomMargin == px) return
                lp.bottomMargin = px
                trackerMetaRow.layoutParams = lp
            }

            companion object {
                private val LIST_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
            }
        }

        class GroupViewHolder(
            itemView: View,
            private val onCardClick: (Group) -> Unit,
            private val onEditClick: (Group) -> Unit,
            private val getHighlightedGroupId: () -> String?
        ) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.groupName)
            private val owner: TextView = itemView.findViewById(R.id.groupOwner)
            private val meta: TextView = itemView.findViewById(R.id.groupMeta)
            private val content: View = itemView.findViewById(R.id.groupCardContent)
            private val editButton: ImageButton = itemView.findViewById(R.id.groupCardEdit)

            fun bind(group: Group) {
                name.text = group.name
                val ownerText = group.owner_email?.takeIf { it.isNotBlank() }
                if (ownerText != null) {
                    owner.visibility = View.VISIBLE
                    owner.text = ownerText
                } else {
                    owner.visibility = View.GONE
                }
                val tracks = (group.track_ids ?: emptyList()).size
                meta.text = "$tracks trackers"
                editButton.visibility = View.VISIBLE
                content.setOnClickListener { onCardClick(group) }
                editButton.setOnClickListener { onEditClick(group) }
                (itemView as? MaterialCardView)?.let { card ->
                    val defaultStrokePx = itemView.resources.getDimensionPixelSize(R.dimen.card_stroke_width)
                    val highlight = group.id == getHighlightedGroupId()
                    card.setStrokeWidth(defaultStrokePx)
                    card.strokeColor = ContextCompat.getColor(itemView.context, R.color.card_stroke_color)
                    card.setCardBackgroundColor(
                        ContextCompat.getColor(
                            itemView.context,
                            if (highlight) R.color.highlight_card_background else R.color.surface
                        )
                    )
                }
            }
        }

        companion object {
            private const val TYPE_TRACKER = 1
            private const val TYPE_GROUP = 2
        }
    }
}

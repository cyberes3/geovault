package com.geovault.tracker.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.parseHexToColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedTrackersFragment : Fragment() {

    private lateinit var addFab: FloatingActionButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private var adapter: SharedItemsAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_shared_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addFab = view.findViewById(R.id.sharedAddTrackerFab)
        swipeRefresh = view.findViewById(R.id.sharedSwipeRefresh)
        recyclerView = view.findViewById(R.id.sharedRecyclerView)
        emptyView = view.findViewById(R.id.sharedEmpty)
        loadingOverlay = view.findViewById(R.id.sharedLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.sharedLoadingSpinner)

        addFab.setOnClickListener {
            DiscoverTrackersBottomSheet().show(parentFragmentManager, "discover_trackers")
        }
        swipeRefresh.setOnRefreshListener { loadTrackers() }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SharedItemsAdapter(
            emptyList(),
            emptySet(),
            onTrackerAction = { tracker, action ->
                when (action) {
                    TrackerAction.EDIT -> (activity as? MainActivity)?.showEditTrackerFragment(tracker)
                    TrackerAction.VIEW_ON_MAP -> viewOnMap(tracker)
                    TrackerAction.UNSUBSCRIBE -> unsubscribeTracker(tracker)
                    TrackerAction.REMOVE_FROM_SHARE -> removeFromShare(tracker)
                    TrackerAction.HIDE_ON_MAP -> toggleTrackerMapVisibility(tracker)
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
                requireActivity().supportFragmentManager.beginTransaction()
                    .add(R.id.fragment_overlay_container, GroupDetailBottomSheet.newInstance(group), "group_detail")
                    .addToBackStack(null)
                    .commit()
            }
        )
        recyclerView.adapter = adapter

        parentFragmentManager.setFragmentResultListener(TrackersListFragment.REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, _ ->
            loadTrackers()
        }
        parentFragmentManager.setFragmentResultListener(GroupsListFragment.REQUEST_GROUPS_REFRESH, viewLifecycleOwner) { _, _ ->
            loadTrackers()
        }

        loadTrackers()
    }

    private fun loadTrackers() {
        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.start()
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
            val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
            TrackerRepository.getGroups(requireContext(), forceRefresh = true) { groups ->
                if (!isAdded) return@getGroups
                val sharedGroups = (groups ?: emptyList())
                    .filter { it.is_owner != true && it.id !in hiddenGroupIds }
                val trackIdsInSharedGroups = sharedGroups
                    .flatMap { it.track_ids ?: emptyList() }
                    .toSet()
                TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
                    if (!isAdded) return@getTrackers
                    requireActivity().runOnUiThread {
                        val sharedTrackers = (list ?: emptyList())
                            .filter { !it.isOwner() && it.id !in hiddenTrackIds && it.id !in trackIdsInSharedGroups }
                        val combined = (sharedGroups.map { SharedListItem.GroupItem(it) } +
                            sharedTrackers.map { SharedListItem.TrackerItem(it) })
                            .sortedBy { it.sortName.lowercase(Locale.getDefault()) }
                        adapter?.setItems(combined, hiddenTrackIds)
                        emptyView.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
                        loadingOverlay.visibility = View.GONE
                        loadingSpinner.stop(hide = false)
                        swipeRefresh.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun toggleTrackerMapVisibility(tracker: Tracker) {
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val current = (visibility?.hidden_track_ids ?: emptyList()).toMutableList()
            val hidden = current.contains(tracker.id)
            val newList = if (hidden) current.filter { it != tracker.id } else current + tracker.id
            TrackerRepository.patchMapVisibility(requireContext(), MapVisibilityRequest(hidden_track_ids = newList)) { updated ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        loadTrackers()
                        (activity as? MainActivity)?.showSnackbar(
                            if (hidden) getString(R.string.show_on_map) else getString(R.string.hide_on_map)
                        )
                    }
                }
            }
        }
    }

    private fun viewOnMap(tracker: Tracker) {
        TrackerRepository.clearCurrentTrackerCache()
        (activity as? MainActivity)?.setInitialTrackForMap(tracker)
        (activity as? MainActivity)?.setCurrentTab(1, forceRefreshMap = true, delayMs = 50)
    }

    private fun unsubscribeTracker(tracker: Tracker) {
        TrackerRepository.unsubscribeTracker(requireContext(), tracker.id) { success ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    if (success) {
                        loadTrackers()
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.unsubscribed))
                    } else {
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
    }

    private fun removeFromShare(tracker: Tracker) {
        TrackerRepository.leaveShareWithMe(requireContext(), tracker.id) { success ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    if (success) {
                        loadTrackers()
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.removed_from_share))
                    } else {
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
    }

    private sealed class SharedListItem(val sortName: String) {
        class TrackerItem(val tracker: Tracker) : SharedListItem(tracker.name)
        class GroupItem(val group: Group) : SharedListItem(group.name)
    }

    private enum class TrackerAction { EDIT, VIEW_ON_MAP, VIEW_PARAMS, UNSUBSCRIBE, REMOVE_FROM_SHARE, HIDE_ON_MAP }

    private class SharedItemsAdapter(
        private var items: List<SharedListItem>,
        private var hiddenTrackIds: Set<String>,
        private val onTrackerAction: (Tracker, TrackerAction) -> Unit,
        private val onGroupCardClick: (Group) -> Unit,
        private val onGroupEditClick: (Group) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun setItems(list: List<SharedListItem>, hidden: Set<String> = emptySet()) {
            items = list
            hiddenTrackIds = hidden
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is SharedListItem.TrackerItem -> TYPE_TRACKER
            is SharedListItem.GroupItem -> TYPE_GROUP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_GROUP) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_card, parent, false)
                GroupViewHolder(view, onGroupCardClick, onGroupEditClick)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tracker_card, parent, false)
                TrackerViewHolder(view, onTrackerAction, { hiddenTrackIds })
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
            private val getHiddenTrackIds: () -> Set<String>
        ) : RecyclerView.ViewHolder(itemView) {
            private val trackerName: TextView = itemView.findViewById(R.id.trackerName)
            private val trackerSelectedCheck: ImageView = itemView.findViewById(R.id.trackerSelectedCheck)
            private val trackerChevronIcon: ImageView = itemView.findViewById(R.id.trackerChevronIcon)
            private val trackerLastUpdate: TextView = itemView.findViewById(R.id.trackerLastUpdate)
            private val trackerSeparator: TextView = itemView.findViewById(R.id.trackerSeparator)
            private val trackerPosition: TextView = itemView.findViewById(R.id.trackerPosition)
            private val btnViewParams: MaterialButton = itemView.findViewById(R.id.btnViewParams)
            private val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEdit)
            private val btnViewOnMap: MaterialButton = itemView.findViewById(R.id.btnViewOnMap)
            private val sharedActionsRow: View = itemView.findViewById(R.id.trackerSharedActionsRow)
            private val btnHideOnMap: MaterialButton = itemView.findViewById(R.id.btnHideOnMap)
            private val btnUnsubscribe: MaterialButton = itemView.findViewById(R.id.btnUnsubscribe)
            private val btnRemoveFromShare: MaterialButton = itemView.findViewById(R.id.btnRemoveFromShare)

            fun bind(tracker: Tracker) {
                val selectedId = itemView.context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tracker_id", "") ?: ""
                trackerSelectedCheck.visibility = if (tracker.id == selectedId) View.VISIBLE else View.GONE
                trackerName.text = tracker.name
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
                sharedActionsRow.visibility = View.VISIBLE
                val hiddenOnMap = tracker.id in getHiddenTrackIds()
                btnHideOnMap.text = itemView.context.getString(
                    if (hiddenOnMap) R.string.show_on_map else R.string.hide_on_map
                )
                btnHideOnMap.setOnClickListener { onAction(tracker, TrackerAction.HIDE_ON_MAP) }
                btnUnsubscribe.setOnClickListener { onAction(tracker, TrackerAction.UNSUBSCRIBE) }
                btnRemoveFromShare.setOnClickListener { onAction(tracker, TrackerAction.REMOVE_FROM_SHARE) }
                btnViewParams.setOnClickListener { onAction(tracker, TrackerAction.VIEW_PARAMS) }
                btnEdit.setOnClickListener { onAction(tracker, TrackerAction.EDIT) }
                btnViewOnMap.setOnClickListener { onAction(tracker, TrackerAction.VIEW_ON_MAP) }
                (itemView as? MaterialCardView)?.setStrokeWidth(0)
                (itemView as? MaterialCardView)?.strokeColor = android.graphics.Color.TRANSPARENT
            }

            companion object {
                private val LIST_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
            }
        }

        class GroupViewHolder(
            itemView: View,
            private val onCardClick: (Group) -> Unit,
            private val onEditClick: (Group) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.groupName)
            private val meta: TextView = itemView.findViewById(R.id.groupMeta)
            private val content: View = itemView.findViewById(R.id.groupCardContent)
            private val editButton: ImageButton = itemView.findViewById(R.id.groupCardEdit)

            fun bind(group: Group) {
                name.text = group.name
                val tracks = (group.track_ids ?: emptyList()).size
                meta.text = "$tracks trackers"
                editButton.visibility = View.VISIBLE
                content.setOnClickListener { onCardClick(group) }
                editButton.setOnClickListener { onEditClick(group) }
            }
        }

        companion object {
            private const val TYPE_TRACKER = 1
            private const val TYPE_GROUP = 2
        }
    }
}

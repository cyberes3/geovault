package com.geovault.tracker.fragments

import android.content.Context
import android.widget.ImageView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.parseHexToColor
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackersListFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private var adapter: TrackersAdapter? = null
    private var pendingScrollToTrackerId: String? = null

    private fun visibleOwnerTrackers(list: List<Tracker>): List<Tracker> {
        return list.filter { tracker ->
            tracker.isOwner() && ((tracker.settings?.get("hidden_in_list") as? Boolean) != true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_trackers_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.trackersSwipeRefresh)
        recyclerView = view.findViewById(R.id.trackersRecyclerView)
        loadingOverlay = view.findViewById(R.id.trackersLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.trackersLoadingSpinner)

        swipeRefresh.setOnRefreshListener { loadTrackers() }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dx != 0 || dy != 0) clearHighlight()
            }
        })
        adapter = TrackersAdapter(emptyList()) { tracker, action ->
            when (action) {
                TrackerAction.EDIT -> (activity as? MainActivity)?.showEditTrackerFragment(tracker)
                TrackerAction.VIEW_ON_MAP -> viewOnMap(tracker)
                TrackerAction.UNSUBSCRIBE -> unsubscribeTracker(tracker)
                TrackerAction.REMOVE_FROM_SHARE -> removeFromShare(tracker)
                TrackerAction.VIEW_PARAMS -> (activity as? MainActivity)?.showTrackerParamsFragment(
                    tracker.id,
                    tracker.name,
                    lastUpdateMs = tracker.last_point?.let { c ->
                        if (c.size >= 3) {
                            val t = (c[2] as? Number)?.toLong() ?: return@let null
                            if (t < 1e12) t * 1000 else t
                        } else null
                    },
                    positionLat = tracker.last_point?.let { c ->
                        if (c.size >= 2) (c[1] as? Number)?.toDouble() else null
                    },
                    positionLon = tracker.last_point?.let { c ->
                        if (c.size >= 2) (c[0] as? Number)?.toDouble() else null
                    }
                )
            }
        }
        recyclerView.adapter = adapter
        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.start()
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    adapter?.setTrackers(visibleOwnerTrackers(list ?: emptyList()))
                    loadingSpinner.stop(hide = false)
                    loadingOverlay.visibility = View.GONE
                    applyScrollAndHighlightIfPending()
                }
            }
        }

        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, bundle ->
            val hiddenTrackerId = bundle?.getString(KEY_HIDDEN_TRACKER_ID)
            if (!hiddenTrackerId.isNullOrEmpty()) {
                adapter?.removeTrackerId(hiddenTrackerId)
            }
            loadTrackers()
        }
        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_UPDATE_TRACKER, viewLifecycleOwner) { _, bundle ->
            val updated = bundle.getParcelable<Tracker>("tracker", Tracker::class.java)
            val hiddenInList = bundle.getBoolean(KEY_UPDATED_TRACKER_HIDDEN, false)
            if (updated != null) {
                if (hiddenInList) {
                    adapter?.removeTrackerId(updated.id)
                } else {
                    adapter?.updateTracker(updated)
                }
            }
        }
        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_UPDATE_LIST_FROM_CACHE, viewLifecycleOwner) { _, _ ->
            TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        adapter?.setTrackers(visibleOwnerTrackers(list ?: emptyList()))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        var shouldReload = false
        pendingHiddenTrackerId?.let { id ->
            pendingHiddenTrackerId = null
            adapter?.removeTrackerId(id)
            shouldReload = true
        }
        if (pendingFullRefresh) {
            pendingFullRefresh = false
            shouldReload = true
        }
        if (shouldReload) {
            loadTrackersInBackground()
        }
    }

    override fun onPause() {
        super.onPause()
        clearHighlight()
    }

    companion object {
        const val REQUEST_REFRESH_LIST = "tracker_list_refresh"
        const val KEY_HIDDEN_TRACKER_ID = "hidden_tracker_id"
        const val REQUEST_UPDATE_TRACKER = "tracker_list_update_tracker"
        const val KEY_UPDATED_TRACKER_HIDDEN = "updated_tracker_hidden"
        const val REQUEST_UPDATE_LIST_FROM_CACHE = "tracker_list_update_from_cache"
        var pendingHiddenTrackerId: String? = null
        var pendingFullRefresh: Boolean = false
    }

    private fun loadTrackers() {
        clearHighlight()
        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.start()
        TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    adapter?.setTrackers(visibleOwnerTrackers(list ?: emptyList()))
                    loadingSpinner.stop(hide = false)
                    loadingOverlay.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun loadTrackersInBackground() {
        TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    adapter?.setTrackers(visibleOwnerTrackers(list ?: emptyList()))
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    fun requestScrollToTrackerId(trackerId: String?) {
        pendingScrollToTrackerId = trackerId
        if ((adapter?.itemCount ?: 0) > 0) {
            applyScrollAndHighlightIfPending()
        }
    }

    private fun clearHighlight() {
        adapter?.setHighlightedTrackerId(null)
    }

    private fun applyScrollAndHighlightIfPending() {
        val id = pendingScrollToTrackerId ?: return
        pendingScrollToTrackerId = null
        val ad = adapter ?: return
        val index = ad.indexOfTrackerId(id)
        if (index < 0) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val offset = recyclerView.height / 3
        layoutManager.scrollToPositionWithOffset(index, offset)
        ad.setHighlightedTrackerId(id)
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

    private enum class TrackerAction { EDIT, VIEW_ON_MAP, VIEW_PARAMS, UNSUBSCRIBE, REMOVE_FROM_SHARE }

    private class TrackersAdapter(
        private var trackers: List<Tracker>,
        private val onAction: (Tracker, TrackerAction) -> Unit
    ) : RecyclerView.Adapter<TrackersAdapter.TrackerViewHolder>() {

        companion object {
            private val LIST_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
        }

        var highlightedTrackerId: String? = null
            private set

        fun indexOfTrackerId(id: String): Int = trackers.indexOfFirst { it.id == id }

        fun setHighlightedTrackerId(id: String?) {
            if (highlightedTrackerId == id) return
            val oldId = highlightedTrackerId
            highlightedTrackerId = id
            if (oldId != null) {
                val oldIndex = indexOfTrackerId(oldId)
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
            }
            if (id != null) {
                val newIndex = indexOfTrackerId(id)
                if (newIndex >= 0) notifyItemChanged(newIndex)
            }
        }

        fun setTrackers(list: List<Tracker>) {
            trackers = list
            notifyDataSetChanged()
        }

        fun removeTrackerId(trackerId: String) {
            val index = trackers.indexOfFirst { it.id == trackerId }
            if (index >= 0) {
                trackers = trackers.toMutableList().apply { removeAt(index) }
                notifyItemRemoved(index)
            }
        }

        fun updateTracker(updated: Tracker) {
            val index = trackers.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                val existing = trackers[index]
                trackers = trackers.toMutableList().apply {
                    set(index, existing.copy(name = updated.name, color = updated.color))
                }
                notifyItemChanged(index)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tracker_card, parent, false)
            return TrackerViewHolder(view, onAction)
        }

        override fun onBindViewHolder(holder: TrackerViewHolder, position: Int) {
            holder.bind(trackers[position], highlightedTrackerId)
        }

        override fun getItemCount(): Int = trackers.size

        class TrackerViewHolder(
            itemView: View,
            private val onAction: (Tracker, TrackerAction) -> Unit
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
            private val btnUnsubscribe: MaterialButton = itemView.findViewById(R.id.btnUnsubscribe)
            private val btnRemoveFromShare: MaterialButton = itemView.findViewById(R.id.btnRemoveFromShare)

            fun bind(tracker: Tracker, highlightedId: String?) {
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
                val isOwner = tracker.isOwner()
                sharedActionsRow.visibility = if (isOwner) View.GONE else View.VISIBLE
                btnUnsubscribe.setOnClickListener { onAction(tracker, TrackerAction.UNSUBSCRIBE) }
                btnRemoveFromShare.setOnClickListener { onAction(tracker, TrackerAction.REMOVE_FROM_SHARE) }
                btnViewParams.setOnClickListener { onAction(tracker, TrackerAction.VIEW_PARAMS) }
                btnEdit.setOnClickListener { onAction(tracker, TrackerAction.EDIT) }
                btnViewOnMap.setOnClickListener { onAction(tracker, TrackerAction.VIEW_ON_MAP) }
                (itemView as? MaterialCardView)?.let { card ->
                    val defaultStrokePx = itemView.resources.getDimensionPixelSize(R.dimen.card_stroke_width)
                    val highlight = tracker.id == highlightedId
                    val strokePx = if (highlight) (4 * itemView.resources.displayMetrics.density).toInt() else defaultStrokePx
                    card.setStrokeWidth(strokePx)
                    card.strokeColor = ContextCompat.getColor(itemView.context, R.color.card_stroke_color)
                }
            }
        }
    }
}

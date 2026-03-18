package com.geovault.tracker.fragments

import android.content.Context
import android.widget.ImageView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import android.util.DisplayMetrics
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.parseHexToColor
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.lastPosition
import com.geovault.tracker.lastUpdateMs
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class TrackersListFragment : Fragment() {
    private val viewModel: TrackersListViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
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
        emptyView = view.findViewById(R.id.trackersEmpty)
        loadingOverlay = view.findViewById(R.id.trackersLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.trackersLoadingSpinner)

        swipeRefresh.setOnRefreshListener { loadTrackers() }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    clearHighlight()
                }
            }
        })
        adapter = TrackersAdapter(emptyList()) { tracker, action ->
            when (action) {
                TrackerAction.EDIT -> (activity as? MainActivity)?.let { if (tracker.isOwner()) it.showEditTrackerFragment(tracker) else it.showEditSharedTrackerFragment(tracker) }
                TrackerAction.VIEW_ON_MAP -> viewOnMap(tracker)
                TrackerAction.UNSUBSCRIBE -> unsubscribeTracker(tracker)
                TrackerAction.REMOVE_FROM_SHARE -> removeFromShare(tracker)
                TrackerAction.VIEW_PARAMS -> (activity as? MainActivity)?.showTrackerParamsFragment(
                    tracker.id,
                    tracker.name,
                    lastUpdateMs = tracker.lastUpdateMs(),
                    positionLat = tracker.lastPosition()?.first,
                    positionLon = tracker.lastPosition()?.second
                )
            }
        }
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                setTrackers(visibleOwnerTrackers(state.trackers))
                if (state.isLoading) {
                    loadingOverlay.visibility = View.VISIBLE
                    loadingSpinner.start()
                } else {
                    loadingOverlay.visibility = View.GONE
                    loadingSpinner.stop(hide = false)
                    swipeRefresh.isRefreshing = false
                    applyScrollAndHighlightIfPending()
                }
            }
        }

        val cached = TrackerRepository.getTrackersCache()
        if (cached != null) {
            viewModel.setCached(cached)
            loadingOverlay.visibility = View.GONE
            loadingSpinner.stop(hide = false)
            applyScrollAndHighlightIfPending()
        } else {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
        }
        viewModel.load(requireContext(), forceRefresh = false, showLoading = false)

        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, bundle ->
            val newTracker = bundle?.getParcelable(KEY_NEW_TRACKER, Tracker::class.java)
            if (newTracker != null) {
                val cache = TrackerRepository.getTrackersCache()
                if (cache != null) {
                    setTrackers(visibleOwnerTrackers(cache))
                }
            } else {
                val deletedTrackerId = bundle?.getString(KEY_DELETED_TRACKER_ID)
                if (!deletedTrackerId.isNullOrEmpty()) {
                    adapter?.removeTrackerId(deletedTrackerId)
                    return@setFragmentResultListener
                }
                val hiddenTrackerId = bundle?.getString(KEY_HIDDEN_TRACKER_ID)
                if (!hiddenTrackerId.isNullOrEmpty()) {
                    adapter?.removeTrackerId(hiddenTrackerId)
                }
                loadTrackers()
            }
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
                        viewModel.setCached(list ?: emptyList())
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
        const val KEY_NEW_TRACKER = "new_tracker"
        const val KEY_DELETED_TRACKER_ID = "deleted_tracker_id"
        const val KEY_HIDDEN_TRACKER_ID = "hidden_tracker_id"
        const val KEY_SKIP_SHARED_LIST_REFRESH = "skip_shared_list_refresh"
        const val REQUEST_UPDATE_TRACKER = "tracker_list_update_tracker"
        const val KEY_UPDATED_TRACKER_HIDDEN = "updated_tracker_hidden"
        const val REQUEST_UPDATE_LIST_FROM_CACHE = "tracker_list_update_from_cache"
        var pendingHiddenTrackerId: String? = null
        var pendingFullRefresh: Boolean = false
    }

    private fun loadTrackers() {
        clearHighlight()
        viewModel.load(requireContext(), forceRefresh = true, showLoading = true)
    }

    private fun loadTrackersInBackground() {
        viewModel.load(requireContext(), forceRefresh = true, showLoading = false)
    }

    private fun setTrackers(trackers: List<Tracker>) {
        adapter?.setTrackers(trackers)
        emptyView.visibility = if (trackers.isEmpty()) View.VISIBLE else View.GONE
    }

    fun requestScrollToTrackerId(trackerId: String?) {
        pendingScrollToTrackerId = trackerId
        if (trackerId == null) {
            clearHighlight()
            return
        }
        // If target is already in the list, scroll immediately to avoid 2s refresh delay.
        if ((adapter?.itemCount ?: 0) > 0 && adapter?.indexOfTrackerId(trackerId) ?: -1 >= 0) {
            applyScrollAndHighlightIfPending()
            return
        }
        loadTrackersInBackground()
    }

    private fun clearHighlight() {
        adapter?.setHighlightedTrackerId(null)
    }

    private fun applyScrollAndHighlightIfPending() {
        val id = pendingScrollToTrackerId ?: return
        val ad = adapter ?: return
        val index = ad.indexOfTrackerId(id)
        if (index < 0) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

        fun doScroll() {
            if (!isAdded || recyclerView.width <= 0 || recyclerView.height <= 0) return
            val offsetPx = (recyclerView.height / 3).coerceAtLeast(0)
            ad.setHighlightedTrackerId(id)
            pendingScrollToTrackerId = null
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
        TrackerRepository.clearSelectedTrackerCaches()
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
            private val sharedActionsRow: View = itemView.findViewById(R.id.trackerSharedActionsRow)
            private val btnUnsubscribe: MaterialButton = itemView.findViewById(R.id.btnUnsubscribe)
            private val btnRemoveFromShare: MaterialButton = itemView.findViewById(R.id.btnRemoveFromShare)

            fun bind(tracker: Tracker, highlightedId: String?) {
                val selectedId = SelectedTrackerPrefs.selectedTrackerId(itemView.context)
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
                val lastUpdateMs = tracker.lastUpdateMs()
                val lastPosition = tracker.lastPosition()
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
        }
    }
}

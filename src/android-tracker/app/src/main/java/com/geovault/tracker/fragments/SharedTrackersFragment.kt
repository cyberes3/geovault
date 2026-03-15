package com.geovault.tracker.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.parseHexToColor
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedTrackersFragment : Fragment() {

    private lateinit var addButton: MaterialButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private var adapter: SharedTrackersAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_shared_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addButton = view.findViewById(R.id.sharedAddTrackerButton)
        swipeRefresh = view.findViewById(R.id.sharedSwipeRefresh)
        recyclerView = view.findViewById(R.id.sharedRecyclerView)
        emptyView = view.findViewById(R.id.sharedEmpty)
        loadingOverlay = view.findViewById(R.id.sharedLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.sharedLoadingSpinner)

        addButton.setOnClickListener {
            DiscoverTrackersBottomSheet().show(parentFragmentManager, "discover_trackers")
        }
        swipeRefresh.setOnRefreshListener { loadTrackers() }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SharedTrackersAdapter(emptyList(), emptySet()) { tracker, action ->
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
        }
        recyclerView.adapter = adapter

        parentFragmentManager.setFragmentResultListener(TrackersFragment.REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, _ ->
            loadTrackers()
        }

        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.start()
        loadTrackers()
    }

    private fun loadTrackers() {
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val hiddenTrackIds = (visibility?.hidden_track_ids ?: emptyList()).toSet()
            TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        val shared = (list ?: emptyList()).filter { !it.isOwner() }
                        adapter?.setTrackers(shared, hiddenTrackIds)
                        emptyView.visibility = if (shared.isEmpty()) View.VISIBLE else View.GONE
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

    private enum class TrackerAction { EDIT, VIEW_ON_MAP, VIEW_PARAMS, UNSUBSCRIBE, REMOVE_FROM_SHARE, HIDE_ON_MAP }

    private class SharedTrackersAdapter(
        private var trackers: List<Tracker>,
        private var hiddenTrackIds: Set<String>,
        private val onAction: (Tracker, TrackerAction) -> Unit
    ) : RecyclerView.Adapter<SharedTrackersAdapter.ViewHolder>() {

        fun setTrackers(list: List<Tracker>, hidden: Set<String> = emptySet()) {
            trackers = list
            hiddenTrackIds = hidden
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tracker_card, parent, false)
            return ViewHolder(view, onAction, { hiddenTrackIds })
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(trackers[position])
        }

        override fun getItemCount(): Int = trackers.size

        class ViewHolder(
            itemView: View,
            private val onAction: (Tracker, TrackerAction) -> Unit,
            private val getHiddenTrackIds: () -> Set<String>
        ) : RecyclerView.ViewHolder(itemView) {
            private val trackerName: TextView = itemView.findViewById(R.id.trackerName)
            private val trackerSelectedCheck: ImageView = itemView.findViewById(R.id.trackerSelectedCheck)
            private val colorBar: View = itemView.findViewById(R.id.trackerColorBar)
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
                colorBar.setBackgroundColor(color)
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
    }
}

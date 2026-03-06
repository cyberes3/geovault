package com.geovault.tracker.fragments

import android.content.Context
import android.graphics.Color
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
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackersFragment : Fragment() {

    private lateinit var createTrackerButton: MaterialButton
    private lateinit var trackerListDivider: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private var adapter: TrackersAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_trackers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        createTrackerButton = view.findViewById(R.id.createTrackerButton)
        trackerListDivider = view.findViewById(R.id.trackerListDivider)
        swipeRefresh = view.findViewById(R.id.trackersSwipeRefresh)
        recyclerView = view.findViewById(R.id.trackersRecyclerView)

        swipeRefresh.setOnRefreshListener { loadTrackers() }

        createTrackerButton.setOnClickListener {
            (activity as? MainActivity)?.showNewTrackerFragment()
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val canScrollUp = recyclerView.canScrollVertically(-1)
                trackerListDivider.visibility = if (canScrollUp) View.VISIBLE else View.INVISIBLE
            }
        })
        adapter = TrackersAdapter(emptyList()) { tracker, action ->
            when (action) {
                TrackerAction.EDIT -> (activity as? MainActivity)?.showEditTrackerFragment(tracker)
                TrackerAction.VIEW_ON_MAP -> viewOnMap(tracker)
                TrackerAction.VIEW_PARAMS -> (activity as? MainActivity)?.showTrackerParamsFragment(
                    tracker.id,
                    tracker.name,
                    lastUpdateMs = tracker.geometry?.coordinates?.lastOrNull()?.let { c ->
                        if (c.size >= 3) {
                            val t = (c[2] as? Number)?.toLong() ?: return@let null
                            if (t < 1e12) t * 1000 else t
                        } else null
                    },
                    positionLat = tracker.geometry?.coordinates?.lastOrNull()?.let { c ->
                        if (c.size >= 2) (c[1] as? Number)?.toDouble() else null
                    },
                    positionLon = tracker.geometry?.coordinates?.lastOrNull()?.let { c ->
                        if (c.size >= 2) (c[0] as? Number)?.toDouble() else null
                    }
                )
            }
        }
        recyclerView.adapter = adapter
        loadTrackers()

        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_REFRESH_LIST, viewLifecycleOwner) { _, _ ->
            loadTrackers()
        }
        // Params fragment refreshes trackers in background; when done we update list from cache
        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_UPDATE_LIST_FROM_CACHE, viewLifecycleOwner) { _, _ ->
            loadTrackersFromCache()
        }
    }

    override fun onResume() {
        super.onResume()
        loadTrackers()
    }

    companion object {
        const val REQUEST_REFRESH_LIST = "tracker_list_refresh"
        const val REQUEST_UPDATE_LIST_FROM_CACHE = "tracker_list_update_from_cache"
    }

    private fun loadTrackers() {
        TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    adapter?.setTrackers(list ?: emptyList())
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun loadTrackersFromCache() {
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    adapter?.setTrackers(list ?: emptyList())
                }
            }
        }
    }

    private fun viewOnMap(tracker: Tracker) {
        requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_tracker_id", tracker.id)
            .putString("selected_tracker_name", tracker.name)
            .apply()
        TrackerRepository.clearCurrentTrackerCache()
        (activity as? MainActivity)?.setCurrentTab(1, forceRefreshMap = true)
    }

    private enum class TrackerAction { EDIT, VIEW_ON_MAP, VIEW_PARAMS }

    private class TrackersAdapter(
        private var trackers: List<Tracker>,
        private val onAction: (Tracker, TrackerAction) -> Unit
    ) : RecyclerView.Adapter<TrackersAdapter.ViewHolder>() {

        fun setTrackers(list: List<Tracker>) {
            trackers = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tracker_card, parent, false)
            return ViewHolder(view, onAction)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(trackers[position])
        }

        override fun getItemCount(): Int = trackers.size

        class ViewHolder(
            itemView: View,
            private val onAction: (Tracker, TrackerAction) -> Unit
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

            fun bind(tracker: Tracker) {
                val selectedId = itemView.context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getString("selected_tracker_id", "") ?: ""
                trackerSelectedCheck.visibility = if (tracker.id == selectedId) View.VISIBLE else View.GONE
                trackerName.text = tracker.name
                try {
                    val color = Color.parseColor(tracker.color ?: "#3388ff")
                    colorBar.setBackgroundColor(color)
                } catch (_: Exception) {
                    colorBar.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.primary_blue))
                }
                val coords = tracker.geometry?.coordinates.orEmpty()
                val lastCoord = coords.lastOrNull()
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
            }
        }

        companion object {
            private val LIST_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
        }
    }
}

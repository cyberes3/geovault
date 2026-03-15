package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.parseHexToColor
import com.google.android.material.button.MaterialButton

class GroupActionsFragment : Fragment() {

    private var group: Group? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = arguments?.getParcelable(ARG_GROUP, Group::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_group_actions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val g = group ?: return
        val title = view.findViewById<TextView>(R.id.groupActionsTitle)
        val closeButton = view.findViewById<ImageButton>(R.id.groupActionsCloseButton)
        val trackersList = view.findViewById<RecyclerView>(R.id.groupActionsTrackersList)
        val emptyView = view.findViewById<TextView>(R.id.groupActionsEmpty)
        val actionViewOnMap = view.findViewById<MaterialButton>(R.id.groupActionViewOnMap)

        title.text = g.name
        closeButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }

        actionViewOnMap.setOnClickListener {
            (activity as? MainActivity)?.openMapForGroup(g)
        }

        trackersList.layoutManager = LinearLayoutManager(requireContext())
        loadTrackersList(g, trackersList, emptyView)
    }

    private fun loadTrackersList(g: Group, trackersList: RecyclerView, emptyView: TextView) {
        val trackIds = g.track_ids ?: emptyList()
        if (trackIds.isEmpty()) {
            trackersList.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            return
        }
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val all = list ?: emptyList()
            val idToTracker = all.associateBy { it.id }
            val ordered = trackIds.mapNotNull { idToTracker[it] }
            requireActivity().runOnUiThread {
                trackersList.visibility = View.VISIBLE
                emptyView.visibility = View.GONE
                trackersList.adapter = GroupTrackerCardAdapter(
                    ordered,
                    showRemove = false,
                    onItemClick = { trackerId ->
                        (activity as? MainActivity)?.openMapForGroup(g, trackerId)
                    },
                    onShowMenu = { tracker, anchor -> showGroupTrackerMenu(anchor, g, tracker) }
                )
            }
        }
    }

    private fun showGroupTrackerMenu(anchor: View, group: Group, tracker: Tracker) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.apply {
            add(Menu.NONE, MENU_VIEW_ON_MAP, 0, getString(R.string.view_on_map))
            add(Menu.NONE, MENU_VIEW_PARAMS, 0, getString(R.string.view_params))
            if (tracker.isOwner()) {
                add(Menu.NONE, MENU_VIEW_IN_LIST, 0, getString(R.string.view_in_trackers_list))
            }
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_VIEW_ON_MAP -> {
                    (activity as? MainActivity)?.openMapForGroup(group, tracker.id)
                    true
                }
                MENU_VIEW_PARAMS -> {
                    (activity as? MainActivity)?.showTrackerParamsFragment(tracker.id, tracker.name)
                    requireActivity().supportFragmentManager.popBackStack()
                    true
                }
                MENU_VIEW_IN_LIST -> {
                    requireActivity().supportFragmentManager.popBackStack()
                    (activity as? MainActivity)?.openTrackersAndScrollTo(tracker.id)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private class GroupTrackerCardAdapter(
        private val trackers: List<Tracker>,
        private val showRemove: Boolean,
        private val onRemove: ((String) -> Unit)? = null,
        private val onItemClick: ((String) -> Unit)? = null,
        private val onShowMenu: ((Tracker, View) -> Unit)? = null
    ) : RecyclerView.Adapter<GroupTrackerCardAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_tracker_card, parent, false)
            return ViewHolder(view, onRemove, onItemClick, onShowMenu)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(trackers[position], showRemove)
        }
        override fun getItemCount(): Int = trackers.size
        class ViewHolder(
            itemView: View,
            private val onRemove: ((String) -> Unit)?,
            private val onItemClick: ((String) -> Unit)?,
            private val onShowMenu: ((Tracker, View) -> Unit)?
        ) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.groupTrackerName)
            private val colorBar: View = itemView.findViewById(R.id.groupTrackerColorBar)
            private val menuBtn: ImageButton = itemView.findViewById(R.id.groupTrackerMenu)
            private val removeBtn: ImageButton = itemView.findViewById(R.id.groupTrackerRemove)
            fun bind(tracker: Tracker, showRemove: Boolean) {
                name.text = tracker.name
                colorBar.setBackgroundColor(parseHexToColor(tracker.color, itemView.context))
                removeBtn.visibility = if (showRemove && onRemove != null) View.VISIBLE else View.GONE
                removeBtn.setOnClickListener { onRemove?.invoke(tracker.id) }
                menuBtn.setOnClickListener { onShowMenu?.invoke(tracker, menuBtn) }
                itemView.isClickable = onItemClick != null
                itemView.setOnClickListener { onItemClick?.invoke(tracker.id) }
            }
        }
    }

    companion object {
        private const val ARG_GROUP = "group"
        private const val MENU_VIEW_ON_MAP = 1
        private const val MENU_VIEW_PARAMS = 2
        private const val MENU_VIEW_IN_LIST = 3
        fun newInstance(group: Group): GroupActionsFragment {
            return GroupActionsFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_GROUP, group) }
            }
        }
    }
}

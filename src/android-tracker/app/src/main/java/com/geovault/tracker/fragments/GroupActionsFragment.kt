package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.geovault.tracker.Group
import com.google.android.material.card.MaterialCardView
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.parseHexToColor
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupActionsFragment : Fragment() {
    private val viewModel: GroupActionsViewModel by viewModels()

    private var group: Group? = null
    private var scrollToTrackerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        scrollToTrackerId = arguments?.getString(ARG_SCROLL_TO_TRACKER_ID)
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
        val actionEdit = view.findViewById<MaterialButton>(R.id.groupActionEditButton)
        val actionViewOnMap = view.findViewById<MaterialButton>(R.id.groupActionViewOnMap)

        title.text = g.name
        closeButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }

        actionEdit.visibility = if (g.is_owner == true) View.VISIBLE else View.GONE
        actionEdit.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .add(R.id.fragment_overlay_container, GroupDetailBottomSheet.newInstance(g), "group_detail")
                .addToBackStack("group_detail")
                .commit()
        }

        actionViewOnMap.setOnClickListener {
            group?.let { navHost()?.openMapForGroup(it) }
        }

        trackersList.layoutManager = LinearLayoutManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    state.group?.let { updated ->
                        group = updated
                        title.text = updated.name
                        loadTrackersList(updated, state.trackers, trackersList, emptyView)
                    }
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let { navHost()?.showSnackbar(it) }
                }
            }
        }
        viewModel.load(g.id)
    }

    private fun loadTrackersList(
        g: Group,
        allTrackers: List<Tracker>,
        trackersList: RecyclerView,
        emptyView: TextView
    ) {
        val trackIds = g.track_ids ?: emptyList()
        val targetTrackerId = scrollToTrackerId
        if (trackIds.isEmpty()) {
            trackersList.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            return
        }
        val idToTracker = allTrackers.associateBy { it.id }
        val ordered = trackIds.mapNotNull { idToTracker[it] }
        trackersList.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        trackersList.adapter = GroupTrackerCardAdapter(
            ordered,
            showRemove = false,
            highlightedTrackerId = targetTrackerId,
            onItemClick = { trackerId ->
                navHost()?.openMapForGroup(g, trackerId)
            },
            onShowMenu = { tracker, anchor -> showGroupTrackerMenu(anchor, g, tracker) }
        )
        if (targetTrackerId != null) {
            val index = ordered.indexOfFirst { it.id == targetTrackerId }
            if (index >= 0) {
                trackersList.post {
                    (trackersList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        index,
                        trackersList.height / 3
                    )
                }
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
                    navHost()?.openMapForGroup(group, tracker.id)
                    true
                }
                MENU_VIEW_PARAMS -> {
                    // Keep group members list on back stack so closing params returns here.
                    navHost()?.showTrackerParamsFragment(tracker.id, tracker.name)
                    true
                }
                MENU_VIEW_IN_LIST -> {
                    requireActivity().supportFragmentManager.popBackStack()
                    navHost()?.openTrackersAndScrollTo(tracker.id)
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
        private val highlightedTrackerId: String? = null,
        private val onRemove: ((String) -> Unit)? = null,
        private val onItemClick: ((String) -> Unit)? = null,
        private val onShowMenu: ((Tracker, View) -> Unit)? = null
    ) : RecyclerView.Adapter<GroupTrackerCardAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_tracker_card, parent, false)
            return ViewHolder(view, highlightedTrackerId, onRemove, onItemClick, onShowMenu)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(trackers[position], showRemove)
        }
        override fun getItemCount(): Int = trackers.size
        class ViewHolder(
            itemView: View,
            private val highlightedTrackerId: String?,
            private val onRemove: ((String) -> Unit)?,
            private val onItemClick: ((String) -> Unit)?,
            private val onShowMenu: ((Tracker, View) -> Unit)?
        ) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.groupTrackerName)
            private val groupTrackerChevronIcon: ImageView = itemView.findViewById(R.id.groupTrackerChevronIcon)
            private val menuBtn: ImageButton = itemView.findViewById(R.id.groupTrackerMenu)
            private val removeContainer: View = itemView.findViewById(R.id.groupTrackerRemoveContainer)
            private val removeBtn: ImageButton = itemView.findViewById(R.id.groupTrackerRemove)
            fun bind(tracker: Tracker, showRemove: Boolean) {
                name.text = tracker.name
                groupTrackerChevronIcon.setColorFilter(parseHexToColor(tracker.color, itemView.context))
                removeContainer.visibility = if (showRemove && onRemove != null) View.VISIBLE else View.GONE
                removeBtn.visibility = if (showRemove && onRemove != null) View.VISIBLE else View.GONE
                removeBtn.setOnClickListener { onRemove?.invoke(tracker.id) }
                menuBtn.setOnClickListener { onShowMenu?.invoke(tracker, menuBtn) }
                itemView.isClickable = onItemClick != null
                itemView.setOnClickListener { onItemClick?.invoke(tracker.id) }
                (itemView as? MaterialCardView)?.let { card ->
                    val defaultStrokePx = itemView.resources.getDimensionPixelSize(R.dimen.thin_card_stroke_width)
                    val highlight = tracker.id == highlightedTrackerId
                    card.strokeWidth = defaultStrokePx
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
    }

    companion object {
        private const val ARG_GROUP = "group"
        private const val ARG_SCROLL_TO_TRACKER_ID = "scroll_to_tracker_id"
        private const val MENU_VIEW_ON_MAP = 1
        private const val MENU_VIEW_PARAMS = 2
        private const val MENU_VIEW_IN_LIST = 3
        fun newInstance(group: Group, scrollToTrackerId: String? = null): GroupActionsFragment {
            return GroupActionsFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_GROUP, group)
                    scrollToTrackerId?.let { putString(ARG_SCROLL_TO_TRACKER_ID, it) }
                }
            }
        }
    }
}

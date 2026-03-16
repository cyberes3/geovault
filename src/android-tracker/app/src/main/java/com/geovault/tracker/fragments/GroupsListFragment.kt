package com.geovault.tracker.fragments

import android.os.Bundle
import android.text.InputType
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository

class GroupsListFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private var adapter: GroupsAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_groups_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.groupsSwipeRefresh)
        recyclerView = view.findViewById(R.id.groupsRecyclerView)
        emptyView = view.findViewById(R.id.groupsEmpty)
        loadingOverlay = view.findViewById(R.id.groupsLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.groupsLoadingSpinner)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GroupsAdapter(
            emptyList(),
            onCardClick = { group -> openGroupActions(group) },
            onViewOnMapClick = { group -> (activity as? MainActivity)?.openMapForGroup(group, returnToTabOnly = true) },
            onEditClick = { group -> openGroupEditor(group) }
        )
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener { loadGroups(forceRefresh = true) }

        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_GROUPS_REFRESH, viewLifecycleOwner) { _, bundle ->
            val deletedGroupId = bundle?.getString(KEY_DELETED_GROUP_ID)
            if (!deletedGroupId.isNullOrEmpty()) {
                adapter?.removeGroupById(deletedGroupId)
                emptyView.visibility = if ((adapter?.itemCount ?: 0) == 0) View.VISIBLE else View.GONE
                return@setFragmentResultListener
            }
            loadGroups(forceRefresh = true)
        }
        requireActivity().supportFragmentManager.setFragmentResultListener(REQUEST_GROUP_UPDATED, viewLifecycleOwner) { _, bundle ->
            val updated = bundle.getParcelable<Group>(KEY_UPDATED_GROUP, Group::class.java) ?: return@setFragmentResultListener
            applySingleGroupUpdate(updated)
        }

        val cached = TrackerRepository.getGroupsCache()
        if (cached != null) {
            val myGroups = cached.filter { g -> g.is_owner == true && g.hidden_in_list != true }
            applyGroups(myGroups.sortedBy { it.name.lowercase() })
        } else {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
            loadGroups(forceRefresh = false)
        }
    }

    companion object {
        const val REQUEST_GROUPS_REFRESH = "groups_refresh"
        const val REQUEST_GROUP_UPDATED = "groups_update_group"
        const val KEY_UPDATED_GROUP = "updated_group"
        const val KEY_DELETED_GROUP_ID = "deleted_group_id"
    }

    private fun shouldShowInMyGroups(group: Group): Boolean {
        return group.is_owner == true && group.hidden_in_list != true
    }

    private fun applySingleGroupUpdate(updated: Group) {
        val shouldShow = shouldShowInMyGroups(updated)
        if (shouldShow) {
            adapter?.upsertGroup(updated)
        } else {
            adapter?.removeGroupById(updated.id)
        }
        emptyView.visibility = if ((adapter?.itemCount ?: 0) == 0) View.VISIBLE else View.GONE
    }

    private fun applyGroups(groups: List<Group>) {
        loadingOverlay.visibility = View.GONE
        loadingSpinner.stop(hide = false)
        swipeRefresh.isRefreshing = false
        adapter?.setGroups(groups)
        emptyView.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadGroups(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
        }
        TrackerRepository.getGroups(requireContext(), forceRefresh = forceRefresh) { list ->
            if (!isAdded) return@getGroups
            val raw = list ?: emptyList()
            val myGroups = raw.filter { g -> g.is_owner == true && g.hidden_in_list != true }
            val sorted = myGroups.sortedBy { it.name.lowercase() }
            requireActivity().runOnUiThread {
                applyGroups(sorted)
            }
        }
    }

    fun showCreateGroupDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.new_group_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(48, 32, 48, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.create_group))
            .setView(input)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = input.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) {
                    TrackerRepository.createGroup(requireContext(), name) { group, errorMessage ->
                        if (!isAdded) return@createGroup
                        requireActivity().runOnUiThread {
                            when {
                                group != null -> {
                                    val cache = TrackerRepository.getGroupsCache()
                                    if (cache != null) {
                                        val myGroups = cache.filter { shouldShowInMyGroups(it) }.sortedBy { it.name.lowercase() }
                                        applyGroups(myGroups)
                                    } else {
                                        loadGroups(forceRefresh = true)
                                    }
                                    openGroupEditor(group)
                                }
                                !errorMessage.isNullOrBlank() -> (activity as? MainActivity)?.showSnackbar(errorMessage)
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun openGroupActions(group: Group) {
        requireActivity().supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, GroupActionsFragment.newInstance(group), "group_actions")
            .addToBackStack(null)
            .commit()
    }

    private fun openGroupEditor(group: Group) {
        requireActivity().supportFragmentManager.beginTransaction()
            .add(R.id.fragment_overlay_container, GroupDetailBottomSheet.newInstance(group), "group_detail")
            .addToBackStack("group_detail")
            .commit()
    }

    private class GroupsAdapter(
        private var groups: List<Group>,
        private val onCardClick: (Group) -> Unit,
        private val onViewOnMapClick: (Group) -> Unit,
        private val onEditClick: (Group) -> Unit
    ) : RecyclerView.Adapter<GroupsAdapter.ViewHolder>() {

        fun setGroups(list: List<Group>) {
            groups = list
            notifyDataSetChanged()
        }

        fun upsertGroup(updated: Group) {
            val mutable = groups.toMutableList()
            val idx = mutable.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                mutable[idx] = updated
            } else {
                mutable.add(updated)
            }
            groups = mutable.sortedBy { it.name.lowercase() }
            notifyDataSetChanged()
        }

        fun removeGroupById(groupId: String) {
            val idx = groups.indexOfFirst { it.id == groupId }
            if (idx < 0) return
            groups = groups.toMutableList().apply { removeAt(idx) }
            notifyItemRemoved(idx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_card, parent, false)
            return ViewHolder(view, onCardClick, onViewOnMapClick, onEditClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(groups[position])
        }

        override fun getItemCount(): Int = groups.size

        companion object {
            private const val MENU_VIEW_ON_MAP = 1
            private const val MENU_EDIT = 2
        }

        class ViewHolder(
            itemView: View,
            private val onCardClick: (Group) -> Unit,
            private val onViewOnMapClick: (Group) -> Unit,
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
                editButton.setOnClickListener { anchor -> showGroupMenu(anchor, group) }
            }

            private fun showGroupMenu(anchor: View, group: Group) {
                val popup = PopupMenu(anchor.context, anchor)
                popup.menu.apply {
                    add(Menu.NONE, MENU_VIEW_ON_MAP, 0, anchor.context.getString(R.string.view_on_map))
                    add(Menu.NONE, MENU_EDIT, 0, anchor.context.getString(R.string.edit))
                }
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_VIEW_ON_MAP -> { onViewOnMapClick(group); true }
                        MENU_EDIT -> { onEditClick(group); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }
}

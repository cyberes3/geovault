package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton

class GroupsFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private lateinit var closeButton: ImageButton
    private lateinit var fab: FloatingActionButton
    private var adapter: GroupsAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_groups, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.groupsSwipeRefresh)
        recyclerView = view.findViewById(R.id.groupsRecyclerView)
        emptyView = view.findViewById(R.id.groupsEmpty)
        loadingOverlay = view.findViewById(R.id.groupsLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.groupsLoadingSpinner)
        closeButton = view.findViewById(R.id.groupsCloseButton)
        fab = view.findViewById(R.id.groupsFab)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GroupsAdapter(
            emptyList(),
            onCardClick = { group -> openGroupActions(group) },
            onEditClick = { group -> openGroupEditor(group) }
        )
        recyclerView.adapter = adapter

        closeButton.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        fab.setOnClickListener { showCreateGroupDialog() }

        swipeRefresh.setOnRefreshListener { loadGroups(forceRefresh = true) }

        parentFragmentManager.setFragmentResultListener(REQUEST_GROUPS_REFRESH, viewLifecycleOwner) { _, _ ->
            loadGroups(forceRefresh = true)
        }

        val cached = TrackerRepository.getGroupsCache()
        if (cached != null) {
            TrackerRepository.getMapVisibility(requireContext()) { visibility ->
                if (!isAdded) return@getMapVisibility
                val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
                val visible = cached.filter { g ->
                    if (g.is_owner == true) g.hidden_in_list != true else g.id !in hiddenGroupIds
                }
                requireActivity().runOnUiThread {
                    applyGroups(visible.sortedBy { it.name.lowercase() })
                }
            }
        } else {
            loadingOverlay.visibility = View.VISIBLE
            loadingSpinner.start()
            loadGroups(forceRefresh = false)
        }
    }

    companion object {
        const val REQUEST_GROUPS_REFRESH = "groups_refresh"
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
        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
            TrackerRepository.getGroups(requireContext(), forceRefresh = forceRefresh) { list ->
                if (!isAdded) return@getGroups
                val raw = list ?: emptyList()
                val visible = raw.filter { g ->
                    if (g.is_owner == true) g.hidden_in_list != true else g.id !in hiddenGroupIds
                }
                val sorted = visible.sortedBy { it.name.lowercase() }
                requireActivity().runOnUiThread {
                    applyGroups(sorted)
                }
            }
        }
    }

    private fun showCreateGroupDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.new_group_name_hint)
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
                                    loadGroups(forceRefresh = true)
                                    (activity as? MainActivity)?.showSnackbar(getString(R.string.saved_tracker))
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
            .addToBackStack(null)
            .commit()
    }

    private class GroupsAdapter(
        private var groups: List<Group>,
        private val onCardClick: (Group) -> Unit,
        private val onEditClick: (Group) -> Unit
    ) : RecyclerView.Adapter<GroupsAdapter.ViewHolder>() {

        fun setGroups(list: List<Group>) {
            groups = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_card, parent, false)
            return ViewHolder(view, onCardClick, onEditClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(groups[position])
        }

        override fun getItemCount(): Int = groups.size

        class ViewHolder(
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
                val ownerStr = if (group.is_owner == true) " · Owner" else ""
                meta.text = "$tracks tracks$ownerStr"
                editButton.visibility = View.VISIBLE
                content.setOnClickListener { onCardClick(group) }
                editButton.setOnClickListener { onEditClick(group) }
            }
        }
    }
}

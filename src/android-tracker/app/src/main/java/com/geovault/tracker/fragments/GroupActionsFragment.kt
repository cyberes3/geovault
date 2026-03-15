package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.geovault.tracker.Group
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository

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
        val pointsList = view.findViewById<LinearLayout>(R.id.groupActionsPointsList)
        val emptyView = view.findViewById<TextView>(R.id.groupActionsEmpty)
        val actionViewOnMap = view.findViewById<TextView>(R.id.groupActionViewOnMap)
        val actionLeave = view.findViewById<TextView>(R.id.groupActionLeave)

        title.text = g.name
        closeButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }

        actionViewOnMap.setOnClickListener {
            (activity as? MainActivity)?.setInitialGroupForMap(g)
            (activity as? MainActivity)?.setCurrentTab(1, forceRefreshMap = true, delayMs = 50)
            requireActivity().supportFragmentManager.popBackStack()
        }
        actionLeave.visibility = if (g.is_owner == true) View.GONE else View.VISIBLE
        actionLeave.setOnClickListener { confirmLeaveGroup(g) }

        loadPointsList(g, pointsList, emptyView)
    }

    private fun loadPointsList(g: Group, pointsList: LinearLayout, emptyView: TextView) {
        val trackIds = g.track_ids ?: emptyList()
        if (trackIds.isEmpty()) {
            pointsList.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            return
        }
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val idToName = (list ?: emptyList()).associate { it.id to it.name }
            requireActivity().runOnUiThread {
                pointsList.removeAllViews()
                pointsList.visibility = View.VISIBLE
                emptyView.visibility = View.GONE
                for (trackId in trackIds) {
                    val displayName = idToName[trackId] ?: trackId
                    val row = layoutInflater.inflate(android.R.layout.simple_list_item_1, pointsList, false)
                    (row as? TextView)?.text = displayName
                    row.setPadding(
                        row.paddingLeft + 8,
                        row.paddingTop,
                        row.paddingRight + 8,
                        row.paddingBottom
                    )
                    pointsList.addView(row)
                }
            }
        }
    }

    private fun confirmLeaveGroup(group: Group) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.group_leave_confirm_title))
            .setMessage(getString(R.string.group_leave_confirm_message))
            .setPositiveButton(getString(R.string.leave_group)) { _, _ ->
                TrackerRepository.leaveGroup(requireContext(), group.id) { success ->
                    if (!isAdded) return@leaveGroup
                    requireActivity().runOnUiThread {
                        if (success) {
                            (activity as? MainActivity)?.showSnackbar(getString(R.string.removed_from_share))
                            parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                            requireActivity().supportFragmentManager.popBackStack()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    companion object {
        private const val ARG_GROUP = "group"
        fun newInstance(group: Group): GroupActionsFragment {
            return GroupActionsFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_GROUP, group) }
            }
        }
    }
}

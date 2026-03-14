package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class GroupDetailBottomSheet : BottomSheetDialogFragment() {

    private lateinit var nameEdit: EditText
    private lateinit var tracksList: LinearLayout
    private lateinit var addTrackButton: com.google.android.material.button.MaterialButton
    private lateinit var membersList: LinearLayout
    private lateinit var addMemberButton: com.google.android.material.button.MaterialButton
    private lateinit var leaveButton: com.google.android.material.button.MaterialButton
    private lateinit var deleteButton: com.google.android.material.button.MaterialButton

    private var group: Group? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameEdit = view.findViewById(R.id.groupDetailName)
        tracksList = view.findViewById(R.id.groupDetailTracksList)
        addTrackButton = view.findViewById(R.id.groupDetailAddTrack)
        membersList = view.findViewById(R.id.groupDetailMembersList)
        addMemberButton = view.findViewById(R.id.groupDetailAddMember)
        leaveButton = view.findViewById(R.id.groupDetailLeave)
        deleteButton = view.findViewById(R.id.groupDetailDelete)

        val groupId = arguments?.getString(ARG_GROUP_ID) ?: return
        loadGroup(groupId)
    }

    private fun loadGroup(groupId: String) {
        TrackerRepository.getGroup(requireContext(), groupId) { g ->
            if (!isAdded) return@getGroup
            requireActivity().runOnUiThread {
                group = g
                if (g != null) bindGroup(g)
            }
        }
    }

    private fun bindGroup(g: Group) {
        nameEdit.setText(g.name)
        val isOwner = g.is_owner == true
        nameEdit.isEnabled = isOwner
        addTrackButton.visibility = if (isOwner) View.VISIBLE else View.GONE
        addMemberButton.visibility = if (isOwner) View.VISIBLE else View.GONE
        leaveButton.visibility = if (isOwner) View.GONE else View.VISIBLE
        deleteButton.visibility = if (isOwner) View.VISIBLE else View.GONE

        tracksList.removeAllViews()
        for (trackId in g.track_ids) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_1, tracksList, false)
            (row as? TextView)?.text = trackId.take(8) + "…"
            if (isOwner) {
                val remove = TextView(requireContext()).apply {
                    text = " ×"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                    setOnClickListener { removeTrack(g.id, trackId) }
                }
                val rowWrap = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    addView(row, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(remove)
                }
                tracksList.addView(rowWrap)
            } else {
                tracksList.addView(row)
            }
        }

        membersList.removeAllViews()
        val memberIds = g.member_ids
        val memberEmails = g.member_emails
        for (i in memberEmails.indices) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_1, membersList, false)
            (row as? TextView)?.text = memberEmails.getOrNull(i) ?: ""
            if (isOwner && i < memberIds.size) {
                val uid = memberIds[i]
                val remove = TextView(requireContext()).apply {
                    text = " ×"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                    setOnClickListener { removeMember(g.id, uid) }
                }
                val rowWrap = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    addView(row, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(remove)
                }
                membersList.addView(rowWrap)
            } else {
                membersList.addView(row)
            }
        }

        addTrackButton.setOnClickListener { showAddTrackDialog(g) }
        addMemberButton.setOnClickListener { showAddMemberDialog(g) }
        leaveButton.setOnClickListener { confirmLeave(g) }
        deleteButton.setOnClickListener { confirmDelete(g) }

        nameEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isOwner) saveName(g)
        }
    }

    private fun saveName(g: Group) {
        val name = nameEdit.text?.toString()?.trim()
        if (name.isNullOrEmpty() || name == g.name) return
        TrackerRepository.patchGroup(requireContext(), g.id, GroupPatchRequest(name = name)) { updated ->
            if (isAdded && updated != null) {
                requireActivity().runOnUiThread {
                    group = updated
                    parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                }
            }
        }
    }

    private fun showAddTrackDialog(g: Group) {
        TrackerRepository.getTrackers(requireContext(), forceRefresh = true) { list ->
            if (!isAdded) return@getTrackers
            val trackers = list ?: emptyList()
            val alreadyInGroup = g.track_ids.toSet()
            val addable = trackers.filter { it.id !in alreadyInGroup }
            requireActivity().runOnUiThread {
                if (addable.isEmpty()) {
                    (activity as? MainActivity)?.showSnackbar("No trackers to add")
                    return@runOnUiThread
                }
                val names = addable.map { it.name }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.add_track_to_group))
                    .setItems(names.toTypedArray()) { _, which ->
                        val trackId = addable[which].id
                        TrackerRepository.addGroupTrack(requireContext(), g.id, trackId) { updated ->
                            if (isAdded && updated != null) {
                                requireActivity().runOnUiThread {
                                    group = updated
                                    bindGroup(updated)
                                    parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                                }
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel_button), null)
                    .show()
            }
        }
    }

    private fun removeTrack(groupId: String, trackId: String) {
        TrackerRepository.removeGroupTrack(requireContext(), groupId, trackId) { success ->
            if (isAdded && success) {
                requireActivity().runOnUiThread { loadGroup(groupId) }
                parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
            }
        }
    }

    private fun showAddMemberDialog(g: Group) {
        TrackerRepository.getUsers(requireContext()) { response ->
            if (!isAdded) return@getUsers
            val users = response?.users ?: emptyList()
            val existing = g.member_emails.map { it.lowercase() }.toSet()
            val addable = users.filter { !existing.contains(it.email.trim().lowercase()) }
            requireActivity().runOnUiThread {
                if (addable.isEmpty()) {
                    (activity as? MainActivity)?.showSnackbar("No users to add")
                    return@runOnUiThread
                }
                val emails = addable.map { it.email }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.add_member_to_group))
                    .setItems(emails.toTypedArray()) { _, which ->
                        val email = addable[which].email.trim()
                        TrackerRepository.addGroupMember(requireContext(), g.id, email) { updated ->
                            if (isAdded && updated != null) {
                                requireActivity().runOnUiThread {
                                    group = updated
                                    bindGroup(updated)
                                    parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                                }
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel_button), null)
                    .show()
            }
        }
    }

    private fun removeMember(groupId: String, userId: String) {
        TrackerRepository.removeGroupMember(requireContext(), groupId, userId) { success ->
            if (isAdded && success) {
                requireActivity().runOnUiThread { loadGroup(groupId) }
                parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
            }
        }
    }

    private fun confirmLeave(g: Group) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.group_leave_confirm_title))
            .setMessage(getString(R.string.group_leave_confirm_message))
            .setPositiveButton(getString(R.string.leave_group)) { _, _ ->
                TrackerRepository.leaveGroup(requireContext(), g.id) { success ->
                    if (isAdded && success) {
                        requireActivity().runOnUiThread {
                            dismiss()
                            parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                            (activity as? MainActivity)?.showSnackbar(getString(R.string.removed_from_share))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun confirmDelete(g: Group) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.group_delete_confirm_title))
            .setMessage(getString(R.string.group_delete_confirm_message))
            .setPositiveButton(getString(R.string.delete_group)) { _, _ ->
                TrackerRepository.deleteGroup(requireContext(), g.id) { success ->
                    if (isAdded && success) {
                        requireActivity().runOnUiThread {
                            dismiss()
                            parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                            (activity as? MainActivity)?.showSnackbar(getString(R.string.tracker_deleted))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"

        fun newInstance(group: Group): GroupDetailBottomSheet {
            return GroupDetailBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_GROUP_ID, group.id) }
            }
        }
    }
}

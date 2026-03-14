package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import com.geovault.common.GeovaultAuthManager
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
    private lateinit var leaveButton: com.google.android.material.button.MaterialButton
    private lateinit var deleteButton: com.google.android.material.button.MaterialButton
    private lateinit var sharingSectionHeader: TextView
    private lateinit var visibilityHeader: TextView
    private lateinit var visibilitySpinner: Spinner
    private lateinit var sharedWithList: LinearLayout
    private lateinit var addSharedWithButton: com.google.android.material.button.MaterialButton
    private lateinit var worldShareRow: LinearLayout
    private lateinit var worldShareSwitch: SwitchCompat
    private lateinit var copyWorldLinkButton: com.google.android.material.button.MaterialButton

    private var group: Group? = null
    private val visibilityValues = arrayOf("private", "shared", "public")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameEdit = view.findViewById(R.id.groupDetailName)
        tracksList = view.findViewById(R.id.groupDetailTracksList)
        addTrackButton = view.findViewById(R.id.groupDetailAddTrack)
        leaveButton = view.findViewById(R.id.groupDetailLeave)
        deleteButton = view.findViewById(R.id.groupDetailDelete)
        sharingSectionHeader = view.findViewById(R.id.groupDetailSharingSectionHeader)
        visibilityHeader = view.findViewById(R.id.groupDetailSharingHeader)
        visibilitySpinner = view.findViewById(R.id.groupDetailVisibility)
        sharedWithList = view.findViewById(R.id.groupDetailSharedWithList)
        addSharedWithButton = view.findViewById(R.id.groupDetailAddSharedWith)
        worldShareRow = view.findViewById(R.id.groupDetailWorldShareRow)
        worldShareSwitch = view.findViewById(R.id.groupDetailWorldShareSwitch)
        copyWorldLinkButton = view.findViewById(R.id.groupDetailCopyWorldLink)

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

        addTrackButton.setOnClickListener { showAddTrackDialog(g) }
        leaveButton.setOnClickListener { confirmLeave(g) }
        deleteButton.setOnClickListener { confirmDelete(g) }

        sharingSectionHeader.visibility = View.VISIBLE
        if (isOwner) {
            visibilityHeader.visibility = View.VISIBLE
            visibilitySpinner.visibility = View.VISIBLE
            worldShareRow.visibility = View.VISIBLE
            val visIndex = visibilityValues.indexOf(g.visibility ?: "private").coerceIn(0, visibilityValues.size - 1)
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf(getString(R.string.visibility_private), getString(R.string.visibility_shared), getString(R.string.visibility_public)))
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            visibilitySpinner.adapter = adapter
            visibilitySpinner.setSelection(visIndex)
            visibilitySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val vis = visibilityValues[position]
                    if (vis != (group?.visibility ?: "private")) {
                        patchGroupSharing(g, visibility = vis, sharedWithEmails = if (vis == "shared") (g.shared_with_emails ?: emptyList()) else null, worldShareEnabled = null)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
            val sharedWithEmails = g.shared_with_emails ?: emptyList()
            if (g.visibility == "shared") {
                sharedWithList.visibility = View.VISIBLE
                addSharedWithButton.visibility = View.VISIBLE
                bindSharedWithList(g, sharedWithEmails)
                addSharedWithButton.setOnClickListener { showAddSharedWithDialog(g) }
            } else {
                sharedWithList.visibility = View.GONE
                addSharedWithButton.visibility = View.GONE
            }
            worldShareSwitch.isChecked = !g.world_share_id.isNullOrBlank()
            copyWorldLinkButton.visibility = if (g.world_share_url != null) View.VISIBLE else View.GONE
            worldShareSwitch.setOnCheckedChangeListener { _, isChecked ->
                patchGroupSharing(g, visibility = null, sharedWithEmails = null, worldShareEnabled = isChecked)
            }
            copyWorldLinkButton.setOnClickListener {
                val url = g.world_share_url
                if (!url.isNullOrBlank()) {
                    val base = GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                    val fullUrl = if (url.startsWith("http")) url else "$base$url"
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("World share link", fullUrl))
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.world_link_copied))
                }
            }
        } else {
            visibilityHeader.visibility = View.GONE
            visibilitySpinner.visibility = View.GONE
            sharedWithList.visibility = View.GONE
            addSharedWithButton.visibility = View.GONE
            worldShareRow.visibility = View.GONE
        }

        nameEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isOwner) saveName(g)
        }
    }

    private fun bindSharedWithList(g: Group, emails: List<String>) {
        sharedWithList.removeAllViews()
        for (email in emails) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_1, sharedWithList, false)
            (row as? TextView)?.text = email
            val remove = TextView(requireContext()).apply {
                text = " ×"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                setOnClickListener {
                    val newList = (g.shared_with_emails ?: emptyList()).filter { it != email }
                    patchGroupSharing(g, visibility = "shared", sharedWithEmails = newList, worldShareEnabled = null)
                }
            }
            val rowWrap = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(row, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(remove)
            }
            sharedWithList.addView(rowWrap)
        }
    }

    private fun showAddSharedWithDialog(g: Group) {
        TrackerRepository.getUsers(requireContext()) { response ->
            if (!isAdded) return@getUsers
            val users = response?.users ?: emptyList()
            val existing = (g.shared_with_emails ?: emptyList()).map { it.lowercase() }.toSet()
            val addable = users.filter { !existing.contains(it.email.trim().lowercase()) }
            requireActivity().runOnUiThread {
                if (addable.isEmpty()) {
                    (activity as? MainActivity)?.showSnackbar("No users to add")
                    return@runOnUiThread
                }
                val emails = addable.map { it.email }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.shared_with_recipients_label))
                    .setItems(emails.toTypedArray()) { _, which ->
                        val email = addable[which].email.trim()
                        val newList = (g.shared_with_emails ?: emptyList()) + email
                        patchGroupSharing(g, visibility = "shared", sharedWithEmails = newList, worldShareEnabled = null)
                    }
                    .setNegativeButton(getString(R.string.cancel_button), null)
                    .show()
            }
        }
    }

    private fun patchGroupSharing(g: Group, visibility: String?, sharedWithEmails: List<String>?, worldShareEnabled: Boolean?) {
        TrackerRepository.patchGroup(requireContext(), g.id, GroupPatchRequest(
            visibility = visibility,
            shared_with_emails = sharedWithEmails,
            world_share_enabled = worldShareEnabled
        )) { updated ->
            if (isAdded && updated != null) {
                requireActivity().runOnUiThread {
                    group = updated
                    bindGroup(updated)
                    parentFragmentManager.setFragmentResult(GroupsFragment.REQUEST_GROUPS_REFRESH, Bundle())
                }
            }
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

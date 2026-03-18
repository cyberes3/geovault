package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.common.R as CommonR
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupDetailBottomSheet : Fragment() {
    private lateinit var nameEdit: EditText
    private lateinit var tracksRow: View
    private lateinit var tracksCountText: TextView
    private lateinit var deleteButton: com.google.android.material.button.MaterialButton
    private lateinit var sharingSectionHeader: TextView
    private lateinit var visibilityHeader: TextView
    private lateinit var visibilityLayout: View
    private lateinit var visibilitySpinner: AutoCompleteTextView
    private lateinit var pickUsersButton: com.google.android.material.button.MaterialButton
    private lateinit var sharedWithCountText: TextView
    private lateinit var worldShareRow: LinearLayout
    private lateinit var worldShareSwitch: SwitchCompat
    private lateinit var copyWorldLinkButton: com.google.android.material.button.MaterialButton
    private lateinit var copyWorldLinkSpinner: LoadingSpinner
    private lateinit var hideInListRow: LinearLayout
    private lateinit var hideInListSwitch: SwitchCompat
    private lateinit var closeButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private lateinit var saveButton: MaterialButton

    private var group: Group? = null
    private var preloadedAddableTrackers: List<Tracker>? = null
    private val visibilityValues = arrayOf("private", "shared", "public")
    private var selectedVisibilityIndex = 0
    private val sharedWithEmailsForSave = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameEdit = view.findViewById(R.id.groupDetailName)
        tracksRow = view.findViewById(R.id.groupDetailTracksRow)
        tracksCountText = view.findViewById(R.id.groupDetailTracksCountText)
        deleteButton = view.findViewById(R.id.groupDetailDelete)
        sharingSectionHeader = view.findViewById(R.id.sharing_section_header)
        visibilityHeader = view.findViewById(R.id.sharing_visibility_header)
        visibilityLayout = view.findViewById(R.id.sharing_visibility_layout)
        visibilitySpinner = view.findViewById(R.id.sharing_visibility_spinner)
        pickUsersButton = view.findViewById(R.id.sharing_pick_users_button)
        sharedWithCountText = view.findViewById(R.id.sharing_shared_with_count_text)
        worldShareRow = view.findViewById(R.id.groupDetailWorldShareRow)
        worldShareSwitch = view.findViewById(R.id.groupDetailWorldShareSwitch)
        copyWorldLinkButton = view.findViewById(R.id.groupDetailCopyWorldLink)
        copyWorldLinkSpinner = view.findViewById(R.id.groupDetailCopyWorldLinkSpinner)
        hideInListRow = view.findViewById(R.id.groupDetailHideInListRow)
        hideInListSwitch = view.findViewById(R.id.groupDetailHideInListSwitch)
        closeButton = view.findViewById(R.id.groupDetailCloseButton)
        titleText = view.findViewById(R.id.groupDetailTitle)
        loadingOverlay = view.findViewById(R.id.groupDetailLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.groupDetailLoadingSpinner)
        saveButton = view.findViewById(R.id.groupDetailSave)
        closeButton.setOnClickListener { closeEditor() }
        saveButton.setOnClickListener { performSave() }

        val initialGroup = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        if (initialGroup == null || initialGroup.is_owner != true) {
            closeEditor()
            return
        }
        nameEdit.setText(initialGroup.name)
        showLoading()

        val groupId = arguments?.getString(ARG_GROUP_ID) ?: return
        group = initialGroup
        parentFragmentManager.setFragmentResultListener(
            AddGroupTrackersFragment.REQUEST_GROUP_TRACK_ADDED,
            viewLifecycleOwner
        ) { _, bundle ->
            val updatedGroupId = bundle.getString(AddGroupTrackersFragment.KEY_GROUP_ID) ?: return@setFragmentResultListener
            if (updatedGroupId != groupId) return@setFragmentResultListener
            val updatedGroup = bundle.getParcelable<Group>(AddGroupTrackersFragment.KEY_GROUP, Group::class.java)
            if (updatedGroup != null) {
                group = updatedGroup
                bindGroup(updatedGroup)
            } else {
                loadGroup(updatedGroupId)
            }
        }
        parentFragmentManager.setFragmentResultListener(
            GroupTrackersListFragment.REQUEST_GROUP_TRACK_REMOVED,
            viewLifecycleOwner
        ) { _, bundle ->
            val removedGroupId = bundle.getString(GroupTrackersListFragment.KEY_GROUP_ID) ?: return@setFragmentResultListener
            if (removedGroupId != groupId) return@setFragmentResultListener
            val updatedGroup = bundle.getParcelable<Group>(AddGroupTrackersFragment.KEY_GROUP, Group::class.java)
            if (updatedGroup != null) {
                group = updatedGroup
                bindGroup(updatedGroup)
            } else {
                loadGroup(removedGroupId)
            }
        }
        loadGroup(groupId)
    }

    private fun loadGroup(groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val g = TrackerRepository.getGroupSuspend(requireContext(), groupId)
            group = g
            if (g != null) {
                bindGroup(g)
            } else {
                hideLoading()
                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                closeEditor()
            }
        }
    }

    private fun bindGroup(g: Group) {
        nameEdit.setText(g.name)
        nameEdit.isEnabled = true
        deleteButton.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE

        deleteButton.setOnClickListener { confirmDelete(g) }

        sharedWithEmailsForSave.clear()
        sharedWithEmailsForSave.addAll(g.shared_with_emails ?: emptyList())

        updateTracksCountText()
        tracksRow.setOnClickListener { showGroupTrackersListView(g) }
        preloadAddableTrackers(g)
        view?.findViewById<View>(R.id.sharingSectionInclude)?.visibility = View.VISIBLE
        visibilityHeader.visibility = View.VISIBLE
        visibilityLayout.visibility = View.VISIBLE
        worldShareRow.visibility = View.VISIBLE
        selectedVisibilityIndex = visibilityValues.indexOf(g.visibility ?: "private").coerceIn(0, visibilityValues.size - 1)
        val labels = listOf(getString(R.string.visibility_private), getString(R.string.visibility_shared), getString(R.string.visibility_public))
        val adapter = ArrayAdapter(requireContext(), CommonR.layout.gv_common_item_dropdown, labels)
        visibilitySpinner.setAdapter(adapter)
        visibilitySpinner.setText(labels[selectedVisibilityIndex], false)
        visibilitySpinner.setOnItemClickListener { _, _, position, _ ->
            selectedVisibilityIndex = position
            val isShared = visibilityValues[position] == "shared"
            pickUsersButton.visibility = if (isShared) View.VISIBLE else View.GONE
            sharedWithCountText.visibility = if (isShared) View.VISIBLE else View.GONE
            if (isShared) updateSharedWithCountText()
        }
        if (g.visibility == "shared") {
            pickUsersButton.visibility = View.VISIBLE
            sharedWithCountText.visibility = View.VISIBLE
            updateSharedWithCountText()
            pickUsersButton.setOnClickListener { showAddSharedWithDialog() }
        } else {
            pickUsersButton.visibility = View.GONE
            sharedWithCountText.visibility = View.GONE
        }
        worldShareSwitch.isChecked = !g.world_share_id.isNullOrBlank()
        copyWorldLinkButton.visibility = if (g.world_share_url != null) View.VISIBLE else View.GONE
        copyWorldLinkButton.setOnClickListener {
            val url = group?.world_share_url
            if (!url.isNullOrBlank()) {
                val base = GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                val fullUrl = if (url.startsWith("http")) url else "$base$url"
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("World share link", fullUrl))
                navHost()?.showSnackbar(getString(R.string.world_link_copied))
            }
        }
        worldShareSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                copyWorldLinkButton.visibility = View.VISIBLE
                worldShareSwitch.isEnabled = false
                copyWorldLinkButton.isEnabled = false
                copyWorldLinkButton.text = ""
                copyWorldLinkSpinner.show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val (updated, _) = TrackerRepository.patchGroupResultSuspend(
                        requireContext(),
                        g.id,
                        GroupPatchRequest(world_share_enabled = true)
                    )
                    worldShareSwitch.isEnabled = true
                    copyWorldLinkButton.isEnabled = true
                    copyWorldLinkButton.text = getString(R.string.copy_world_share_link)
                    copyWorldLinkSpinner.hide()
                    if (updated != null) {
                        group = updated
                        copyWorldLinkButton.visibility = if (updated.world_share_url != null) View.VISIBLE else View.GONE
                    }
                }
            } else {
                copyWorldLinkButton.visibility = View.GONE
            }
        }
        hideInListRow.visibility = View.VISIBLE
        hideInListSwitch.isChecked = g.hidden_in_list == true
        hideInListSwitch.setOnCheckedChangeListener(null)
        hideLoading()
    }

    private fun updateTracksCountText() {
        val n = group?.track_ids?.size ?: 0
        tracksCountText.text = getString(R.string.group_tracks) + " ($n)"
    }

    private fun preloadAddableTrackers(g: Group) {
        val alreadyInGroup = (g.track_ids ?: emptyList()).toSet()
        viewLifecycleOwner.lifecycleScope.launch {
            val list = TrackerRepository.getTrackersSuspend(requireContext(), forceRefresh = false)
            val addable = (list ?: emptyList())
                .filter { tracker ->
                    tracker.id !in alreadyInGroup &&
                        if (tracker.isOwner()) {
                            ((tracker.settings?.get("hidden_in_list") as? Boolean) != true)
                        } else {
                            ((tracker.settings?.get("allow_group_reshare") as? Boolean) == true) &&
                                tracker.visibility == "public"
                        }
                }
            preloadedAddableTrackers = addable
        }
    }

    private fun showGroupTrackersListView(g: Group) {
        parentFragmentManager.beginTransaction()
            .add(
                R.id.fragment_overlay_container,
                GroupTrackersListFragment.newInstance(g, preloadedAddableTrackers),
                "group_trackers_list"
            )
            .addToBackStack("group_trackers_list")
            .commit()
    }

    private fun updateSharedWithCountText() {
        val n = sharedWithEmailsForSave.size
        sharedWithCountText.text = resources.getQuantityString(R.plurals.shared_with_user_count, n, n)
    }

    private fun showAddSharedWithDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val response = TrackerRepository.getUsersSuspend(requireContext())
            val users = response?.users ?: emptyList()
            if (users.isEmpty()) {
                navHost()?.showSnackbar(getString(R.string.no_other_users_found))
                return@launch
            }
            val normalizedUsers = users.map { it.email.trim().lowercase() }.toSet()
            val pinnedExisting = sharedWithEmailsForSave
                .map { it.trim() }
                .filter { it.isNotBlank() && it.lowercase() !in normalizedUsers }
            SharedUserPickerDialog.show(
                fragment = this@GroupDetailBottomSheet,
                title = getString(R.string.shared_with_recipients_label),
                users = users,
                selectedEmails = sharedWithEmailsForSave.toSet()
            ) { picked ->
                sharedWithEmailsForSave.clear()
                sharedWithEmailsForSave.addAll(pinnedExisting)
                sharedWithEmailsForSave.addAll(picked.sorted())
                updateSharedWithCountText()
            }
        }
    }

    private fun performSave() {
        val g = group ?: return
        val name = nameEdit.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            navHost()?.showSnackbar("Name is required")
            return
        }
        val visibility = if (selectedVisibilityIndex in visibilityValues.indices) visibilityValues[selectedVisibilityIndex] else "private"
        val request = GroupPatchRequest(
            name = name,
            hidden_in_list = hideInListSwitch.isChecked,
            visibility = visibility,
            shared_with_emails = if (visibility == "shared") sharedWithEmailsForSave.toList() else null,
            world_share_enabled = worldShareSwitch.isChecked
        )
        setAllInputsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val (updated, errorMessage) = TrackerRepository.patchGroupResultSuspend(requireContext(), g.id, request)
            when {
                updated != null -> {
                    group = updated
                    parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                    closeEditor()
                }
                !errorMessage.isNullOrBlank() -> {
                    setAllInputsEnabled(true)
                    navHost()?.showSnackbar(errorMessage)
                }
                else -> {
                    setAllInputsEnabled(true)
                    navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                }
            }
        }
    }

    private fun setAllInputsEnabled(enabled: Boolean) {
        nameEdit.isEnabled = enabled
        visibilitySpinner.isEnabled = enabled
        pickUsersButton.isEnabled = enabled
        worldShareSwitch.isEnabled = enabled
        hideInListSwitch.isEnabled = enabled
        deleteButton.isEnabled = enabled
        saveButton.isEnabled = enabled
    }

    private fun confirmDelete(g: Group) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.group_delete_confirm_title))
            .setMessage(getString(R.string.group_delete_confirm_message))
            .setPositiveButton(getString(R.string.delete_group)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val success = TrackerRepository.deleteGroupSuspend(requireContext(), g.id)
                    if (isAdded && success) {
                        closeEditor()
                        parentFragmentManager.setFragmentResult(
                            GroupsListFragment.REQUEST_GROUPS_REFRESH,
                            Bundle().apply {
                                putString(GroupsListFragment.KEY_DELETED_GROUP_ID, g.id)
                            }
                        )
                        navHost()?.showSnackbar(getString(R.string.tracker_deleted))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun closeEditor() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun showLoading() {
        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.start()
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
        loadingSpinner.stop(hide = false)
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_GROUP = "group"

        fun newInstance(group: Group): GroupDetailBottomSheet {
            return GroupDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, group.id)
                    putParcelable(ARG_GROUP, group)
                }
            }
        }
    }
}

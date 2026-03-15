package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.LoadingSpinner
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.common.R as CommonR
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.parseHexToColor
import com.google.android.material.button.MaterialButton

class GroupDetailBottomSheet : Fragment() {

    private lateinit var nameEdit: EditText
    private lateinit var tracksList: LinearLayout
    private lateinit var addTrackButton: com.google.android.material.button.MaterialButton
    private lateinit var leaveButton: com.google.android.material.button.MaterialButton
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
    private lateinit var hideInListRow: LinearLayout
    private lateinit var hideInListSwitch: SwitchCompat
    private lateinit var hideInListHint: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private lateinit var saveButton: MaterialButton

    private var group: Group? = null
    /** Track id -> display name for group track list (from getTrackers / available-to-add). */
    private var trackNamesById: Map<String, String> = emptyMap()
    private val visibilityValues = arrayOf("private", "shared", "public")
    private var selectedVisibilityIndex = 0
    private val sharedWithEmailsForSave = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameEdit = view.findViewById(R.id.groupDetailName)
        tracksList = view.findViewById(R.id.groupDetailTracksList)
        addTrackButton = view.findViewById(R.id.groupDetailAddTrack)
        leaveButton = view.findViewById(R.id.groupDetailLeave)
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
        hideInListRow = view.findViewById(R.id.groupDetailHideInListRow)
        hideInListSwitch = view.findViewById(R.id.groupDetailHideInListSwitch)
        hideInListHint = view.findViewById(R.id.groupDetailHideInListHint)
        closeButton = view.findViewById(R.id.groupDetailCloseButton)
        titleText = view.findViewById(R.id.groupDetailTitle)
        loadingOverlay = view.findViewById(R.id.groupDetailLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.groupDetailLoadingSpinner)
        saveButton = view.findViewById(R.id.groupDetailSave)
        closeButton.setOnClickListener { closeEditor() }
        saveButton.setOnClickListener { performSave() }

        val initialGroup = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        if (initialGroup != null) {
            titleText.text = initialGroup.name
            nameEdit.setText(initialGroup.name)
        }
        showLoading()

        val groupId = arguments?.getString(ARG_GROUP_ID) ?: return
        loadGroup(groupId)
    }

    private fun loadGroup(groupId: String) {
        TrackerRepository.getGroup(requireContext(), groupId) { g ->
            if (!isAdded) return@getGroup
            requireActivity().runOnUiThread {
                group = g
                if (g != null) {
                    titleText.text = g.name
                    bindGroup(g)
                } else {
                    hideLoading()
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    closeEditor()
                }
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
        saveButton.visibility = if (isOwner) View.VISIBLE else View.GONE
        (leaveButton.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (isOwner) {
                params.width = 0
                params.weight = 1f
                params.marginEnd = (8 * resources.displayMetrics.density).toInt()
            } else {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.weight = 0f
                params.marginEnd = 0
            }
            leaveButton.layoutParams = params
        }

        addTrackButton.setOnClickListener { showAddTrackDialog(g) }
        leaveButton.setOnClickListener { confirmLeave(g) }
        deleteButton.setOnClickListener { confirmDelete(g) }

        sharedWithEmailsForSave.clear()
        sharedWithEmailsForSave.addAll(g.shared_with_emails ?: emptyList())

        loadTrackNamesThenBindTracks(g, isOwner) { hideLoading() }
        view?.findViewById<View>(R.id.sharingSectionInclude)?.visibility = if (isOwner) View.VISIBLE else View.GONE
        if (isOwner) {
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
            visibilityLayout.visibility = View.GONE
            pickUsersButton.visibility = View.GONE
            sharedWithCountText.visibility = View.GONE
            worldShareRow.visibility = View.GONE
        }
        hideInListRow.visibility = View.VISIBLE
        hideInListHint.visibility = View.VISIBLE
        if (isOwner) {
            hideInListSwitch.isChecked = g.hidden_in_list == true
            hideInListSwitch.setOnCheckedChangeListener(null)
        } else {
            TrackerRepository.getMapVisibility(requireContext()) { visibility ->
                if (!isAdded) return@getMapVisibility
                val hiddenGroupIds = (visibility?.hidden_group_ids ?: emptyList()).toSet()
                val isHiddenInMapPrefs = g.id in hiddenGroupIds
                requireActivity().runOnUiThread {
                    hideInListSwitch.isChecked = isHiddenInMapPrefs
                    hideInListSwitch.setOnCheckedChangeListener { _, isChecked ->
                        TrackerRepository.getMapVisibility(requireContext()) { vis ->
                            if (!isAdded) return@getMapVisibility
                            val current = (vis?.hidden_group_ids ?: emptyList()).toSet()
                            val newIds = if (isChecked) current + g.id else current - g.id
                            TrackerRepository.patchMapVisibility(
                                requireContext(),
                                com.geovault.tracker.MapVisibilityRequest(hidden_group_ids = newIds.toList())
                            ) { updated ->
                                if (!isAdded) return@patchMapVisibility
                                requireActivity().runOnUiThread {
                                    if (updated != null) {
                                        parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                                    } else {
                                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                                        hideInListSwitch.isChecked = !isChecked
                                    }
                                }
                            }
                        }
                    }
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
                    (activity as? MainActivity)?.openMapForGroup(group, tracker.id)
                    true
                }
                MENU_VIEW_PARAMS -> {
                    (activity as? MainActivity)?.showTrackerParamsFragment(tracker.id, tracker.name)
                    closeEditor()
                    true
                }
                MENU_VIEW_IN_LIST -> {
                    closeEditor()
                    (activity as? MainActivity)?.openTrackersAndScrollTo(tracker.id)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Load trackers (from getTrackers) then populate tracksList with card rows; owner sees remove. */
    private fun loadTrackNamesThenBindTracks(g: Group, isOwner: Boolean, onDone: () -> Unit) {
        tracksList.removeAllViews()
        val trackIds = g.track_ids ?: emptyList()
        if (trackIds.isEmpty()) {
            onDone()
            return
        }
        TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
            if (!isAdded) return@getTrackers
            val all = list ?: emptyList()
            val idToTracker = all.associateBy { it.id }
            val ordered = trackIds.mapNotNull { idToTracker[it] }
            trackNamesById = all.associate { it.id to it.name }
            requireActivity().runOnUiThread {
                for (tracker in ordered) {
                    val card = layoutInflater.inflate(R.layout.item_group_tracker_card, tracksList, false)
                    card.findViewById<TextView>(R.id.groupTrackerName).text = tracker.name
                    card.findViewById<ImageView>(R.id.groupTrackerChevronIcon).setColorFilter(
                        parseHexToColor(tracker.color, card.context)
                    )
                    val menuBtn = card.findViewById<ImageButton>(R.id.groupTrackerMenu)
                    menuBtn.visibility = View.GONE
                    val removeBtn = card.findViewById<ImageButton>(R.id.groupTrackerRemove)
                    if (isOwner) {
                        removeBtn.visibility = View.VISIBLE
                        removeBtn.setOnClickListener { removeTrack(g.id, tracker.id) }
                    } else {
                        removeBtn.visibility = View.GONE
                    }
                    card.isClickable = true
                    card.setOnClickListener {
                        group?.let {
                            (activity as? MainActivity)?.openMapForGroup(it, tracker.id)
                        }
                    }
                    tracksList.addView(card)
                }
                onDone()
            }
        }
    }

    private fun updateSharedWithCountText() {
        val n = sharedWithEmailsForSave.size
        sharedWithCountText.text = resources.getQuantityString(R.plurals.shared_with_user_count, n, n)
    }

    private fun showAddSharedWithDialog() {
        TrackerRepository.getUsers(requireContext()) { response ->
            if (!isAdded) return@getUsers
            requireActivity().runOnUiThread {
                val users = response?.users ?: emptyList()
                if (users.isEmpty()) {
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.no_other_users_found))
                    return@runOnUiThread
                }
                val normalizedUsers = users.map { it.email.trim().lowercase() }.toSet()
                val pinnedExisting = sharedWithEmailsForSave
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.lowercase() !in normalizedUsers }
                SharedUserPickerDialog.show(
                    fragment = this,
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
    }

    private fun performSave() {
        val g = group ?: return
        val name = nameEdit.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            (activity as? MainActivity)?.showSnackbar("Name is required")
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
        TrackerRepository.patchGroup(requireContext(), g.id, request) { updated, errorMessage ->
            if (!isAdded) return@patchGroup
            requireActivity().runOnUiThread {
                when {
                    updated != null -> {
                        group = updated
                        titleText.text = updated.name
                        parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                        closeEditor()
                    }
                    !errorMessage.isNullOrBlank() -> {
                        setAllInputsEnabled(true)
                        (activity as? MainActivity)?.showSnackbar(errorMessage)
                    }
                    else -> {
                        setAllInputsEnabled(true)
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    }
                }
            }
        }
    }

    private fun setAllInputsEnabled(enabled: Boolean) {
        nameEdit.isEnabled = enabled
        addTrackButton.isEnabled = enabled
        visibilitySpinner.isEnabled = enabled
        pickUsersButton.isEnabled = enabled
        worldShareSwitch.isEnabled = enabled
        hideInListSwitch.isEnabled = enabled
        leaveButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
        saveButton.isEnabled = enabled
    }

    private fun showAddTrackDialog(g: Group) {
        val alreadyInGroup = (g.track_ids ?: emptyList()).toSet()
        TrackerRepository.getAvailableToAdd(requireContext()) { response ->
            if (!isAdded) return@getAvailableToAdd
            val avail = response ?: return@getAvailableToAdd
            val idToName = mutableMapOf<String, String>()
            (avail.public + avail.shared_with_me).forEach { item ->
                idToName[item.id] = item.name
            }
            val addableIds = (avail.public.map { it.id } + avail.shared_with_me.map { it.id } +
                avail.shared_with_me_groups.flatMap { it.track_ids } + avail.public_groups.flatMap { it.track_ids })
                .distinct().filter { it !in alreadyInGroup }
            if (addableIds.isEmpty()) {
                requireActivity().runOnUiThread {
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.discover_empty))
                }
                return@getAvailableToAdd
            }
            TrackerRepository.getTrackers(requireContext(), forceRefresh = false) { list ->
                if (!isAdded) return@getTrackers
                (list ?: emptyList()).forEach { t -> idToName[t.id] = t.name }
                val addable = addableIds.map { id -> id to (idToName[id] ?: id) }.sortedBy { it.second.lowercase() }
                requireActivity().runOnUiThread {
                    val labels = addable.map { it.second }.toTypedArray()
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.add_track_to_group))
                        .setItems(labels) { _, which ->
                            val trackId = addable[which].first
                            TrackerRepository.addGroupTrack(requireContext(), g.id, trackId) { updated ->
                                if (isAdded && updated != null) {
                                    requireActivity().runOnUiThread {
                                        group = updated
                                        bindGroup(updated)
                                        parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                                    }
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel_button), null)
                        .show()
                }
            }
        }
    }

    private fun removeTrack(groupId: String, trackId: String) {
        TrackerRepository.removeGroupTrack(requireContext(), groupId, trackId) { success ->
            if (isAdded && success) {
                requireActivity().runOnUiThread { loadGroup(groupId) }
                parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
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
                            closeEditor()
                            parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
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
                            closeEditor()
                            parentFragmentManager.setFragmentResult(GroupsListFragment.REQUEST_GROUPS_REFRESH, Bundle())
                            (activity as? MainActivity)?.showSnackbar(getString(R.string.tracker_deleted))
                        }
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
        private const val MENU_VIEW_ON_MAP = 1
        private const val MENU_VIEW_PARAMS = 2
        private const val MENU_VIEW_IN_LIST = 3

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

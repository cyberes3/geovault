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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.LoadingSpinner
import com.geovault.common.NaturalSort
import com.geovault.common.ToggleHelpCardView
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.ui.applyDialogButtonColors
import com.geovault.common.R as CommonR
import com.geovault.tracker.Tracker
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@AndroidEntryPoint
class GroupDetailFragment : Fragment() {
    private val viewModel: GroupDetailViewModel by activityViewModels()
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
    private lateinit var worldShareRow: ToggleHelpCardView
    private lateinit var worldShareCopyRow: View
    private lateinit var copyWorldLinkButton: com.google.android.material.button.MaterialButton
    private lateinit var copyWorldLinkSpinner: LoadingSpinner
    private lateinit var hideInListRow: ToggleHelpCardView
    private lateinit var closeButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner
    private lateinit var saveButton: MaterialButton

    private var group: Group? = null
    private val visibilityValues = arrayOf("private", "shared", "public")
    private var selectedVisibilityIndex = 0
    private val sharedWithEmailsForSave = mutableListOf<String>()
    private var isRenderingState = false
    private var pendingAction: PendingAction? = null

    private enum class PendingAction {
        SAVE,
        DELETE,
        ENABLE_WORLD_SHARE,
        DISABLE_WORLD_SHARE
    }

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
        worldShareCopyRow = view.findViewById(R.id.groupDetailWorldShareCopyRow)
        copyWorldLinkButton = view.findViewById(R.id.groupDetailCopyWorldLink)
        copyWorldLinkSpinner = view.findViewById(R.id.groupDetailCopyWorldLinkSpinner)
        hideInListRow = view.findViewById(R.id.groupDetailHideInListRow)
        closeButton = view.findViewById(R.id.groupDetailCloseButton)
        titleText = view.findViewById(R.id.groupDetailTitle)
        loadingOverlay = view.findViewById(R.id.groupDetailLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.groupDetailLoadingSpinner)
        saveButton = view.findViewById(R.id.groupDetailSave)
        closeButton.setOnClickListener { tryClose() }
        saveButton.setOnClickListener { performSave() }

        val initialGroup = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        if (initialGroup == null || initialGroup.is_owner != true) {
            closeEditor()
            return
        }
        if ((nameEdit.text?.toString() ?: "") != initialGroup.name) {
            nameEdit.setText(initialGroup.name)
        }
        showLoading()

        val groupId = arguments?.getString(ARG_GROUP_ID) ?: return
        group = initialGroup
        observeViewModel()
        viewModel.load(groupId)
        viewModel.onNameChanged(initialGroup.name)

        nameEdit.doAfterTextChanged {
            if (!isRenderingState) viewModel.onNameChanged(it?.toString() ?: "")
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state.phase) {
                        GroupDetailPhase.Loading -> showLoading()
                        GroupDetailPhase.Ready -> {
                            hideLoading()
                            if (state.group != null) {
                                group = state.group
                            }
                            bindState(state.form)
                            setAllInputsEnabled(true)
                            copyWorldLinkButton.isEnabled = true
                            copyWorldLinkButton.text = getString(R.string.copy_world_share_link)
                            copyWorldLinkSpinner.hide()
                            if (
                                pendingAction == PendingAction.ENABLE_WORLD_SHARE ||
                                pendingAction == PendingAction.DISABLE_WORLD_SHARE
                            ) {
                                pendingAction = null
                            }
                        }
                        GroupDetailPhase.Saving -> {
                            hideLoading()
                            setAllInputsEnabled(false)
                        }
                        GroupDetailPhase.Saved -> {
                            pendingAction = null
                            closeEditor(discardDraft = false)
                        }
                        GroupDetailPhase.Deleting -> setAllInputsEnabled(false)
                        GroupDetailPhase.Deleted -> {
                            pendingAction = null
                            closeEditor(discardDraft = false)
                            navHost()?.showSnackbar(getString(R.string.tracker_deleted))
                        }
                    }
                    if (!state.errorMessage.isNullOrBlank()) {
                        setAllInputsEnabled(true)
                        val failedAction = pendingAction
                        pendingAction = null
                        val failureMessageRes = when (failedAction) {
                            PendingAction.SAVE -> R.string.failed_to_save_tracker
                            PendingAction.DELETE -> R.string.failed_to_delete_tracker
                            PendingAction.ENABLE_WORLD_SHARE -> R.string.failed_to_enable_world_share
                            PendingAction.DISABLE_WORLD_SHARE -> R.string.failed_to_disable_world_share
                            null -> R.string.failed_to_load_tracker
                        }
                        navHost()?.showSnackbar(getString(failureMessageRes))
                    }
                }
            }
        }
    }

    private fun bindState(form: GroupDetailFormState) {
        isRenderingState = true
        if (!nameEdit.hasFocus() && (nameEdit.text?.toString() ?: "") != form.name) {
            nameEdit.setText(form.name)
        }
        nameEdit.isEnabled = true
        deleteButton.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE

        val g = group
        if (g != null) {
            deleteButton.setOnClickListener { confirmDelete(g) }
        }

        sharedWithEmailsForSave.clear()
        sharedWithEmailsForSave.addAll(form.sharedWithEmails)

        updateTracksCountText(viewModel.uiState.value.draftTrackIds.size)
        if (g != null) {
            tracksRow.setOnClickListener { showGroupTrackersListView(g) }
        }
        view?.findViewById<View>(R.id.sharingSectionInclude)?.visibility = View.VISIBLE
        visibilityHeader.visibility = View.VISIBLE
        visibilityLayout.visibility = View.VISIBLE
        worldShareRow.visibility = View.VISIBLE
        worldShareCopyRow.visibility = View.VISIBLE
        selectedVisibilityIndex = visibilityValues.indexOf(form.visibility).coerceIn(0, visibilityValues.size - 1)
        val labels = listOf(getString(R.string.visibility_private), getString(R.string.visibility_shared), getString(R.string.visibility_public))
        val adapter = ArrayAdapter(requireContext(), CommonR.layout.gv_common_item_dropdown, labels)
        visibilitySpinner.setAdapter(adapter)
        visibilitySpinner.setText(labels[selectedVisibilityIndex], false)
        visibilitySpinner.setOnItemClickListener { _, _, position, _ ->
            selectedVisibilityIndex = position
            if (!isRenderingState) {
                val visibility = if (position in visibilityValues.indices) visibilityValues[position] else "private"
                viewModel.onVisibilityChanged(visibility)
            }
            val isShared = visibilityValues[position] == "shared"
            pickUsersButton.visibility = if (isShared) View.VISIBLE else View.GONE
            sharedWithCountText.visibility = if (isShared) View.VISIBLE else View.GONE
            if (isShared) updateSharedWithCountText()
        }
        if (form.visibility == "shared") {
            pickUsersButton.visibility = View.VISIBLE
            sharedWithCountText.visibility = View.VISIBLE
            updateSharedWithCountText()
            pickUsersButton.setOnClickListener { showAddSharedWithDialog() }
        } else {
            pickUsersButton.visibility = View.GONE
            sharedWithCountText.visibility = View.GONE
        }
        worldShareRow.isChecked = form.worldShareEnabled
        copyWorldLinkButton.visibility = if (form.worldShareUrl != null) View.VISIBLE else View.GONE
        copyWorldLinkButton.setOnClickListener {
            val url = viewModel.uiState.value.form.worldShareUrl
            if (!url.isNullOrBlank()) {
                val base = GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                val fullUrl = if (url.startsWith("http")) url else "$base$url"
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("World share link", fullUrl))
                navHost()?.showSnackbar(getString(R.string.world_link_copied))
            }
        }
        worldShareRow.setOnCheckedChangeListener { _, isChecked ->
            if (isRenderingState) return@setOnCheckedChangeListener
            viewModel.onWorldShareEnabledChanged(isChecked)
            if (isChecked) {
                pendingAction = PendingAction.ENABLE_WORLD_SHARE
                copyWorldLinkButton.visibility = View.VISIBLE
                worldShareRow.isEnabled = false
                copyWorldLinkButton.isEnabled = false
                copyWorldLinkButton.text = ""
                copyWorldLinkSpinner.show()
                viewModel.enableWorldShare()
            } else {
                copyWorldLinkButton.visibility = View.GONE
                pendingAction = PendingAction.DISABLE_WORLD_SHARE
                viewModel.disableWorldShare()
            }
        }
        hideInListRow.visibility = View.VISIBLE
        hideInListRow.isChecked = form.hiddenInList
        hideInListRow.setOnCheckedChangeListener { _, isChecked ->
            if (!isRenderingState) viewModel.onHiddenInListChanged(isChecked)
        }
        hideLoading()
        isRenderingState = false
    }

    private fun updateTracksCountText(n: Int) {
        tracksCountText.text = getString(R.string.group_tracks) + " ($n)"
    }

    private fun showGroupTrackersListView(g: Group) {
        parentFragmentManager.beginTransaction()
            .add(
                R.id.fragment_overlay_container,
                GroupTrackersListFragment.newInstance(g),
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
        val users = viewModel.uiState.value.users
        if (users.isEmpty()) {
            navHost()?.showSnackbar(getString(R.string.no_other_users_found))
            return
        }
        val normalizedUsers = users.map { it.email.trim().lowercase() }.toSet()
        val pinnedExisting = sharedWithEmailsForSave
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in normalizedUsers }
        SharedUserPickerDialog.show(
            fragment = this@GroupDetailFragment,
            title = getString(R.string.shared_with_recipients_label),
            users = users,
            selectedEmails = sharedWithEmailsForSave.toSet()
        ) { picked ->
            sharedWithEmailsForSave.clear()
            sharedWithEmailsForSave.addAll(pinnedExisting)
            sharedWithEmailsForSave.addAll(picked.sortedWith(NaturalSort.naturalOrder()))
            updateSharedWithCountText()
            viewModel.onSharedWithEmailsChanged(sharedWithEmailsForSave.toList())
        }
    }

    private fun performSave() {
        val name = nameEdit.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            navHost()?.showSnackbar(getString(R.string.name_required))
            return
        }
        viewModel.onNameChanged(name)
        viewModel.onSharedWithEmailsChanged(sharedWithEmailsForSave.toList())
        val visibility = if (selectedVisibilityIndex in visibilityValues.indices) visibilityValues[selectedVisibilityIndex] else "private"
        viewModel.onVisibilityChanged(visibility)
        viewModel.onHiddenInListChanged(hideInListRow.isChecked)
        viewModel.onWorldShareEnabledChanged(worldShareRow.isChecked)
        pendingAction = PendingAction.SAVE
        setAllInputsEnabled(false)
        viewModel.saveGroup()
    }

    private fun setAllInputsEnabled(enabled: Boolean) {
        nameEdit.isEnabled = enabled
        visibilitySpinner.isEnabled = enabled
        pickUsersButton.isEnabled = enabled
        worldShareRow.isEnabled = enabled
        hideInListRow.isEnabled = enabled
        deleteButton.isEnabled = enabled
        saveButton.isEnabled = enabled
    }

    private fun confirmDelete(g: Group) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.group_delete_confirm_title))
            .setMessage(getString(R.string.group_delete_confirm_message))
            .setPositiveButton(getString(R.string.delete_group)) { _, _ ->
                pendingAction = PendingAction.DELETE
                viewModel.deleteGroup(g.id)
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun closeEditor(discardDraft: Boolean = true) {
        if (discardDraft) {
            viewModel.discardDraftMembership()
        }
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun tryClose() {
        if (!viewModel.hasUnsavedChanges()) {
            closeEditor()
            return
        }
        val discardDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.discard_changes_confirm_title))
            .setMessage(getString(R.string.discard_changes_confirm_message))
            .setPositiveButton(getString(R.string.discard)) { _, _ -> closeEditor() }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
        discardDialog.applyDialogButtonColors(requireContext(), destructiveAction = true)
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

        fun newInstance(group: Group): GroupDetailFragment {
            return GroupDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, group.id)
                    putParcelable(ARG_GROUP, group)
                }
            }
        }
    }
}

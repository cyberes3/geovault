package com.geovault.tracker.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.tracker.RepositoryResult
import com.geovault.common.GeovaultAuthManager
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.Tracker
import com.geovault.tracker.UserItem
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.ui.applyDialogButtonColors
import com.geovault.common.LoadingSpinner
import com.geovault.common.NaturalSort
import com.geovault.common.ToggleHelpCardView
import com.geovault.common.R as CommonR
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.updateColorPreview
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditTrackerFragment : Fragment() {
    private val viewModel: EditTrackerViewModel by viewModels()

    private lateinit var nameEdit: EditText
    private lateinit var colorEdit: EditText
    private lateinit var colorPreview: View
    private lateinit var pickColorButton: MaterialButton
    private lateinit var selectedTrackSwitch: ToggleHelpCardView
    private lateinit var hideOnMapSwitch: ToggleHelpCardView
    private lateinit var saveButton: MaterialButton
    private lateinit var clearHistoryButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
    private lateinit var closeButton: ImageButton
    private lateinit var recentDataWindowSpinner: AutoCompleteTextView
    private lateinit var scrollContent: NestedScrollView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner

    private lateinit var sharingSection: View
    private lateinit var visibilitySpinner: AutoCompleteTextView
    private lateinit var pickUsersButton: MaterialButton
    private lateinit var sharedWithCountText: TextView
    private lateinit var shareParamsRecipientsSwitch: ToggleHelpCardView
    private lateinit var allowGroupReshareSwitch: ToggleHelpCardView
    private lateinit var worldShareEnabledSwitch: ToggleHelpCardView
    private lateinit var worldShareParamsRow: View
    private lateinit var shareParamsWorldSwitch: ToggleHelpCardView
    private lateinit var copyWorldLinkButton: MaterialButton
    private lateinit var copyWorldLinkSpinner: LoadingSpinner
    private lateinit var ownerToolsSection: View
    private lateinit var exportKmlButton: MaterialButton

    /** After clear history succeeds, keep the clear button disabled until this fragment is closed. */
    private var historyClearedThisSession = false

    private val visibilityValues = arrayOf("private", "shared", "public")

    /** Selected indices for dropdowns (since AutoCompleteTextView has no selectedItemPosition). */
    private var selectedRecentDataIndex = 0
    private var selectedVisibilityIndex = 0

    private val sharedWithEmails = mutableListOf<String>()

    /** Pending KML bytes to write when user picks save location (system file saver). */
    private var pendingKmlExportBytes: ByteArray? = null
    private var trackerId: String = ""
    private var pendingHiddenInListAfterSave: Boolean = false
    private var isRenderingState: Boolean = false
    private var pendingAction: PendingAction? = null

    /** Mirrors the last `enabled` passed to [setAllInputsEnabled] for combining with tracking state. */
    private var formInteractionEnabled: Boolean = false

    private enum class PendingAction {
        SAVE,
        CLEAR_HISTORY,
        DELETE,
        ENABLE_WORLD_SHARE,
        DISABLE_WORLD_SHARE
    }

    private val createKmlDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = pendingKmlExportBytes
        pendingKmlExportBytes = null
        if (bytes == null || !isAdded) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            navHost()?.showSnackbar(getString(R.string.kml_exported))
        } catch (e: Exception) {
            navHost()?.showSnackbar(getString(R.string.failed_to_save_kml))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_edit_tracker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameEdit = view.findViewById(R.id.editTrackerName)
        colorEdit = view.findViewById(R.id.editTrackerColor)
        colorPreview = view.findViewById(R.id.colorPreview)
        pickColorButton = view.findViewById(R.id.pickColorButton)
        selectedTrackSwitch = view.findViewById(R.id.editTrackerDefaultSwitch)
        hideOnMapSwitch = view.findViewById(R.id.editTrackerHideOnMapSwitch)
        saveButton = view.findViewById(R.id.editTrackerSave)
        clearHistoryButton = view.findViewById(R.id.editTrackerClearHistory)
        deleteButton = view.findViewById(R.id.editTrackerDelete)
        closeButton = view.findViewById(R.id.editTrackerClose)
        recentDataWindowSpinner = view.findViewById(R.id.editTrackerRecentDataSpinner)
        scrollContent = view.findViewById(R.id.editTrackerScrollContent)
        loadingOverlay = view.findViewById(R.id.editTrackerLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.editTrackerLoadingSpinner)

        sharingSection = view.findViewById(R.id.editTrackerSharingSection)
        visibilitySpinner = view.findViewById(R.id.sharing_visibility_spinner)
        pickUsersButton = view.findViewById(R.id.sharing_pick_users_button)
        sharedWithCountText = view.findViewById(R.id.sharing_shared_with_count_text)
        shareParamsRecipientsSwitch = view.findViewById(R.id.editTrackerShareParamsRecipients)
        allowGroupReshareSwitch = view.findViewById(R.id.editTrackerAllowGroupReshare)
        worldShareEnabledSwitch = view.findViewById(R.id.editTrackerWorldShareEnabled)
        worldShareParamsRow = view.findViewById(R.id.editTrackerWorldShareParamsRow)
        shareParamsWorldSwitch = view.findViewById(R.id.editTrackerWorldShareParamsRow)
        copyWorldLinkButton = view.findViewById(R.id.editTrackerCopyWorldLink)
        copyWorldLinkSpinner = view.findViewById(R.id.editTrackerCopyWorldLinkSpinner)
        ownerToolsSection = view.findViewById(R.id.editTrackerOwnerToolsSection)
        exportKmlButton = view.findViewById(R.id.editTrackerExportKml)

        val visibilityLabels = arrayOf(
            getString(R.string.visibility_private),
            getString(R.string.visibility_shared),
            getString(R.string.visibility_public)
        )
        val visibilityAdapter = ArrayAdapter(requireContext(), CommonR.layout.gv_common_item_dropdown, visibilityLabels)
        visibilitySpinner.setAdapter(visibilityAdapter)
        visibilitySpinner.setText(visibilityLabels[0], false)
        pickUsersButton.visibility = View.GONE
        sharedWithCountText.visibility = View.GONE
        visibilitySpinner.setOnItemClickListener { _, _, position, _ ->
            selectedVisibilityIndex = position
            val vis = if (position in visibilityValues.indices) visibilityValues[position] else "private"
            if (!isRenderingState) viewModel.onVisibilityChanged(vis)
            val showShared = vis == "shared"
            pickUsersButton.visibility = if (showShared) View.VISIBLE else View.GONE
            sharedWithCountText.visibility = if (showShared) View.VISIBLE else View.GONE
            if (showShared) updateSharedWithCountText()
        }

        worldShareEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isRenderingState) return@setOnCheckedChangeListener
            viewModel.onWorldShareEnabledChanged(isChecked)
            worldShareParamsRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                pendingAction = PendingAction.ENABLE_WORLD_SHARE
                copyWorldLinkButton.visibility = View.VISIBLE
                copyWorldLinkButton.isEnabled = false
                copyWorldLinkButton.text = ""
                copyWorldLinkSpinner.show()
                viewModel.enableWorldShare(trackerId)
            } else {
                copyWorldLinkButton.visibility = View.GONE
                pendingAction = PendingAction.DISABLE_WORLD_SHARE
                viewModel.disableWorldShare(trackerId)
            }
        }

        copyWorldLinkButton.setOnClickListener {
            val url = viewModel.uiState.value.form.worldShareUrl
            if (!url.isNullOrBlank()) {
                val base = GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                val fullUrl = if (url.startsWith("http")) url else "$base$url"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("World share link", fullUrl))
                navHost()?.showSnackbar(getString(R.string.world_link_copied))
            }
        }

        pickUsersButton.setOnClickListener { showAddRecipientDialog() }

        closeButton.setOnClickListener { tryClose() }

        showLoadingState(true)
        observeViewModel()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingRuntimeStateStore.state.collect {
                    updateSelectedTrackerToggleEnabled()
                }
            }
        }

        val recentDataLabels = RecentDataWindowOptions.labels(requireContext())
        val spinnerAdapter = ArrayAdapter(requireContext(), CommonR.layout.gv_common_item_dropdown, recentDataLabels)
        recentDataWindowSpinner.setAdapter(spinnerAdapter)
        recentDataWindowSpinner.setText(recentDataLabels[0], false)
        recentDataWindowSpinner.setOnItemClickListener { _, _, position, _ ->
            selectedRecentDataIndex = position
            if (!isRenderingState) {
                val recentDataWindow = RecentDataWindowOptions.valueForIndex(position)
                viewModel.onRecentDataWindowChanged(recentDataWindow)
            }
        }

        nameEdit.doAfterTextChanged {
            if (!isRenderingState) viewModel.onNameChanged(it?.toString() ?: "")
        }
        colorEdit.doAfterTextChanged {
            if (!isRenderingState) {
                val value = it?.toString() ?: ""
                viewModel.onColorChanged(value)
                updateColorPreview(colorPreview, value)
            }
        }
        hideOnMapSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isRenderingState) viewModel.onHiddenInListChanged(isChecked)
        }
        shareParamsRecipientsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isRenderingState) viewModel.onShareParamsRecipientsChanged(isChecked)
        }
        allowGroupReshareSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isRenderingState) viewModel.onAllowGroupReshareChanged(isChecked)
        }
        shareParamsWorldSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isRenderingState) viewModel.onShareParamsWorldChanged(isChecked)
        }

        pickColorButton.setOnClickListener {
            showHueColorPickerDialog(
                requireContext(),
                colorEdit.text?.toString(),
                colorEdit
            ) { hex ->
                if (isAdded) updateColorPreview(colorPreview, hex)
            }
        }

        val tracker = arguments?.getParcelable<Tracker>(ARG_TRACKER, Tracker::class.java)
        trackerId = tracker?.id ?: arguments?.getString(ARG_TRACKER_ID) ?: return
        selectedTrackSwitch.isChecked = SelectedTrackerPrefs.selectedTrackerId(requireContext()) == trackerId
        selectedTrackSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isRenderingState) viewModel.onDefaultTrackChanged(isChecked)
            if (isChecked) {
                val name = nameEdit.text?.toString()?.takeIf { it.isNotBlank() } ?: ""
                SelectedTrackerManager.setSelectedTracker(
                    context = requireContext(),
                    trackerId = trackerId,
                    trackerName = name,
                    restartTrackingIfRunning = true
                )
            } else {
                if (SelectedTrackerPrefs.selectedTrackerId(requireContext()) == trackerId) {
                    SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(requireContext())
                }
            }
        }
        if (tracker != null) {
            viewModel.bindInitialTracker(
                tracker = tracker,
                defaultColorHex = defaultTrackerColorHex(requireContext()),
                isDefaultTrack = selectedTrackSwitch.isChecked
            )
        }
        viewModel.load(trackerId)

        saveButton.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                navHost()?.showSnackbar(getString(R.string.name_required))
                return@setOnClickListener
            }
            val resolvedRecentDataWindow = RecentDataWindowOptions.resolveValueFromInput(
                context = requireContext(),
                rawInput = recentDataWindowSpinner.text?.toString().orEmpty()
            )
            if (resolvedRecentDataWindow == null) {
                navHost()?.showSnackbar(getString(R.string.invalid_recent_data_window_selection))
                return@setOnClickListener
            }
            viewModel.onRecentDataWindowChanged(resolvedRecentDataWindow)
            val hiddenInList = hideOnMapSwitch.isChecked
            pendingAction = PendingAction.SAVE
            pendingHiddenInListAfterSave = hiddenInList
            setAllInputsEnabled(false)
            viewModel.save()
        }

        clearHistoryButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.clear_history_confirm_title))
                .setMessage(getString(R.string.clear_history_confirm_message))
                .setPositiveButton(getString(R.string.clear_history_tracker)) { _, _ ->
                    pendingAction = PendingAction.CLEAR_HISTORY
                    setAllInputsEnabled(false)
                    viewModel.clearTrackerHistory(trackerId)
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
        }

        deleteButton.setOnClickListener {
            val confirmDialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_tracker_confirm_title))
                .setMessage(getString(R.string.delete_tracker_confirm_message))
                .setPositiveButton(getString(R.string.delete_tracker)) { _, _ ->
                    pendingAction = PendingAction.DELETE
                    setAllInputsEnabled(false)
                    viewModel.deleteTracker(trackerId)
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            confirmDialog.applyDialogButtonColors(requireContext(), destructiveAction = true)
        }

        updateSelectedTrackerToggleEnabled()
    }

    private fun populateFormFromState(form: EditTrackerFormState) {
        val color = form.color.ifBlank { defaultTrackerColorHex(requireContext()) }
        isRenderingState = true
        if ((nameEdit.text?.toString() ?: "") != form.name) {
            nameEdit.setText(form.name)
        }
        if ((colorEdit.text?.toString() ?: "") != color) {
            colorEdit.setText(color)
        }
        updateColorPreview(colorPreview, color)
        if (selectedTrackSwitch.isChecked != form.isDefaultTrack) {
            selectedTrackSwitch.isChecked = form.isDefaultTrack
        }
        val idx = RecentDataWindowOptions.indexForValue(form.recentDataWindow)
        selectedRecentDataIndex = idx
        val recentDataLabels = RecentDataWindowOptions.labels(requireContext())
        if ((recentDataWindowSpinner.text?.toString() ?: "") != recentDataLabels[idx]) {
            recentDataWindowSpinner.setText(recentDataLabels[idx], false)
        }
        if (form.isOwner) {
            sharingSection.visibility = View.VISIBLE
            val vis = form.visibility
            val visIdx = visibilityValues.indexOf(vis).coerceIn(0, visibilityValues.size - 1)
            selectedVisibilityIndex = visIdx
            val visibilityLabels = arrayOf(
                getString(R.string.visibility_private),
                getString(R.string.visibility_shared),
                getString(R.string.visibility_public)
            )
            if ((visibilitySpinner.text?.toString() ?: "") != visibilityLabels[visIdx]) {
                visibilitySpinner.setText(visibilityLabels[visIdx], false)
            }
            val showShared = vis == "shared"
            pickUsersButton.visibility = if (showShared) View.VISIBLE else View.GONE
            sharedWithCountText.visibility = if (showShared) View.VISIBLE else View.GONE
            sharedWithEmails.clear()
            sharedWithEmails.addAll(form.sharedWithEmails)
            if (showShared) updateSharedWithCountText()
            shareParamsRecipientsSwitch.isChecked = form.shareParamsWithRecipients
            allowGroupReshareSwitch.isChecked = form.allowGroupReshare
            shareParamsWorldSwitch.isChecked = form.shareParamsWithWorld
            val worldOn = form.worldShareEnabled
            worldShareEnabledSwitch.isChecked = worldOn
            worldShareParamsRow.visibility = if (worldOn) View.VISIBLE else View.GONE
            copyWorldLinkButton.visibility = if (worldOn && !form.worldShareUrl.isNullOrBlank()) View.VISIBLE else View.GONE
        } else {
            sharingSection.visibility = View.GONE
        }
        if (form.isOwner) {
            ownerToolsSection.visibility = View.VISIBLE
            exportKmlButton.setOnClickListener { exportKml(form.trackerId) }
        } else {
            ownerToolsSection.visibility = View.GONE
        }
        if (SelectedTrackerPrefs.selectedTrackerId(requireContext()) == form.trackerId) {
            SelectedTrackerPrefs.updateSelectedTrackerName(requireContext(), form.name)
        }
        hideOnMapSwitch.isChecked = form.hiddenInList
        isRenderingState = false
    }

    private fun hasUnsavedChanges(): Boolean = viewModel.hasUnsavedChanges()

    private fun exportKml(trackerId: String) {
        viewModel.exportKml(trackerId)
    }

    private fun updateSharedWithCountText() {
        val n = sharedWithEmails.size
        sharedWithCountText.text = resources.getQuantityString(R.plurals.shared_with_user_count, n, n)
    }

    private fun showAddRecipientDialog() {
        val users = viewModel.uiState.value.users
        if (users.isEmpty()) {
            navHost()?.showSnackbar(getString(R.string.no_other_users_found))
            return
        }
        val normalizedUsers = users.map { it.email.trim().lowercase() }.toSet()
        val pinnedExisting = sharedWithEmails
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in normalizedUsers }
        SharedUserPickerDialog.show(
            fragment = this@EditTrackerFragment,
            title = getString(R.string.add_recipient),
            users = users,
            selectedEmails = sharedWithEmails.toSet()
        ) { picked ->
            sharedWithEmails.clear()
            sharedWithEmails.addAll(pinnedExisting)
            sharedWithEmails.addAll(picked.sortedWith(NaturalSort.naturalOrder()))
            updateSharedWithCountText()
            viewModel.onSharedWithEmailsChanged(sharedWithEmails.toList())
        }
    }

    private fun popBackStack() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state.phase) {
                        EditTrackerPhase.Loading -> showLoadingState(true)
                        EditTrackerPhase.Ready -> {
                            showLoadingState(false)
                            populateFormFromState(state.form)
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
                        EditTrackerPhase.Saving -> {
                            showLoadingState(false)
                            setAllInputsEnabled(false)
                        }
                        EditTrackerPhase.Saved -> {
                            navHost()?.refreshMapAfterTrackerSettingsSaved(trackerId)
                            if (pendingHiddenInListAfterSave && trackerId == SelectedTrackerPrefs.selectedTrackerId(requireContext())) {
                                SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(requireContext())
                            }
                            pendingAction = null
                            popBackStack()
                        }
                    }

                    if (state.didClearHistory) {
                        historyClearedThisSession = true
                        pendingAction = null
                        setAllInputsEnabled(true)
                        viewModel.consumeHistoryCleared()
                        navHost()?.showSnackbar(getString(R.string.history_cleared))
                    }

                    if (state.didDelete) {
                        if (trackerId == SelectedTrackerPrefs.selectedTrackerId(requireContext())) {
                            SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(requireContext())
                        }
                        pendingAction = null
                        viewModel.consumeDelete()
                        popBackStack()
                        navHost()?.showSnackbar(getString(R.string.tracker_deleted))
                    }

                    if (!state.errorMessage.isNullOrBlank()) {
                        val failedAction = pendingAction
                        if (failedAction != null) {
                            setAllInputsEnabled(true)
                            pendingAction = null
                            val failureMessageRes = when {
                                failedAction == PendingAction.SAVE &&
                                    state.errorMessage == EditTrackerViewModel.SAVE_PERSISTENCE_MISMATCH -> {
                                    R.string.failed_to_save_tracker_persistence_mismatch
                                }
                                else -> when (failedAction) {
                                    PendingAction.SAVE -> R.string.failed_to_save_tracker
                                    PendingAction.CLEAR_HISTORY -> R.string.failed_to_clear_history
                                    PendingAction.DELETE -> R.string.failed_to_delete_tracker
                                    PendingAction.ENABLE_WORLD_SHARE -> R.string.failed_to_enable_world_share
                                    PendingAction.DISABLE_WORLD_SHARE -> R.string.failed_to_disable_world_share
                                }
                            }
                            navHost()?.showSnackbar(getString(failureMessageRes))
                        } else {
                            navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
                        }
                    }
                    if (state.kmlBytes != null) {
                        try {
                            val safeName = (
                                state.form.name.map { c -> if (c.isLetterOrDigit() || c in " -_") c else "" }
                                    .joinToString("")
                                    .take(40)
                                    .ifEmpty { "track" }
                                )
                            pendingKmlExportBytes = state.kmlBytes
                            createKmlDocumentLauncher.launch("$safeName.kml")
                        } catch (_: Exception) {
                            navHost()?.showSnackbar(getString(R.string.failed_to_save_kml))
                        } finally {
                            viewModel.consumeKml()
                        }
                    }
                }
            }
        }
    }

    private fun tryClose() {
        if (!hasUnsavedChanges()) {
            popBackStack()
            return
        }
        val discardDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.discard_changes_confirm_title))
            .setMessage(getString(R.string.discard_changes_confirm_message))
            .setPositiveButton(getString(R.string.discard)) { _, _ -> popBackStack() }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
        discardDialog.applyDialogButtonColors(requireContext(), destructiveAction = true)
    }

    private fun showLoadingState(loading: Boolean) {
        scrollContent.visibility = if (loading) View.GONE else View.VISIBLE
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            loadingSpinner.start()
            saveButton.isEnabled = false
            saveButton.alpha = 0.4f
            deleteButton.visibility = View.GONE
        } else {
            loadingSpinner.stop(hide = true)
            saveButton.isEnabled = true
            saveButton.alpha = 1f
            deleteButton.visibility = View.VISIBLE
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        saveButton.isEnabled = enabled
        saveButton.alpha = alpha
        val clearEnabled = enabled && !historyClearedThisSession
        clearHistoryButton.isEnabled = clearEnabled
        clearHistoryButton.alpha = if (clearEnabled) 1f else 0.4f
        deleteButton.isEnabled = enabled
        deleteButton.alpha = alpha
    }

    private fun updateSelectedTrackerToggleEnabled() {
        selectedTrackSwitch.isEnabled =
            formInteractionEnabled && !TrackingRuntimeStateStore.state.value.isRunning
    }

    private fun setAllInputsEnabled(enabled: Boolean) {
        formInteractionEnabled = enabled
        nameEdit.isEnabled = enabled
        colorEdit.isEnabled = enabled
        pickColorButton.isEnabled = enabled
        hideOnMapSwitch.isEnabled = enabled
        recentDataWindowSpinner.isEnabled = enabled
        recentDataWindowSpinner.isClickable = enabled
        recentDataWindowSpinner.isFocusable = enabled
        if (sharingSection.visibility == View.VISIBLE) {
            visibilitySpinner.isEnabled = enabled
            visibilitySpinner.isClickable = enabled
            visibilitySpinner.isFocusable = enabled
            pickUsersButton.isEnabled = enabled
            shareParamsRecipientsSwitch.isEnabled = enabled
            allowGroupReshareSwitch.isEnabled = enabled
            worldShareEnabledSwitch.isEnabled = enabled
            shareParamsWorldSwitch.isEnabled = enabled
            copyWorldLinkButton.isEnabled = enabled
        }
        if (ownerToolsSection.visibility == View.VISIBLE) {
            exportKmlButton.isEnabled = enabled
        }
        setActionButtonsEnabled(enabled)
        updateSelectedTrackerToggleEnabled()
    }

    companion object {
        const val ARG_TRACKER = "tracker"
        const val ARG_TRACKER_ID = "tracker_id"
    }
}

package com.geovault.tracker.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.common.GeovaultAuthManager
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.TrackingService
import com.geovault.tracker.UserItem
import com.geovault.common.LoadingSpinner
import com.geovault.common.R as CommonR
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.updateColorPreview
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView

class EditTrackerFragment : Fragment() {

    private lateinit var nameEdit: EditText
    private lateinit var colorEdit: EditText
    private lateinit var colorPreview: View
    private lateinit var pickColorButton: MaterialButton
    private lateinit var defaultTrackSwitch: SwitchCompat
    private lateinit var hideOnMapSwitch: SwitchCompat
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
    private lateinit var shareParamsRecipientsSwitch: SwitchCompat
    private lateinit var allowGroupReshareSwitch: SwitchCompat
    private lateinit var worldShareEnabledSwitch: SwitchCompat
    private lateinit var worldShareParamsRow: View
    private lateinit var shareParamsWorldSwitch: SwitchCompat
    private lateinit var copyWorldLinkButton: MaterialButton
    private lateinit var copyWorldLinkSpinner: LoadingSpinner
    private lateinit var ownerToolsSection: View
    private lateinit var exportKmlButton: MaterialButton

    /** After clear history succeeds, keep the clear button disabled until this fragment is closed. */
    private var historyClearedThisSession = false

    /** Values for recent data spinner: empty = All, then 1min, 1h, 1d, 1w, 1m */
    private val recentDataValues = arrayOf("", "1min", "1h", "1d", "1w", "1m")

    private val visibilityValues = arrayOf("private", "shared", "public")

    /** Selected indices for dropdowns (since AutoCompleteTextView has no selectedItemPosition). */
    private var selectedRecentDataIndex = 0
    private var selectedVisibilityIndex = 0

    /** Snapshot when form was loaded; used to detect unsaved changes. */
    private var initialName: String? = null
    private var initialColor: String? = null
    private var initialDefaultTrack: Boolean = false
    private var initialRecentDataWindow: String? = null
    private var initialVisibility: String? = null
    private var initialSharedWithEmails: List<String>? = null
    private var initialShareParamsRecipients: Boolean = false
    private var initialAllowGroupReshare: Boolean = false
    private var initialShareParamsWorld: Boolean = false
    private var initialWorldShareEnabled: Boolean = false
    private var initialHiddenInList: Boolean = false

    /** Last fetched tracker (for world_share_url after save). */
    private var currentFetchedTracker: Tracker? = null

    /** Current list of recipient emails (when visibility = shared). */
    private val sharedWithEmails = mutableListOf<String>()

    /** Pending KML bytes to write when user picks save location (system file saver). */
    private var pendingKmlExportBytes: ByteArray? = null

    private val createKmlDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = pendingKmlExportBytes
        pendingKmlExportBytes = null
        if (bytes == null || !isAdded) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(requireContext(), getString(R.string.kml_exported), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            (activity as? MainActivity)?.showSnackbar("Failed to save KML")
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
        defaultTrackSwitch = view.findViewById(R.id.editTrackerDefaultSwitch)
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
        shareParamsWorldSwitch = view.findViewById(R.id.editTrackerShareParamsWorld)
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
            val showShared = vis == "shared"
            pickUsersButton.visibility = if (showShared) View.VISIBLE else View.GONE
            sharedWithCountText.visibility = if (showShared) View.VISIBLE else View.GONE
            if (showShared) updateSharedWithCountText()
        }

        worldShareEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            worldShareParamsRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                val id = arguments?.getParcelable<Tracker>(ARG_TRACKER, Tracker::class.java)?.id
                    ?: arguments?.getString(ARG_TRACKER_ID)
                if (!id.isNullOrEmpty()) {
                    worldShareParamsRow.isEnabled = false
                    copyWorldLinkButton.visibility = View.VISIBLE
                    copyWorldLinkButton.isEnabled = false
                    copyWorldLinkButton.text = ""
                    copyWorldLinkSpinner.show()
                    TrackerRepository.updateTrackerSettings(
                        requireContext(),
                        id,
                        TrackerSettingsRequest(world_share_enabled = true)
                    ) { updated, _ ->
                        if (!isAdded) return@updateTrackerSettings
                        requireActivity().runOnUiThread {
                            worldShareParamsRow.isEnabled = true
                            copyWorldLinkButton.isEnabled = true
                            copyWorldLinkButton.text = getString(R.string.copy_world_share_link)
                            copyWorldLinkSpinner.hide()
                            if (updated != null) {
                                currentFetchedTracker = updated
                                copyWorldLinkButton.visibility = if (updated.world_share_url != null) View.VISIBLE else View.GONE
                            }
                        }
                    }
                } else {
                    copyWorldLinkButton.visibility = View.GONE
                }
            } else {
                copyWorldLinkButton.visibility = View.GONE
            }
        }

        copyWorldLinkButton.setOnClickListener {
            val url = currentFetchedTracker?.world_share_url
            if (!url.isNullOrBlank()) {
                val base = GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                val fullUrl = if (url.startsWith("http")) url else "$base$url"
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("World share link", fullUrl))
                Toast.makeText(requireContext(), getString(R.string.world_link_copied), Toast.LENGTH_SHORT).show()
            }
        }

        pickUsersButton.setOnClickListener { showAddRecipientDialog() }

        closeButton.setOnClickListener { tryClose() }

        showLoadingState(true)

        val recentDataLabels = arrayOf(
            getString(R.string.recent_data_all),
            getString(R.string.recent_data_1min),
            getString(R.string.recent_data_1h),
            getString(R.string.recent_data_1d),
            getString(R.string.recent_data_1w),
            getString(R.string.recent_data_1m)
        )
        val spinnerAdapter = ArrayAdapter(requireContext(), CommonR.layout.gv_common_item_dropdown, recentDataLabels)
        recentDataWindowSpinner.setAdapter(spinnerAdapter)
        recentDataWindowSpinner.setText(recentDataLabels[0], false)
        recentDataWindowSpinner.setOnItemClickListener { _, _, position, _ ->
            selectedRecentDataIndex = position
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
        val trackerId: String = tracker?.id ?: arguments?.getString(ARG_TRACKER_ID) ?: return
        val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        defaultTrackSwitch.isChecked = prefs.getString("selected_tracker_id", null) == trackerId
        defaultTrackSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val name = nameEdit.text?.toString()?.takeIf { it.isNotBlank() } ?: ""
                prefs.edit()
                    .putString("selected_tracker_id", trackerId)
                    .putString("selected_tracker_name", name)
                    .apply()
                requireActivity().supportFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
                TrackerRepository.getTrackerGeometry(requireContext(), trackerId) { }
                if (TrackingService.isRunning) {
                    val ctx = requireContext()
                    ctx.startService(Intent(ctx, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
                    Handler(Looper.getMainLooper()).postDelayed({
                        ctx.startForegroundService(Intent(ctx, TrackingService::class.java).apply { action = TrackingService.ACTION_START })
                    }, 400)
                }
            } else {
                if (prefs.getString("selected_tracker_id", null) == trackerId) {
                    prefs.edit()
                        .remove("selected_tracker_id")
                        .remove("selected_tracker_name")
                        .apply()
                    TrackerRepository.clearGeometryCache()
                    requireActivity().supportFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
                }
            }
        }
        if (tracker != null) {
            populateFormFromTracker(tracker)
            showLoadingState(false)
        } else {
            TrackerRepository.getTracker(requireContext(), trackerId, forceRefresh = false) { fetched ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        showLoadingState(false)
                        if (fetched != null) {
                            populateFormFromTracker(fetched)
                        } else {
                            (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                        }
                    }
                }
            }
        }

        saveButton.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val color = colorEdit.text.toString().trim().ifEmpty { null }
            if (name.isEmpty()) {
                (activity as? MainActivity)?.showSnackbar("Name is required")
                return@setOnClickListener
            }
            val recentDataWindow = if (selectedRecentDataIndex in recentDataValues.indices) recentDataValues[selectedRecentDataIndex] else ""
            val visibility = if (selectedVisibilityIndex in visibilityValues.indices) visibilityValues[selectedVisibilityIndex] else "private"
            val hiddenInList = hideOnMapSwitch.isChecked
            val request = TrackerSettingsRequest(
                name = name,
                color = color?.takeIf { it.isNotBlank() },
                recent_data_window = recentDataWindow.ifEmpty { null },
                visibility = if (sharingSection.visibility == View.VISIBLE) visibility else null,
                share_params_with_recipients = if (sharingSection.visibility == View.VISIBLE) shareParamsRecipientsSwitch.isChecked else null,
                share_params_with_world = if (sharingSection.visibility == View.VISIBLE) shareParamsWorldSwitch.isChecked else null,
                shared_with_emails = if (sharingSection.visibility == View.VISIBLE && visibility == "shared") sharedWithEmails.toList() else null,
                world_share_enabled = if (sharingSection.visibility == View.VISIBLE) worldShareEnabledSwitch.isChecked else null,
                allow_group_reshare = if (sharingSection.visibility == View.VISIBLE) allowGroupReshareSwitch.isChecked else null,
                hidden_in_list = hiddenInList
            )
            setAllInputsEnabled(false)
            TrackerRepository.updateTrackerSettings(requireContext(), trackerId, request) { updated, errorMessage ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        when {
                            updated != null -> {
                                currentFetchedTracker = updated
                                if (hiddenInList) {
                                    val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                                    if (trackerId == prefs.getString("selected_tracker_id", null)) {
                                        prefs.edit()
                                            .remove("selected_tracker_id")
                                            .remove("selected_tracker_name")
                                            .apply()
                                        TrackerRepository.clearCurrentTrackerCache()
                                        TrackerRepository.clearGeometryCache()
                                    }
                                    TrackersListFragment.pendingHiddenTrackerId = trackerId
                                    requireActivity().supportFragmentManager.setFragmentResult(
                                        TrackersListFragment.REQUEST_REFRESH_LIST,
                                        android.os.Bundle().apply {
                                            putString(TrackersListFragment.KEY_HIDDEN_TRACKER_ID, trackerId)
                                            putBoolean(TrackersListFragment.KEY_SKIP_SHARED_LIST_REFRESH, true)
                                        }
                                    )
                                } else {
                                    TrackersListFragment.pendingFullRefresh = true
                                    requireActivity().supportFragmentManager.setFragmentResult(
                                        TrackersListFragment.REQUEST_REFRESH_LIST,
                                        android.os.Bundle().apply {
                                            putBoolean(TrackersListFragment.KEY_SKIP_SHARED_LIST_REFRESH, true)
                                        }
                                    )
                                }
                                requireActivity().supportFragmentManager.setFragmentResult(
                                    TrackersListFragment.REQUEST_UPDATE_TRACKER,
                                    android.os.Bundle().apply {
                                        putParcelable("tracker", updated)
                                        putBoolean(TrackersListFragment.KEY_UPDATED_TRACKER_HIDDEN, hiddenInList)
                                    }
                                )
                                requireActivity().supportFragmentManager.popBackStack()
                            }
                            !errorMessage.isNullOrBlank() -> {
                                setAllInputsEnabled(true)
                                (activity as? MainActivity)?.showSnackbar(errorMessage)
                            }
                            else -> {
                                setAllInputsEnabled(true)
                                (activity as? MainActivity)?.showSnackbar("Failed to save tracker")
                            }
                        }
                    }
                }
            }
        }

        clearHistoryButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.clear_history_confirm_title))
                .setMessage(getString(R.string.clear_history_confirm_message))
                .setPositiveButton(getString(R.string.clear_history_tracker)) { _, _ ->
                    setAllInputsEnabled(false)
                    TrackerRepository.clearTrackerHistory(requireContext(), trackerId) { success ->
                        if (isAdded) {
                            requireActivity().runOnUiThread {
                                if (success) {
                                    historyClearedThisSession = true
                                    requireActivity().supportFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
                                    Toast.makeText(requireContext(), getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
                                } else {
                                    (activity as? MainActivity)?.showSnackbar("Failed to clear history")
                                }
                                setAllInputsEnabled(true)
                            }
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
        }

        deleteButton.setOnClickListener {
            val confirmDialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_tracker_confirm_title))
                .setMessage(getString(R.string.delete_tracker_confirm_message))
                .setPositiveButton(getString(R.string.delete_tracker)) { _, _ ->
                    val prefs = requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    val selectedId = prefs.getString("selected_tracker_id", null)
                    setAllInputsEnabled(false)
                    TrackerRepository.deleteTracker(requireContext(), trackerId) { success ->
                        if (isAdded) {
                            requireActivity().runOnUiThread {
                                if (success) {
                                    if (trackerId == selectedId) {
                                        prefs.edit()
                                            .remove("selected_tracker_id")
                                            .remove("selected_tracker_name")
                                            .apply()
                                        TrackerRepository.clearCurrentTrackerCache()
                                    }
                                    requireActivity().supportFragmentManager.setFragmentResult(
                                        TrackersListFragment.REQUEST_REFRESH_LIST,
                                        android.os.Bundle().apply {
                                            putString(TrackersListFragment.KEY_DELETED_TRACKER_ID, trackerId)
                                        }
                                    )
                                    requireActivity().supportFragmentManager.popBackStack()
                                    Toast.makeText(requireContext(), getString(R.string.tracker_deleted), Toast.LENGTH_SHORT).show()
                                } else {
                                    setAllInputsEnabled(true)
                                    (activity as? MainActivity)?.showSnackbar("Failed to delete tracker")
                                }
                            }
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.error_red)
            )
            confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
                ContextCompat.getColor(requireContext(), com.geovault.common.R.color.gv_common_dialog_negative_button)
            )
        }
    }

    private fun populateFormFromTracker(tracker: Tracker) {
        val trackerId = tracker.id
        currentFetchedTracker = tracker
        val color = tracker.color ?: defaultTrackerColorHex(requireContext())
        nameEdit.setText(tracker.name)
        colorEdit.setText(color)
        updateColorPreview(colorPreview, color)
        initialName = tracker.name
        initialColor = normalizeColorForCompare(color)
        initialDefaultTrack = defaultTrackSwitch.isChecked
        val recentVal = (tracker.settings?.get("recent_data_window") as? String) ?: ""
        initialRecentDataWindow = recentVal
        val idx = recentDataValues.indexOf(recentVal).coerceAtLeast(0)
        selectedRecentDataIndex = idx
        val recentDataLabels = arrayOf(
            getString(R.string.recent_data_all),
            getString(R.string.recent_data_1min),
            getString(R.string.recent_data_1h),
            getString(R.string.recent_data_1d),
            getString(R.string.recent_data_1w),
            getString(R.string.recent_data_1m)
        )
        recentDataWindowSpinner.setText(recentDataLabels[idx], false)
        if (tracker.isOwner()) {
            sharingSection.visibility = View.VISIBLE
            val vis = tracker.visibility ?: "private"
            val visIdx = visibilityValues.indexOf(vis).coerceIn(0, visibilityValues.size - 1)
            selectedVisibilityIndex = visIdx
            val visibilityLabels = arrayOf(
                getString(R.string.visibility_private),
                getString(R.string.visibility_shared),
                getString(R.string.visibility_public)
            )
            visibilitySpinner.setText(visibilityLabels[visIdx], false)
            val showShared = vis == "shared"
            pickUsersButton.visibility = if (showShared) View.VISIBLE else View.GONE
            sharedWithCountText.visibility = if (showShared) View.VISIBLE else View.GONE
            sharedWithEmails.clear()
            sharedWithEmails.addAll(tracker.shared_with_emails ?: emptyList())
            initialSharedWithEmails = sharedWithEmails.toList()
            if (showShared) updateSharedWithCountText()
            shareParamsRecipientsSwitch.isChecked = tracker.share_params_with_recipients == true
            val allowReshare = (tracker.settings as? Map<*, *>)?.get("allow_group_reshare") == true
            allowGroupReshareSwitch.isChecked = allowReshare
            shareParamsWorldSwitch.isChecked = tracker.share_params_with_world == true
            val worldOn = tracker.world_share_url != null
            worldShareEnabledSwitch.isChecked = worldOn
            initialVisibility = vis
            initialShareParamsRecipients = tracker.share_params_with_recipients == true
            initialAllowGroupReshare = allowReshare
            initialShareParamsWorld = tracker.share_params_with_world == true
            initialWorldShareEnabled = worldOn
            worldShareParamsRow.visibility = if (worldOn) View.VISIBLE else View.GONE
            copyWorldLinkButton.visibility = if (worldOn && !tracker.world_share_url.isNullOrBlank()) View.VISIBLE else View.GONE
        } else {
            sharingSection.visibility = View.GONE
        }
        if (tracker.isOwner()) {
            ownerToolsSection.visibility = View.VISIBLE
            exportKmlButton.setOnClickListener { exportKml(trackerId) }
        } else {
            ownerToolsSection.visibility = View.GONE
        }
        if (requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", null) == trackerId) {
            requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
                .putString("selected_tracker_name", tracker.name)
                .apply()
        }
        val hiddenInList = (tracker.settings?.get("hidden_in_list") as? Boolean) == true
        initialHiddenInList = hiddenInList
        hideOnMapSwitch.isChecked = hiddenInList
    }

    private fun normalizeColorForCompare(color: String?): String? {
        val t = color?.trim()?.ifEmpty { null } ?: return null
        return if (t.startsWith("#")) t else "#$t"
    }

    private fun getSelectedRecentDataWindow(): String {
        return if (selectedRecentDataIndex in recentDataValues.indices) recentDataValues[selectedRecentDataIndex] else ""
    }

    private fun hasUnsavedChanges(): Boolean {
        if (initialName == null) return false
        val currentName = nameEdit.text?.toString()?.trim() ?: ""
        val defaultHex = defaultTrackerColorHex(requireContext())
        val currentColorNorm = normalizeColorForCompare(colorEdit.text?.toString()?.trim()?.ifEmpty { null }) ?: defaultHex
        val initialColorNorm = initialColor ?: defaultHex
        val currentDefault = defaultTrackSwitch.isChecked
        val currentRecent = getSelectedRecentDataWindow()
        val initialRecent = initialRecentDataWindow ?: ""
        val currentHiddenInList = hideOnMapSwitch.isChecked
        var base = currentName != initialName || currentColorNorm != initialColorNorm || currentDefault != initialDefaultTrack || currentRecent != initialRecent || currentHiddenInList != initialHiddenInList
        if (sharingSection.visibility == View.VISIBLE) {
            val currentVis = if (selectedVisibilityIndex in visibilityValues.indices) visibilityValues[selectedVisibilityIndex] else "private"
            base = base || currentVis != (initialVisibility ?: "private") ||
                shareParamsRecipientsSwitch.isChecked != initialShareParamsRecipients ||
                allowGroupReshareSwitch.isChecked != initialAllowGroupReshare ||
                shareParamsWorldSwitch.isChecked != initialShareParamsWorld ||
                worldShareEnabledSwitch.isChecked != initialWorldShareEnabled ||
                sharedWithEmails.toSet() != (initialSharedWithEmails?.toSet() ?: emptySet<String>())
        }
        return base
    }

    private fun exportKml(trackerId: String) {
        TrackerRepository.fetchTrackerKml(requireContext(), trackerId) { body ->
            if (!isAdded) return@fetchTrackerKml
            requireActivity().runOnUiThread {
                if (body == null) {
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                    return@runOnUiThread
                }
                try {
                    val bytes = body.bytes()
                    val safeName = (currentFetchedTracker?.name?.map { c -> if (c.isLetterOrDigit() || c in " -_") c else "" }?.joinToString("")?.take(40) ?: "track").ifEmpty { "track" }
                    pendingKmlExportBytes = bytes
                    createKmlDocumentLauncher.launch("$safeName.kml")
                } catch (e: Exception) {
                    (activity as? MainActivity)?.showSnackbar("Failed to save KML")
                }
            }
        }
    }

    private fun updateSharedWithCountText() {
        val n = sharedWithEmails.size
        sharedWithCountText.text = resources.getQuantityString(R.plurals.shared_with_user_count, n, n)
    }

    private fun showAddRecipientDialog() {
        TrackerRepository.getUsers(requireContext()) { response ->
            if (!isAdded) return@getUsers
            requireActivity().runOnUiThread {
                val users = response?.users ?: emptyList()
                if (users.isEmpty()) {
                    (activity as? MainActivity)?.showSnackbar(getString(R.string.no_other_users_found))
                    return@runOnUiThread
                }
                val normalizedUsers = users.map { it.email.trim().lowercase() }.toSet()
                val pinnedExisting = sharedWithEmails
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.lowercase() !in normalizedUsers }
                SharedUserPickerDialog.show(
                    fragment = this,
                    title = getString(R.string.add_recipient),
                    users = users,
                    selectedEmails = sharedWithEmails.toSet()
                ) { picked ->
                    sharedWithEmails.clear()
                    sharedWithEmails.addAll(pinnedExisting)
                    sharedWithEmails.addAll(picked.sorted())
                    updateSharedWithCountText()
                }
            }
        }
    }

    private fun popBackStack() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun tryClose() {
        TrackerRepository.cancelTrackerRequest()
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
        discardDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.error_red)
        )
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

    private fun setAllInputsEnabled(enabled: Boolean) {
        nameEdit.isEnabled = enabled
        colorEdit.isEnabled = enabled
        pickColorButton.isEnabled = enabled
        defaultTrackSwitch.isEnabled = enabled
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
    }

    companion object {
        const val ARG_TRACKER = "tracker"
        const val ARG_TRACKER_ID = "tracker_id"
    }
}

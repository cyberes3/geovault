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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var saveButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var clearHistoryButton: MaterialButton
    private lateinit var deleteButton: ImageButton
    private lateinit var recentDataWindowSpinner: Spinner
    private lateinit var scrollContent: NestedScrollView
    private lateinit var loadingOverlay: View
    private lateinit var loadingSpinner: LoadingSpinner

    private lateinit var sharingSection: View
    private lateinit var visibilitySpinner: Spinner
    private lateinit var sharedWithContainer: View
    private lateinit var recipientsList: LinearLayout
    private lateinit var addRecipientButton: MaterialButton
    private lateinit var shareParamsRecipientsSwitch: SwitchCompat
    private lateinit var allowGroupReshareSwitch: SwitchCompat
    private lateinit var worldShareEnabledSwitch: SwitchCompat
    private lateinit var worldShareParamsRow: View
    private lateinit var shareParamsWorldSwitch: SwitchCompat
    private lateinit var copyWorldLinkButton: MaterialButton
    private lateinit var ownerToolsSection: View
    private lateinit var subscribersButton: MaterialButton
    private lateinit var exportKmlButton: MaterialButton
    private lateinit var copyProfileUrlButton: MaterialButton
    private lateinit var copySecretButton: MaterialButton

    /** After clear history succeeds, keep the clear button disabled until this fragment is closed. */
    private var historyClearedThisSession = false

    /** Values for recent data spinner: empty = All, then 1min, 1h, 1d, 1w, 1m */
    private val recentDataValues = arrayOf("", "1min", "1h", "1d", "1w", "1m")

    private val visibilityValues = arrayOf("private", "shared", "public")

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

    /** Last fetched tracker (for world_share_url after save). */
    private var currentFetchedTracker: Tracker? = null

    /** Current list of recipient emails (when visibility = shared). */
    private val sharedWithEmails = mutableListOf<String>()

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
        saveButton = view.findViewById(R.id.editTrackerSave)
        cancelButton = view.findViewById(R.id.editTrackerCancel)
        clearHistoryButton = view.findViewById(R.id.editTrackerClearHistory)
        deleteButton = view.findViewById(R.id.editTrackerDelete)
        recentDataWindowSpinner = view.findViewById(R.id.editTrackerRecentDataSpinner)
        scrollContent = view.findViewById(R.id.editTrackerScrollContent)
        loadingOverlay = view.findViewById(R.id.editTrackerLoadingOverlay)
        loadingSpinner = view.findViewById(R.id.editTrackerLoadingSpinner)

        sharingSection = view.findViewById(R.id.editTrackerSharingSection)
        visibilitySpinner = view.findViewById(R.id.editTrackerVisibilitySpinner)
        sharedWithContainer = view.findViewById(R.id.editTrackerSharedWithContainer)
        recipientsList = view.findViewById(R.id.editTrackerRecipientsList)
        addRecipientButton = view.findViewById(R.id.editTrackerAddRecipient)
        shareParamsRecipientsSwitch = view.findViewById(R.id.editTrackerShareParamsRecipients)
        allowGroupReshareSwitch = view.findViewById(R.id.editTrackerAllowGroupReshare)
        worldShareEnabledSwitch = view.findViewById(R.id.editTrackerWorldShareEnabled)
        worldShareParamsRow = view.findViewById(R.id.editTrackerWorldShareParamsRow)
        shareParamsWorldSwitch = view.findViewById(R.id.editTrackerShareParamsWorld)
        copyWorldLinkButton = view.findViewById(R.id.editTrackerCopyWorldLink)
        ownerToolsSection = view.findViewById(R.id.editTrackerOwnerToolsSection)
        subscribersButton = view.findViewById(R.id.editTrackerSubscribers)
        exportKmlButton = view.findViewById(R.id.editTrackerExportKml)
        copyProfileUrlButton = view.findViewById(R.id.editTrackerCopyProfileUrl)
        copySecretButton = view.findViewById(R.id.editTrackerCopySecret)

        val visibilityLabels = arrayOf(
            getString(R.string.visibility_private),
            getString(R.string.visibility_shared),
            getString(R.string.visibility_public)
        )
        val visibilityAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, visibilityLabels)
        visibilityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        visibilitySpinner.adapter = visibilityAdapter

        visibilitySpinner.setSelection(0)
        visibilitySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val vis = if (position in visibilityValues.indices) visibilityValues[position] else "private"
                sharedWithContainer.visibility = if (vis == "shared") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        worldShareEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            worldShareParamsRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            copyWorldLinkButton.visibility = if (isChecked && currentFetchedTracker?.world_share_url != null) View.VISIBLE else View.GONE
        }

        copyWorldLinkButton.setOnClickListener {
            val url = currentFetchedTracker?.world_share_url
            if (!url.isNullOrBlank()) {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("World share link", url))
                Toast.makeText(requireContext(), getString(R.string.world_link_copied), Toast.LENGTH_SHORT).show()
            }
        }

        addRecipientButton.setOnClickListener { showAddRecipientDialog() }

        cancelButton.setOnClickListener { tryClose() }

        showLoadingState(true)

        val recentDataLabels = arrayOf(
            getString(R.string.recent_data_all),
            getString(R.string.recent_data_1min),
            getString(R.string.recent_data_1h),
            getString(R.string.recent_data_1d),
            getString(R.string.recent_data_1w),
            getString(R.string.recent_data_1m)
        )
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, recentDataLabels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        recentDataWindowSpinner.adapter = spinnerAdapter

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
                requireActivity().supportFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
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
                    requireActivity().supportFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
                }
            }
        }
        // Always fetch fresh tracker from server so edit form shows current settings (e.g. recent_data_window).
        TrackerRepository.getTracker(requireContext(), trackerId, forceRefresh = true) { fetched ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    showLoadingState(false)
                    if (fetched != null) {
                        currentFetchedTracker = fetched
                        val color = fetched.color ?: defaultTrackerColorHex(requireContext())
                        nameEdit.setText(fetched.name)
                        colorEdit.setText(color)
                        updateColorPreview(colorPreview, color)
                        initialName = fetched.name
                        initialColor = normalizeColorForCompare(color)
                        initialDefaultTrack = defaultTrackSwitch.isChecked
                        val recentVal = (fetched.settings?.get("recent_data_window") as? String) ?: ""
                        initialRecentDataWindow = recentVal
                        val idx = recentDataValues.indexOf(recentVal).coerceAtLeast(0)
                        recentDataWindowSpinner.setSelection(idx)
                        if (fetched.isOwner()) {
                            sharingSection.visibility = View.VISIBLE
                            val vis = fetched.visibility ?: "private"
                            val visIdx = visibilityValues.indexOf(vis).coerceIn(0, visibilityValues.size - 1)
                            visibilitySpinner.setSelection(visIdx)
                            sharedWithContainer.visibility = if (vis == "shared") View.VISIBLE else View.GONE
                            sharedWithEmails.clear()
                            sharedWithEmails.addAll(fetched.shared_with_emails ?: emptyList())
                            initialSharedWithEmails = sharedWithEmails.toList()
                            refreshRecipientsList()
                            shareParamsRecipientsSwitch.isChecked = fetched.share_params_with_recipients == true
                            val allowReshare = (fetched.settings as? Map<*, *>)?.get("allow_group_reshare") == true
                            allowGroupReshareSwitch.isChecked = allowReshare
                            shareParamsWorldSwitch.isChecked = fetched.share_params_with_world == true
                            val worldOn = fetched.world_share_url != null
                            worldShareEnabledSwitch.isChecked = worldOn
                            initialVisibility = vis
                            initialShareParamsRecipients = fetched.share_params_with_recipients == true
                            initialAllowGroupReshare = allowReshare
                            initialShareParamsWorld = fetched.share_params_with_world == true
                            initialWorldShareEnabled = worldOn
                            worldShareParamsRow.visibility = if (worldOn) View.VISIBLE else View.GONE
                            copyWorldLinkButton.visibility = if (worldOn && !fetched.world_share_url.isNullOrBlank()) View.VISIBLE else View.GONE
                        } else {
                            sharingSection.visibility = View.GONE
                        }
                        if (fetched.isOwner()) {
                            ownerToolsSection.visibility = View.VISIBLE
                            subscribersButton.setOnClickListener { showSubscribersDialog(trackerId) }
                            exportKmlButton.setOnClickListener { exportKml(trackerId) }
                            copyProfileUrlButton.setOnClickListener {
                                val secret = currentFetchedTracker?.tracker_secret
                                if (!secret.isNullOrBlank()) {
                                    val url = (GeovaultAuthManager.getServerUrl(requireContext()).trimEnd('/')
                                        + "/api/extensions/live-track/trackers/$trackerId/profile.properties?secret=$secret")
                                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("GPSLogger profile", url))
                                    Toast.makeText(requireContext(), getString(R.string.profile_url_copied), Toast.LENGTH_SHORT).show()
                                }
                            }
                            copySecretButton.setOnClickListener {
                                val secret = currentFetchedTracker?.tracker_secret
                                if (!secret.isNullOrBlank()) {
                                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Tracker secret", secret))
                                    Toast.makeText(requireContext(), getString(R.string.secret_copied), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            ownerToolsSection.visibility = View.GONE
                        }
                        if (requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", null) == trackerId) {
                            requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
                                .putString("selected_tracker_name", fetched.name)
                                .apply()
                        }
                    } else {
                        (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
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
            val recentPos = recentDataWindowSpinner.selectedItemPosition
            val recentDataWindow = if (recentPos in recentDataValues.indices) recentDataValues[recentPos] else ""
            val visPos = visibilitySpinner.selectedItemPosition
            val visibility = if (visPos in visibilityValues.indices) visibilityValues[visPos] else "private"
            val request = TrackerSettingsRequest(
                name = name,
                color = color?.takeIf { it.isNotBlank() },
                recent_data_window = recentDataWindow.ifEmpty { null },
                visibility = if (sharingSection.visibility == View.VISIBLE) visibility else null,
                share_params_with_recipients = if (sharingSection.visibility == View.VISIBLE) shareParamsRecipientsSwitch.isChecked else null,
                share_params_with_world = if (sharingSection.visibility == View.VISIBLE) shareParamsWorldSwitch.isChecked else null,
                shared_with_emails = if (sharingSection.visibility == View.VISIBLE && visibility == "shared") sharedWithEmails.toList() else null,
                world_share_enabled = if (sharingSection.visibility == View.VISIBLE) worldShareEnabledSwitch.isChecked else null,
                allow_group_reshare = if (sharingSection.visibility == View.VISIBLE) allowGroupReshareSwitch.isChecked else null
            )
            setAllInputsEnabled(false)
            TrackerRepository.updateTrackerSettings(requireContext(), trackerId, request) { updated, errorMessage ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        when {
                            updated != null -> {
                                currentFetchedTracker = updated
                                requireActivity().supportFragmentManager.setFragmentResult(
                                    TrackersFragment.REQUEST_UPDATE_TRACKER,
                                    android.os.Bundle().apply { putParcelable("tracker", updated) }
                                )
                                requireActivity().supportFragmentManager.popBackStack()
                                Toast.makeText(requireContext(), getString(R.string.saved_tracker), Toast.LENGTH_SHORT).show()
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
                                    requireActivity().supportFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
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
                                    requireActivity().supportFragmentManager.setFragmentResult(TrackersFragment.REQUEST_REFRESH_LIST, android.os.Bundle())
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

    private fun normalizeColorForCompare(color: String?): String? {
        val t = color?.trim()?.ifEmpty { null } ?: return null
        return if (t.startsWith("#")) t else "#$t"
    }

    private fun getSelectedRecentDataWindow(): String {
        val pos = recentDataWindowSpinner.selectedItemPosition
        return if (pos in recentDataValues.indices) recentDataValues[pos] else ""
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
        var base = currentName != initialName || currentColorNorm != initialColorNorm || currentDefault != initialDefaultTrack || currentRecent != initialRecent
        if (sharingSection.visibility == View.VISIBLE) {
            val visPos = visibilitySpinner.selectedItemPosition
            val currentVis = if (visPos in visibilityValues.indices) visibilityValues[visPos] else "private"
            base = base || currentVis != (initialVisibility ?: "private") ||
                shareParamsRecipientsSwitch.isChecked != initialShareParamsRecipients ||
                allowGroupReshareSwitch.isChecked != initialAllowGroupReshare ||
                shareParamsWorldSwitch.isChecked != initialShareParamsWorld ||
                worldShareEnabledSwitch.isChecked != initialWorldShareEnabled ||
                sharedWithEmails.toSet() != (initialSharedWithEmails?.toSet() ?: emptySet<String>())
        }
        return base
    }

    private fun refreshRecipientsList() {
        recipientsList.removeAllViews()
        for (email in sharedWithEmails) {
            val row = layoutInflater.inflate(R.layout.item_recipient_row, recipientsList, false)
            row.findViewById<TextView>(R.id.recipientEmail).text = email
            row.findViewById<ImageButton>(R.id.recipientRemove).setOnClickListener {
                sharedWithEmails.remove(email)
                refreshRecipientsList()
            }
            recipientsList.addView(row)
        }
    }

    private fun showSubscribersDialog(trackerId: String) {
        TrackerRepository.getSubscribers(requireContext(), trackerId) { response ->
            if (!isAdded) return@getSubscribers
            requireActivity().runOnUiThread {
                val list = response?.subscribers ?: emptyList()
                val message = if (list.isEmpty()) "No subscribers" else list.joinToString("\n") { it.email }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.subscribers_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.close), null)
                    .show()
            }
        }
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
                    val file = java.io.File(requireContext().getExternalFilesDir(null), "$safeName.kml")
                    file.writeBytes(bytes)
                    val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/vnd.google-earth.kml+xml"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(intent, getString(R.string.export_kml)))
                    Toast.makeText(requireContext(), getString(R.string.kml_exported), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    (activity as? MainActivity)?.showSnackbar("Failed to save KML")
                }
            }
        }
    }

    private fun showAddRecipientDialog() {
        TrackerRepository.getUsers(requireContext()) { response ->
            if (!isAdded) return@getUsers
            requireActivity().runOnUiThread {
                val users = response?.users ?: emptyList()
                val alreadyAdded = sharedWithEmails.map { it.lowercase() }.toSet()
                val addable = users.filter { !alreadyAdded.contains(it.email.trim().lowercase()) }
                if (addable.isEmpty()) {
                    (activity as? MainActivity)?.showSnackbar("No more users to add")
                    return@runOnUiThread
                }
                val emails = addable.map { it.email }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.add_recipient))
                    .setItems(emails.toTypedArray()) { _, which ->
                        val email = addable[which].email.trim()
                        if (email.isNotBlank() && !sharedWithEmails.any { it.equals(email, ignoreCase = true) }) {
                            sharedWithEmails.add(email)
                            refreshRecipientsList()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel_button), null)
                    .show()
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
        cancelButton.isEnabled = enabled
        cancelButton.alpha = alpha
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
        recentDataWindowSpinner.isEnabled = enabled
        if (sharingSection.visibility == View.VISIBLE) {
            visibilitySpinner.isEnabled = enabled
            addRecipientButton.isEnabled = enabled
            shareParamsRecipientsSwitch.isEnabled = enabled
            allowGroupReshareSwitch.isEnabled = enabled
            worldShareEnabledSwitch.isEnabled = enabled
            shareParamsWorldSwitch.isEnabled = enabled
            copyWorldLinkButton.isEnabled = enabled
            for (i in 0 until recipientsList.childCount) {
                recipientsList.getChildAt(i).findViewById<ImageButton>(R.id.recipientRemove).isEnabled = enabled
            }
        }
        if (ownerToolsSection.visibility == View.VISIBLE) {
            subscribersButton.isEnabled = enabled
            exportKmlButton.isEnabled = enabled
            copyProfileUrlButton.isEnabled = enabled
            copySecretButton.isEnabled = enabled
        }
        setActionButtonsEnabled(enabled)
    }

    companion object {
        const val ARG_TRACKER = "tracker"
        const val ARG_TRACKER_ID = "tracker_id"
    }
}

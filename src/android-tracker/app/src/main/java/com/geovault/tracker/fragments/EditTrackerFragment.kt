package com.geovault.tracker.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.tracker.MainActivity
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.TrackingService
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.updateColorPreview
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat

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

    /** After clear history succeeds, keep the clear button disabled until this fragment is closed. */
    private var historyClearedThisSession = false

    /** Snapshot when form was loaded; used to detect unsaved changes. */
    private var initialName: String? = null
    private var initialColor: String? = null
    private var initialDefaultTrack: Boolean = false

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

        cancelButton.setOnClickListener { tryClose() }

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
                val name = tracker?.name ?: nameEdit.text?.toString()?.takeIf { it.isNotBlank() } ?: ""
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
        if (tracker != null) {
            val color = tracker.color ?: "#3388ff"
            nameEdit.setText(tracker.name)
            colorEdit.setText(color)
            updateColorPreview(colorPreview, color)
            initialName = tracker.name
            initialColor = normalizeColorForCompare(color)
            initialDefaultTrack = defaultTrackSwitch.isChecked
        } else {
            TrackerRepository.getTracker(requireContext(), trackerId) { fetched ->
                if (isAdded && fetched != null) {
                    requireActivity().runOnUiThread {
                        val color = fetched.color ?: "#3388ff"
                        nameEdit.setText(fetched.name)
                        colorEdit.setText(color)
                        updateColorPreview(colorPreview, color)
                        initialName = fetched.name
                        initialColor = normalizeColorForCompare(color)
                        initialDefaultTrack = defaultTrackSwitch.isChecked
                        if (requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).getString("selected_tracker_id", null) == trackerId) {
                            requireContext().getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
                                .putString("selected_tracker_name", fetched.name)
                                .apply()
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
            setAllInputsEnabled(false)
            TrackerRepository.updateTracker(requireContext(), trackerId, name, color) { updated ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        if (updated != null) {
                            requireActivity().supportFragmentManager.setFragmentResult(
                                TrackersFragment.REQUEST_UPDATE_TRACKER,
                                android.os.Bundle().apply { putParcelable("tracker", updated) }
                            )
                            requireActivity().supportFragmentManager.popBackStack()
                            Toast.makeText(requireContext(), getString(R.string.saved_tracker), Toast.LENGTH_SHORT).show()
                        } else {
                            setAllInputsEnabled(true)
                            (activity as? MainActivity)?.showSnackbar("Failed to save tracker")
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

    private fun hasUnsavedChanges(): Boolean {
        if (initialName == null) return false
        val currentName = nameEdit.text?.toString()?.trim() ?: ""
        val currentColorNorm = normalizeColorForCompare(colorEdit.text?.toString()?.trim()?.ifEmpty { null }) ?: "#3388ff"
        val initialColorNorm = initialColor ?: "#3388ff"
        val currentDefault = defaultTrackSwitch.isChecked
        return currentName != initialName || currentColorNorm != initialColorNorm || currentDefault != initialDefaultTrack
    }

    private fun popBackStack() {
        requireActivity().supportFragmentManager.popBackStack()
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
        discardDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.error_red)
        )
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
        setActionButtonsEnabled(enabled)
    }

    companion object {
        const val ARG_TRACKER = "tracker"
        const val ARG_TRACKER_ID = "tracker_id"
    }
}

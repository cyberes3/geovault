package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.common.ToggleHelpCardView
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.updateColorPreview
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.ui.applyDialogButtonColors
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NewTrackerFragment : Fragment() {
    private val viewModel: NewTrackerViewModel by viewModels()

    private lateinit var nameEdit: EditText
    private lateinit var colorEdit: EditText
    private lateinit var colorPreview: View
    private lateinit var selectedTrackSwitch: ToggleHelpCardView
    private lateinit var pickColorButton: MaterialButton
    private lateinit var createButton: MaterialButton
    private lateinit var cancelButton: MaterialButton

    /** Snapshot when form was loaded; used to detect unsaved changes. */
    private var initialName: String = ""
    private var initialColor: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_new_tracker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameEdit = view.findViewById(R.id.newTrackerName)
        colorEdit = view.findViewById(R.id.newTrackerColor)
        colorPreview = view.findViewById(R.id.colorPreview)
        selectedTrackSwitch = view.findViewById(R.id.newTrackerDefaultSwitch)
        pickColorButton = view.findViewById(R.id.pickColorButton)
        createButton = view.findViewById(R.id.newTrackerCreate)
        cancelButton = view.findViewById(R.id.newTrackerCancel)

        selectedTrackSwitch.isChecked = false

        val defaultHex = defaultTrackerColorHex(requireContext())
        colorEdit.setText(defaultHex)
        updateColorPreview(colorPreview, defaultHex)
        initialName = ""
        initialColor = defaultHex

        pickColorButton.setOnClickListener {
            showHueColorPickerDialog(
                requireContext(),
                colorEdit.text?.toString(),
                colorEdit
            ) { hex ->
                if (isAdded) updateColorPreview(colorPreview, hex)
            }
        }

        cancelButton.setOnClickListener { tryClose() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingRuntimeStateStore.state.collect { runtime ->
                    selectedTrackSwitch.isEnabled = !runtime.isRunning
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    setActionButtonsEnabled(!state.isSaving)
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let { navHost()?.showSnackbar(it) }
                    val created = state.createdTracker
                    if (created != null) {
                        if (selectedTrackSwitch.isChecked) {
                            SelectedTrackerManager.setSelectedTracker(
                                context = requireContext(),
                                trackerId = created.id,
                                trackerName = created.name,
                                restartTrackingIfRunning = true
                            )
                        }
                        requireActivity().supportFragmentManager.popBackStack()
                        navHost()?.showSnackbar(getString(R.string.tracker_created))
                        viewModel.consumeCreatedTracker()
                    }
                }
            }
        }

        createButton.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val color = colorEdit.text.toString().trim().ifEmpty { null }
            if (name.isEmpty()) {
                navHost()?.showSnackbar(getString(R.string.name_required))
                return@setOnClickListener
            }
            viewModel.createTracker(name = name, color = color)
        }
    }

    private fun normalizeColorForCompare(color: String?): String? {
        val t = color?.trim()?.ifEmpty { null } ?: return null
        return if (t.startsWith("#")) t else "#$t"
    }

    private fun hasUnsavedChanges(): Boolean {
        val currentName = nameEdit.text?.toString()?.trim() ?: ""
        val defaultHex = defaultTrackerColorHex(requireContext())
        val currentColorNorm = normalizeColorForCompare(colorEdit.text?.toString()?.trim()?.ifEmpty { null }) ?: defaultHex
        val initialColorNorm = normalizeColorForCompare(initialColor) ?: defaultHex
        return currentName != initialName || currentColorNorm != initialColorNorm
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
        discardDialog.applyDialogButtonColors(requireContext(), destructiveAction = true)
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        createButton.isEnabled = enabled
        cancelButton.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        createButton.alpha = alpha
        cancelButton.alpha = alpha
    }
}

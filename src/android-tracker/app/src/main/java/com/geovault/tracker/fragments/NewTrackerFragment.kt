package com.geovault.tracker.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.geovault.common.RetrofitClient
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.TrackerApi
import com.geovault.tracker.showHueColorPickerDialog
import com.geovault.tracker.updateColorPreview
import com.geovault.tracker.defaultTrackerColorHex
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerRepository
import com.geovault.tracker.navigation.navHost
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NewTrackerFragment : Fragment() {

    private lateinit var nameEdit: EditText
    private lateinit var colorEdit: EditText
    private lateinit var colorPreview: View
    private lateinit var selectedTrackSwitch: SwitchCompat
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

        createButton.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            val color = colorEdit.text.toString().trim().ifEmpty { null }
            if (name.isEmpty()) {
                navHost()?.showSnackbar("Name is required")
                return@setOnClickListener
            }
            setActionButtonsEnabled(false)
            val serverUrl = com.geovault.common.GeovaultAuthManager.getServerUrl(requireContext())
            if (serverUrl.isEmpty()) {
                navHost()?.showSnackbar("Not connected")
                setActionButtonsEnabled(true)
                return@setOnClickListener
            }
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val api = RetrofitClient.getClient(requireContext(), baseUrl).create(TrackerApi::class.java)
            api.createTracker(TrackerCreateRequest(name = name, color = color))
                .enqueue(object : Callback<com.geovault.tracker.Tracker> {
                    override fun onResponse(
                        call: Call<com.geovault.tracker.Tracker>,
                        response: Response<com.geovault.tracker.Tracker>
                    ) {
                        if (isAdded) {
                            requireActivity().runOnUiThread {
                                setActionButtonsEnabled(true)
                                if (response.isSuccessful && response.body() != null) {
                                    val newTracker = response.body()!!
                                    TrackerRepository.insertTrackerInCache(newTracker)
                                    if (selectedTrackSwitch.isChecked) {
                                        SelectedTrackerManager.setSelectedTracker(
                                            context = requireContext(),
                                            trackerId = newTracker.id,
                                            trackerName = newTracker.name,
                                            restartTrackingIfRunning = true
                                        )
                                        TrackerRepository.getTrackerGeometry(requireContext(), newTracker.id, callback = { })
                                    }
                                    requireActivity().supportFragmentManager.setFragmentResult(
                                        TrackersListFragment.REQUEST_REFRESH_LIST,
                                        android.os.Bundle().apply { putParcelable(TrackersListFragment.KEY_NEW_TRACKER, newTracker) }
                                    )
                                    requireActivity().supportFragmentManager.popBackStack()
                                    Toast.makeText(requireContext(), "Tracker created", Toast.LENGTH_SHORT).show()
                                } else {
                                    val msg = response.errorBody()?.string()?.take(120) ?: "Failed to create tracker"
                                    navHost()?.showSnackbar(msg)
                                }
                            }
                        }
                    }
                    override fun onFailure(call: Call<com.geovault.tracker.Tracker>, t: Throwable) {
                        if (isAdded) {
                            requireActivity().runOnUiThread {
                                setActionButtonsEnabled(true)
                                navHost()?.showSnackbar(t.message ?: "Network error")
                            }
                        }
                    }
                })
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
        discardDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.error_red)
        )
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        createButton.isEnabled = enabled
        cancelButton.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        createButton.alpha = alpha
        cancelButton.alpha = alpha
    }
}

package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditSharedTrackerFragment : Fragment() {
    private val viewModel: EditSharedTrackerViewModel by viewModels()

    companion object {
        const val ARG_TRACKER = "tracker"
    }

    private lateinit var trackerName: TextView
    private lateinit var trackerOwner: TextView
    private lateinit var hideOnMapSwitch: SwitchCompat
    private lateinit var unsubscribeButton: MaterialButton
    private lateinit var removeFromShareButton: MaterialButton
    private lateinit var closeButton: ImageButton

    private var currentTracker: Tracker? = null
    private var suppressHideSwitchCallback = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_edit_shared_tracker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        trackerName = view.findViewById(R.id.editSharedTrackerName)
        trackerOwner = view.findViewById(R.id.editSharedTrackerOwner)
        hideOnMapSwitch = view.findViewById(R.id.editSharedTrackerHideOnMapSwitch)
        unsubscribeButton = view.findViewById(R.id.editSharedTrackerUnsubscribe)
        removeFromShareButton = view.findViewById(R.id.editSharedTrackerRemoveFromShare)
        closeButton = view.findViewById(R.id.editSharedTrackerClose)

        closeButton.setOnClickListener { parentFragmentManager.popBackStack() }
        val initialTracker = arguments?.getParcelable(ARG_TRACKER, Tracker::class.java)
        if (initialTracker == null) {
            parentFragmentManager.popBackStack()
            return
        }
        currentTracker = initialTracker
        renderTracker(initialTracker)
        bindActions(initialTracker.id)
        viewModel.load()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val hiddenIds = state.mapVisibility?.hidden_track_ids ?: emptyList()
                    suppressHideSwitchCallback = true
                    hideOnMapSwitch.isChecked = hiddenIds.contains(initialTracker.id)
                    suppressHideSwitchCallback = false
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let { navHost()?.showSnackbar(it) }
                    if (state.didLeave) {
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        }
    }

    private fun renderTracker(tracker: Tracker) {
        trackerName.text = tracker.name
        trackerOwner.text = tracker.owner_email?.takeIf { it.isNotBlank() } ?: ""

        removeFromShareButton.visibility = if ((tracker.visibility ?: "") == "shared") View.VISIBLE else View.GONE

        // visibility binding is handled through ViewModel state collection
    }

    private fun bindActions(trackerId: String) {
        hideOnMapSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressHideSwitchCallback) return@setOnCheckedChangeListener
            viewModel.setHidden(trackerId, isChecked)
        }

        unsubscribeButton.setOnClickListener {
            val d = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.unsubscribe_confirm_title))
                .setMessage(getString(R.string.unsubscribe_confirm_message))
                .setPositiveButton(getString(R.string.unsubscribe)) { _, _ ->
                    viewModel.unsubscribe(trackerId)
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_blue))
        }

        removeFromShareButton.setOnClickListener {
            val d = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.remove_from_share_confirm_title))
                .setMessage(getString(R.string.remove_from_share_confirm_message))
                .setPositiveButton(getString(R.string.remove_from_share)) { _, _ ->
                    viewModel.leaveShared(trackerId)
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_blue))
        }
    }
}

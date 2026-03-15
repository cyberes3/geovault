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
import com.geovault.tracker.MainActivity
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerRepository
import com.google.android.material.button.MaterialButton

class EditSharedTrackerFragment : Fragment() {

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
    }

    private fun renderTracker(tracker: Tracker) {
        trackerName.text = tracker.name
        trackerOwner.text = tracker.owner_email?.takeIf { it.isNotBlank() } ?: ""

        removeFromShareButton.visibility = if ((tracker.visibility ?: "") == "shared") View.VISIBLE else View.GONE

        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            requireActivity().runOnUiThread {
                suppressHideSwitchCallback = true
                hideOnMapSwitch.isChecked = visibility?.hidden_track_ids?.contains(tracker.id) == true
                suppressHideSwitchCallback = false
            }
        }
    }

    private fun bindActions(trackerId: String) {
        hideOnMapSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressHideSwitchCallback) return@setOnCheckedChangeListener
            TrackerRepository.getMapVisibility(requireContext()) { visibility ->
                if (!isAdded) return@getMapVisibility
                val current = (visibility?.hidden_track_ids ?: emptyList()).toMutableList()
                val updated = if (isChecked) {
                    if (current.contains(trackerId)) current else current + trackerId
                } else {
                    current.filter { it != trackerId }
                }
                TrackerRepository.patchMapVisibility(requireContext(), MapVisibilityRequest(hidden_track_ids = updated)) { _ -> }
            }
        }

        unsubscribeButton.setOnClickListener {
            val d = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.unsubscribe_confirm_title))
                .setMessage(getString(R.string.unsubscribe_confirm_message))
                .setPositiveButton(getString(R.string.unsubscribe)) { _, _ ->
                    TrackerRepository.unsubscribeTracker(requireContext(), trackerId) { success ->
                        if (!isAdded) return@unsubscribeTracker
                        requireActivity().runOnUiThread {
                            if (success) {
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.unsubscribed))
                                requireActivity().supportFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, Bundle())
                                parentFragmentManager.popBackStack()
                            } else {
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                            }
                        }
                    }
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
                    TrackerRepository.leaveShareWithMe(requireContext(), trackerId) { success ->
                        if (!isAdded) return@leaveShareWithMe
                        requireActivity().runOnUiThread {
                            if (success) {
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.removed_from_share))
                                requireActivity().supportFragmentManager.setFragmentResult(TrackersListFragment.REQUEST_REFRESH_LIST, Bundle())
                                parentFragmentManager.popBackStack()
                            } else {
                                (activity as? MainActivity)?.showSnackbar(getString(R.string.failed_to_load_tracker))
                            }
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_blue))
        }
    }
}

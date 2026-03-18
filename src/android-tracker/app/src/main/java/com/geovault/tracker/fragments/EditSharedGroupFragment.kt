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
import com.geovault.tracker.Group
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.R
import com.geovault.tracker.TrackerRepository
import com.google.android.material.button.MaterialButton

class EditSharedGroupFragment : Fragment() {

    companion object {
        const val ARG_GROUP = "group"
    }

    private lateinit var groupName: TextView
    private lateinit var groupOwner: TextView
    private lateinit var hideInListSwitch: SwitchCompat
    private lateinit var leaveButton: MaterialButton
    private lateinit var closeButton: ImageButton

    private var group: Group? = null
    private var suppressHideSwitchCallback = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_edit_shared_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        groupName = view.findViewById(R.id.editSharedGroupName)
        groupOwner = view.findViewById(R.id.editSharedGroupOwner)
        hideInListSwitch = view.findViewById(R.id.editSharedGroupHideInListSwitch)
        leaveButton = view.findViewById(R.id.editSharedGroupLeave)
        closeButton = view.findViewById(R.id.editSharedGroupClose)

        closeButton.setOnClickListener { parentFragmentManager.popBackStack() }
        val initialGroup = arguments?.getParcelable(ARG_GROUP, Group::class.java)
        if (initialGroup == null || initialGroup.is_owner == true) {
            parentFragmentManager.popBackStack()
            return
        }
        group = initialGroup
        renderGroup(initialGroup)
        bindActions()
    }

    private fun renderGroup(g: Group) {
        groupName.text = g.name
        groupOwner.text = g.owner_email?.takeIf { it.isNotBlank() } ?: ""

        TrackerRepository.getMapVisibility(requireContext()) { visibility ->
            if (!isAdded) return@getMapVisibility
            requireActivity().runOnUiThread {
                suppressHideSwitchCallback = true
                hideInListSwitch.isChecked = visibility?.hidden_group_ids?.contains(g.id) == true
                suppressHideSwitchCallback = false
            }
        }
    }

    private fun bindActions() {
        val g = group ?: return
        hideInListSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressHideSwitchCallback) return@setOnCheckedChangeListener
            TrackerRepository.getMapVisibility(requireContext()) { visibility ->
                if (!isAdded) return@getMapVisibility
                val current = (visibility?.hidden_group_ids ?: emptyList()).toMutableList()
                val updated = if (isChecked) {
                    if (current.contains(g.id)) current else current + g.id
                } else {
                    current.filter { it != g.id }
                }
                TrackerRepository.patchMapVisibility(
                    requireContext(),
                    MapVisibilityRequest(hidden_group_ids = updated)
                ) { _ -> }
            }
        }

        leaveButton.setOnClickListener {
            val d = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.group_leave_confirm_title))
                .setMessage(getString(R.string.group_leave_confirm_message))
                .setPositiveButton(getString(R.string.leave_group)) { _, _ ->
                    TrackerRepository.leaveGroup(requireContext(), g.id) { success ->
                        if (!isAdded) return@leaveGroup
                        requireActivity().runOnUiThread {
                            if (success) {
                                navHost()?.showSnackbar(getString(R.string.removed_from_share))
                                parentFragmentManager.setFragmentResult(
                                    GroupsListFragment.REQUEST_GROUPS_REFRESH,
                                    Bundle().apply {
                                        putString(SharedTrackersFragment.KEY_REMOVED_SHARED_GROUP_ID, g.id)
                                    }
                                )
                                parentFragmentManager.popBackStack()
                            } else {
                                navHost()?.showSnackbar(getString(R.string.failed_to_load_tracker))
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

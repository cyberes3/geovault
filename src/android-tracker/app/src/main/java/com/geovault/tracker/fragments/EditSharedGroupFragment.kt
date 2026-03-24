package com.geovault.tracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geovault.tracker.Group
import com.geovault.tracker.navigation.navHost
import com.geovault.tracker.R
import com.geovault.tracker.ui.applyDialogButtonColors
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditSharedGroupFragment : Fragment() {
    private val viewModel: EditSharedGroupViewModel by viewModels()

    companion object {
        const val ARG_GROUP = "group"
    }

    private lateinit var groupName: TextView
    private lateinit var groupOwner: TextView
    private lateinit var leaveButton: MaterialButton
    private lateinit var closeButton: ImageButton

    private var group: Group? = null
    private var pendingAction: PendingAction? = null

    private enum class PendingAction {
        LEAVE_GROUP
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_edit_shared_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        groupName = view.findViewById(R.id.editSharedGroupName)
        groupOwner = view.findViewById(R.id.editSharedGroupOwner)
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                        val failedAction = pendingAction
                        pendingAction = null
                        val failureMessageRes = when (failedAction) {
                            PendingAction.LEAVE_GROUP -> R.string.failed_to_leave_group
                            null -> R.string.failed_to_load_tracker
                        }
                        navHost()?.showSnackbar(getString(failureMessageRes))
                        viewModel.consumeError()
                    }
                    if (state.didLeave) {
                        pendingAction = null
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        }
    }

    private fun renderGroup(g: Group) {
        groupName.text = g.name
        groupOwner.text = g.owner_email?.takeIf { it.isNotBlank() } ?: ""
    }

    private fun bindActions() {
        val g = group ?: return
        leaveButton.setOnClickListener {
            val d = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.group_leave_confirm_title))
                .setMessage(getString(R.string.group_leave_confirm_message))
                .setPositiveButton(getString(R.string.leave_group)) { _, _ ->
                    pendingAction = PendingAction.LEAVE_GROUP
                    viewModel.leaveGroup(g.id)
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
            d.applyDialogButtonColors(requireContext(), destructiveAction = true)
        }
    }
}

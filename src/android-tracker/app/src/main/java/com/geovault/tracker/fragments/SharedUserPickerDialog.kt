package com.geovault.tracker.fragments

import androidx.fragment.app.Fragment
import com.geovault.tracker.R
import com.geovault.tracker.UserItem
import java.util.Locale

object SharedUserPickerDialog {

    fun show(
        fragment: Fragment,
        title: String,
        users: List<UserItem>,
        selectedEmails: Set<String>,
        onApply: (Set<String>) -> Unit
    ) {
        val normalizedSelected = selectedEmails
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotBlank() }
            .toSet()
        val items = users
            .map { it.email.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .map { it to it }

        MultiSelectPickerDialog.show(
            fragment = fragment,
            title = title,
            items = items,
            initialSelectedIds = normalizedSelected,
            hintText = fragment.getString(R.string.shared_with_click_to_toggle),
            emptyText = fragment.getString(R.string.no_other_users_found),
            onApply = onApply
        )
    }
}

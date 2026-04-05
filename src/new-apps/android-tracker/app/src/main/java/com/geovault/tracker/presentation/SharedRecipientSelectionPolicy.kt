package com.geovault.tracker.presentation

object SharedRecipientSelectionPolicy {
    fun toggle(currentRawEmails: String, email: String): String {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isEmpty()) return currentRawEmails
        val current = TrackerSharingSettingsPolicy.parseSharedEmails(currentRawEmails).toMutableSet()
        if (current.contains(normalizedEmail)) {
            current.remove(normalizedEmail)
        } else {
            current.add(normalizedEmail)
        }
        return current.toList().sorted().joinToString(", ")
    }
}

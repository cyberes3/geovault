package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedRecipientSelectionPolicyTest {

    @Test
    fun toggle_addsMissingRecipient() {
        val next = SharedRecipientSelectionPolicy.toggle(
            currentRawEmails = "a@example.com",
            email = "b@example.com"
        )
        assertEquals("a@example.com, b@example.com", next)
    }

    @Test
    fun toggle_removesExistingRecipient() {
        val next = SharedRecipientSelectionPolicy.toggle(
            currentRawEmails = "a@example.com, b@example.com",
            email = "a@example.com"
        )
        assertEquals("b@example.com", next)
    }
}

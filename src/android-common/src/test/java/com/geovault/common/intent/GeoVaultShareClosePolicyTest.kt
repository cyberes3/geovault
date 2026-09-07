package com.geovault.common.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultShareClosePolicyTest {

    @Test
    fun incomingShareColdStartExitsHost() {
        assertEquals(
            GeoVaultShareCloseAction.ExitHost,
            GeoVaultShareClosePolicy.decide(isIncomingShareFlow = true, keepHostOpen = false),
        )
    }

    @Test
    fun incomingShareOnRunningHostReturnsToSender() {
        assertEquals(
            GeoVaultShareCloseAction.ReturnToSender,
            GeoVaultShareClosePolicy.decide(isIncomingShareFlow = true, keepHostOpen = true),
        )
    }

    @Test
    fun pickerOrLocalFlowDismissesUi() {
        assertEquals(
            GeoVaultShareCloseAction.DismissLocalUi,
            GeoVaultShareClosePolicy.decide(isIncomingShareFlow = false, keepHostOpen = true),
        )
    }
}

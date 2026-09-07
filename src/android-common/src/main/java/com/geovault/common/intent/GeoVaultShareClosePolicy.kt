package com.geovault.common.intent

sealed interface GeoVaultShareCloseAction {
    data object ExitHost : GeoVaultShareCloseAction
    data object ReturnToSender : GeoVaultShareCloseAction
    data object DismissLocalUi : GeoVaultShareCloseAction
}

object GeoVaultShareClosePolicy {
    fun decide(
        isIncomingShareFlow: Boolean,
        keepHostOpen: Boolean,
    ): GeoVaultShareCloseAction {
        if (!isIncomingShareFlow) {
            return GeoVaultShareCloseAction.DismissLocalUi
        }
        return if (keepHostOpen) {
            GeoVaultShareCloseAction.ReturnToSender
        } else {
            GeoVaultShareCloseAction.ExitHost
        }
    }
}

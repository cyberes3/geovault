package com.geovault.common.ui.snackbar

fun interface GeoVaultSnackbarActionHandler {
    fun handle(actionId: String)
}

class GeoVaultSnackbarActionRouter(
    private val handlers: Map<String, () -> Unit>
) : GeoVaultSnackbarActionHandler {
    override fun handle(actionId: String) {
        handlers[actionId]?.invoke()
    }
}

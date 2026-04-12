package com.geovault.common.ui.snackbar

data class GeoVaultSnackbarAction(
    val label: String,
    val actionId: String
)

data class GeoVaultSnackbarModel(
    val id: String,
    val message: String,
    val action: GeoVaultSnackbarAction? = null
)

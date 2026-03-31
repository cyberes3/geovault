package com.geovault.common.ui.model

data class GeoVaultStatusRenderModel(
    val visualState: GeoVaultStatusVisualState,
    val title: String? = null,
    val message: String = "",
    val primaryAction: GeoVaultActionRenderModel? = null,
    val secondaryAction: GeoVaultActionRenderModel? = null
)

data class GeoVaultActionRenderModel(
    val label: String,
    val enabled: Boolean = true
)

enum class GeoVaultStatusVisualState {
    Loading,
    Success,
    Error,
    Info
}

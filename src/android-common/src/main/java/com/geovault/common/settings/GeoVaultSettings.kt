package com.geovault.common.settings

import kotlinx.serialization.Serializable

@Serializable
internal data class GeoVaultSettings(
    val schemaVersion: Int = 1,
    val boolValues: Map<String, Boolean> = emptyMap(),
    val stringValues: Map<String, String> = emptyMap(),
    val intValues: Map<String, Int> = emptyMap(),
    val longValues: Map<String, Long> = emptyMap(),
    val floatValues: Map<String, Float> = emptyMap()
)

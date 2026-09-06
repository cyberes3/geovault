package com.geovault.common.settings

import kotlinx.serialization.json.JsonObject

interface GeoVaultDocumentMigration {
    val fromVersion: Int
    fun migrate(json: JsonObject): JsonObject
}

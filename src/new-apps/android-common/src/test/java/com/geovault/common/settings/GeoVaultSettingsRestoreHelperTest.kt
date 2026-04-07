package com.geovault.common.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultSettingsRestoreHelperTest {
    private val registry = PrefKeyRegistry(
        boolKeys = setOf("add_suffix"),
        stringKeys = setOf("server_url")
    )

    @Test
    fun normalize_updates_schema_and_prunes_unknown_keys() {
        val helper = GeoVaultSettingsRestoreHelper(schemaVersion = 3, keyRegistry = registry)
        val source = GeoVaultSettings(
            schemaVersion = 1,
            boolValues = mapOf("add_suffix" to true, "deprecated_flag" to true),
            stringValues = mapOf(
                "server_url" to "https://example.com",
                "deprecated_url" to "https://deprecated.example.com"
            )
        )

        val normalized = helper.normalize(source)

        assertEquals(3, normalized.schemaVersion)
        assertTrue(normalized.boolValues.containsKey("add_suffix"))
        assertFalse(normalized.boolValues.containsKey("deprecated_flag"))
        assertTrue(normalized.stringValues.containsKey("server_url"))
        assertFalse(normalized.stringValues.containsKey("deprecated_url"))
    }

    @Test
    fun normalize_is_idempotent_when_already_valid() {
        val helper = GeoVaultSettingsRestoreHelper(schemaVersion = 3, keyRegistry = registry)
        val source = GeoVaultSettings(
            schemaVersion = 3,
            boolValues = mapOf("add_suffix" to true),
            stringValues = mapOf("server_url" to "https://example.com")
        )

        val normalized = helper.normalize(source)

        assertEquals(source, normalized)
    }
}

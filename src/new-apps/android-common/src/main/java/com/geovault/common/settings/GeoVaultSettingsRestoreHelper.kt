package com.geovault.common.settings

internal data class PrefKeyRegistry(
    val boolKeys: Set<String> = emptySet(),
    val stringKeys: Set<String> = emptySet(),
    val intKeys: Set<String> = emptySet(),
    val longKeys: Set<String> = emptySet(),
    val floatKeys: Set<String> = emptySet()
) {
    companion object {
        fun fromKeys(keys: Set<PrefKey<*>>): PrefKeyRegistry {
            val boolKeys = linkedSetOf<String>()
            val stringKeys = linkedSetOf<String>()
            val intKeys = linkedSetOf<String>()
            val longKeys = linkedSetOf<String>()
            val floatKeys = linkedSetOf<String>()
            keys.forEach { key ->
                when (key) {
                    is PrefKey.BooleanKey -> boolKeys += key.name
                    is PrefKey.StringKey -> stringKeys += key.name
                    is PrefKey.IntKey -> intKeys += key.name
                    is PrefKey.LongKey -> longKeys += key.name
                    is PrefKey.FloatKey -> floatKeys += key.name
                }
            }
            return PrefKeyRegistry(
                boolKeys = boolKeys,
                stringKeys = stringKeys,
                intKeys = intKeys,
                longKeys = longKeys,
                floatKeys = floatKeys
            )
        }
    }
}

internal class GeoVaultSettingsRestoreHelper(
    private val schemaVersion: Int,
    private val keyRegistry: PrefKeyRegistry
) {
    fun defaultValue(): GeoVaultSettings {
        return GeoVaultSettings(schemaVersion = schemaVersion)
    }

    fun normalize(settings: GeoVaultSettings): GeoVaultSettings {
        val normalized = settings.copy(
            schemaVersion = schemaVersion,
            boolValues = pruneUnknown(settings.boolValues, keyRegistry.boolKeys),
            stringValues = pruneUnknown(settings.stringValues, keyRegistry.stringKeys),
            intValues = pruneUnknown(settings.intValues, keyRegistry.intKeys),
            longValues = pruneUnknown(settings.longValues, keyRegistry.longKeys),
            floatValues = pruneUnknown(settings.floatValues, keyRegistry.floatKeys)
        )
        return if (normalized == settings) settings else normalized
    }

    private fun <T> pruneUnknown(map: Map<String, T>, knownKeys: Set<String>): Map<String, T> {
        if (knownKeys.isEmpty()) {
            return map
        }
        return map.filterKeys { key -> knownKeys.contains(key) }
    }
}

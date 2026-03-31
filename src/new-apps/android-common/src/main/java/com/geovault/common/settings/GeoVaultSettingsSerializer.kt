package com.geovault.common.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class GeoVaultSettingsSerializer(
    private val defaultValueProvider: () -> GeoVaultSettings
) : Serializer<GeoVaultSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: GeoVaultSettings = defaultValueProvider()

    override suspend fun readFrom(input: InputStream): GeoVaultSettings {
        try {
            val text = input.readBytes().decodeToString()
            return if (text.isBlank()) defaultValue else json.decodeFromString(GeoVaultSettings.serializer(), text)
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read GeoVault settings data.", exception)
        } catch (exception: IllegalArgumentException) {
            throw CorruptionException("Cannot read GeoVault settings data.", exception)
        }
    }

    override suspend fun writeTo(t: GeoVaultSettings, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

package com.geovault.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultDocumentStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun get_returnsDefaultDocument() = runBlocking {
        val store = sampleStore("default")

        assertEquals(SampleDocument(), store.get())
    }

    @Test
    fun update_persistsDocument() = runBlocking {
        val store = sampleStore("persist")

        store.update { current -> current.copy(name = "saved") }

        assertEquals("saved", store.get().name)
        val onDisk = datastoreFile("persist").readText()
        assertTrue(onDisk.contains("\"name\":\"saved\""))
        assertTrue(onDisk.contains("\"schemaVersion\":2"))
        assertTrue(onDisk.contains("\"payload\""))
    }

    @Test
    fun migratesFromPreviousVersionToCurrent() = runBlocking {
        val file = datastoreFile("migrate")
        file.parentFile?.mkdirs()
        file.writeText("""{"schemaVersion":1,"payload":{"name":"alpha"}}""")

        val store = sampleStore("migrate")
        val loaded = store.get()

        assertEquals("alpha", loaded.name)
        assertEquals("migrated", loaded.label)
        val rewritten = file.readText()
        assertTrue(rewritten.contains("\"schemaVersion\":2"))
        assertTrue(rewritten.contains("\"label\":\"migrated\""))
    }

    @Test
    fun authSettingsDocument_encryptedSecretRoundTrip() = runBlocking {
        val store = GeoVaultDocumentStore(
            context = context,
            fileName = uniqueFileName("auth"),
            documentSerializer = AuthSettingsDocument.serializer(),
            defaultValue = AuthSettingsDocument(),
            currentVersion = AuthSettingsDocument.SCHEMA_VERSION,
            legacyMapper = AuthSettingsDocument::fromLegacy,
        )

        store.update { current ->
            current.copy(accessToken = GeoVaultSecureString.encrypt("secret-token"))
        }

        val loaded = store.get()
        assertEquals("secret-token", loaded.accessToken?.decrypt())
        assertTrue(loaded.accessToken?.ciphertext?.startsWith("v1:") == true)
        assertTrue(loaded.accessToken?.ciphertext?.contains("secret-token") != true)
    }

    @Test
    fun migratesSamePathLegacyMapBlob() = runBlocking {
        val file = datastoreFile("legacy-auth")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "schemaVersion": 1,
              "boolValues": {},
              "stringValues": {
                "server_url": "https://example.com",
                "last_consumed_pkce_state": "plain-state"
              },
              "intValues": {},
              "longValues": {
                "expires_at": 42
              },
              "floatValues": {}
            }
            """.trimIndent()
        )

        val store = GeoVaultDocumentStore(
            context = context,
            fileName = uniqueFileName("legacy-auth"),
            documentSerializer = AuthSettingsDocument.serializer(),
            defaultValue = AuthSettingsDocument(),
            currentVersion = AuthSettingsDocument.SCHEMA_VERSION,
            legacyMapper = AuthSettingsDocument::fromLegacy,
        )

        val loaded = store.get()
        assertEquals("https://example.com", loaded.serverUrl)
        assertEquals(42L, loaded.expiresAt)
        assertEquals("plain-state", loaded.lastConsumedPkceState?.decrypt())
        assertTrue(loaded.lastConsumedPkceState?.ciphertext?.startsWith("v1:") == true)
        assertNull(loaded.accessToken)
        assertTrue(file.readText().contains("\"payload\""))
    }

    @Test
    fun migratesAlternateLegacyFileWhenNewFileMissing() = runBlocking {
        val legacyFile = datastoreFile("geovault_prefs")
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText(
            """
            {
              "schemaVersion": 1,
              "boolValues": {"add_suffix": false},
              "stringValues": {},
              "intValues": {},
              "longValues": {},
              "floatValues": {}
            }
            """.trimIndent()
        )

        val store = GeoVaultDocumentStore(
            context = context,
            fileName = uniqueFileName("uploader_options"),
            documentSerializer = SampleUploaderDocument.serializer(),
            defaultValue = SampleUploaderDocument(),
            currentVersion = 1,
            legacyFileName = uniqueFileName("geovault_prefs"),
            legacyMapper = { blob ->
                SampleUploaderDocument(addFilenameSuffix = blob.boolValues["add_suffix"] ?: true)
            },
        )

        assertEquals(false, store.get().addFilenameSuffix)
        assertTrue(datastoreFile("uploader_options").exists())
        assertTrue(legacyFile.exists())
    }

    private fun sampleStore(label: String): GeoVaultDocumentStore<SampleDocument> {
        return GeoVaultDocumentStore(
            context = context,
            fileName = uniqueFileName(label),
            documentSerializer = SampleDocument.serializer(),
            defaultValue = SampleDocument(),
            currentVersion = 2,
            migrations = listOf(SampleV1ToV2Migration),
        )
    }

    private fun uniqueFileName(label: String): String = "$label.settings"

    private fun datastoreFile(label: String): File {
        return File(context.filesDir, "datastore/${uniqueFileName(label)}")
    }
}

@Serializable
private data class SampleDocument(
    val name: String = "",
    val label: String = "",
)

@Serializable
private data class SampleUploaderDocument(
    val addFilenameSuffix: Boolean = true,
)

private object SampleV1ToV2Migration : GeoVaultDocumentMigration {
    override val fromVersion: Int = 1

    override fun migrate(json: JsonObject): JsonObject {
        return buildJsonObject {
            json.forEach { (key, value) -> put(key, value) }
            put("label", json["label"]?.jsonPrimitive?.contentOrNull ?: "migrated")
        }
    }
}

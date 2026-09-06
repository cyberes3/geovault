package com.geovault.common.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GeoVaultDocumentStore<T>(
    context: Context,
    fileName: String,
    documentSerializer: KSerializer<T>,
    val defaultValue: T,
    private val currentVersion: Int,
    migrations: List<GeoVaultDocumentMigration> = emptyList(),
    legacyFileName: String? = null,
    private val legacyMapper: ((GeoVaultLegacySettingsBlob) -> T)? = null,
) {
    private val appContext = context.applicationContext
    private val resolvedFileName = resolveFileName(fileName)
    private val resolvedLegacyFileName = legacyFileName?.let(::resolveFileName)
    private val filePath = File(appContext.filesDir, "datastore/$resolvedFileName").absolutePath
    private val fileSerializer = GeoVaultDocumentSerializer(
        filePath = filePath,
        documentSerializer = documentSerializer,
        defaultValue = defaultValue,
        currentVersion = currentVersion,
        migrations = migrations,
        legacyMapper = legacyMapper,
    )
    private val dataStore = dataStoreFor(
        filePath = filePath,
        signature = "$resolvedFileName|$currentVersion|${documentSerializer.descriptor.serialName}",
        serializer = fileSerializer,
    )
    private val migrateMutex = Mutex()

    @Volatile
    private var migrated = false

    val data: Flow<T> = flow {
        ensureMigrated()
        emitAll(dataStore.data)
    }.distinctUntilChanged()

    suspend fun get(): T = data.first()

    suspend fun update(transform: (T) -> T) {
        ensureMigrated()
        dataStore.updateData(transform)
    }

    private suspend fun ensureMigrated() {
        if (migrated) return
        migrateMutex.withLock {
            if (migrated) return
            importAlternateLegacyFileIfNeeded()
            val current = dataStore.data.first()
            if (GeoVaultDocumentRewriteTracker.consume(filePath)) {
                rewriteFile(current)
            }
            migrated = true
        }
    }

    private suspend fun importAlternateLegacyFileIfNeeded() {
        if (legacyMapper == null || resolvedLegacyFileName == null) return
        val newFile = File(filePath)
        if (newFile.exists() && newFile.length() > 0L) return
        val legacyFile = File(appContext.filesDir, "datastore/$resolvedLegacyFileName")
        val blob = GeoVaultLegacySettingsBlob.readFrom(legacyFile) ?: return
        val mapped = legacyMapper.invoke(blob)
        dataStore.updateData { mapped }
    }

    private suspend fun rewriteFile(current: T) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            fileSerializer.writeTo(current, output)
        }
    }

    private companion object {
        private val dataStoresByPath = ConcurrentHashMap<String, DataStore<*>>()
        private val signaturesByPath = ConcurrentHashMap<String, String>()

        fun resolveFileName(name: String): String {
            return if (name.endsWith(".settings")) name else "$name.settings"
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> dataStoreFor(
            filePath: String,
            signature: String,
            serializer: Serializer<T>,
        ): DataStore<T> {
            signaturesByPath[filePath]?.let { knownSignature ->
                require(knownSignature == signature) {
                    "GeoVaultDocumentStore reused with conflicting schema for file: $filePath"
                }
            }
            return dataStoresByPath.getOrPut(filePath) {
                signaturesByPath[filePath] = signature
                DataStoreFactory.create(
                    serializer = serializer,
                    corruptionHandler = ReplaceFileCorruptionHandler {
                        serializer.defaultValue
                    },
                    produceFile = {
                        val file = File(filePath)
                        file.parentFile?.mkdirs()
                        file
                    },
                )
            } as DataStore<T>
        }
    }
}

internal object GeoVaultDocumentRewriteTracker {
    private val needsRewrite = ConcurrentHashMap<String, Boolean>()

    fun mark(filePath: String) {
        needsRewrite[filePath] = true
    }

    fun consume(filePath: String): Boolean {
        return needsRewrite.remove(filePath) == true
    }
}

internal val geoVaultDocumentJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private class GeoVaultDocumentSerializer<T>(
    private val filePath: String,
    private val documentSerializer: KSerializer<T>,
    override val defaultValue: T,
    private val currentVersion: Int,
    private val migrations: List<GeoVaultDocumentMigration>,
    private val legacyMapper: ((GeoVaultLegacySettingsBlob) -> T)?,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T {
        try {
            val text = input.readBytes().decodeToString()
            if (text.isBlank()) return defaultValue
            val element = geoVaultDocumentJson.parseToJsonElement(text)
            if (GeoVaultLegacySettingsBlob.isLegacyMapBlob(element)) {
                GeoVaultDocumentRewriteTracker.mark(filePath)
                val blob = geoVaultDocumentJson.decodeFromJsonElement(
                    GeoVaultLegacySettingsBlob.serializer(),
                    element,
                )
                return legacyMapper?.invoke(blob) ?: defaultValue
            }
            val envelope = element as? JsonObject
                ?: throw CorruptionException("Cannot read GeoVault document envelope.")
            val version = envelope["schemaVersion"]?.jsonPrimitive?.intOrNull
                ?: throw CorruptionException("Cannot read GeoVault document version.")
            val rawPayload = envelope["payload"] as? JsonObject
                ?: throw CorruptionException("Cannot read GeoVault document payload.")
            val payload = if (version < currentVersion) {
                GeoVaultDocumentRewriteTracker.mark(filePath)
                applyMigrations(rawPayload, version)
            } else {
                rawPayload
            }
            return geoVaultDocumentJson.decodeFromJsonElement(documentSerializer, payload)
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read GeoVault document data.", exception)
        } catch (exception: IllegalArgumentException) {
            throw CorruptionException("Cannot read GeoVault document data.", exception)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        val payload = geoVaultDocumentJson.encodeToJsonElement(documentSerializer, t).jsonObject
        val envelope = buildJsonObject {
            put("schemaVersion", currentVersion)
            put("payload", payload)
        }
        output.write(geoVaultDocumentJson.encodeToString(JsonObject.serializer(), envelope).encodeToByteArray())
    }

    private fun applyMigrations(payload: JsonObject, fromVersion: Int): JsonObject {
        val byFrom = migrations.associateBy { migration -> migration.fromVersion }
        var version = fromVersion
        var current = payload
        while (version < currentVersion) {
            val migration = byFrom[version]
                ?: throw IllegalStateException(
                    "Missing GeoVaultDocumentMigration fromVersion=$version for $filePath"
                )
            current = migration.migrate(current)
            version += 1
        }
        return current
    }
}

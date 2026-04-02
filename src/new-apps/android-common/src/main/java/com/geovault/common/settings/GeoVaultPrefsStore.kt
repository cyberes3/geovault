package com.geovault.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed class PrefKey<T>(val name: String, val defaultValue: T) {
    class BooleanKey(name: String, defaultValue: Boolean = false) : PrefKey<Boolean>(name, defaultValue)
    class StringKey(name: String, defaultValue: String = "") : PrefKey<String>(name, defaultValue)
    class IntKey(name: String, defaultValue: Int = 0) : PrefKey<Int>(name, defaultValue)
    class LongKey(name: String, defaultValue: Long = 0L) : PrefKey<Long>(name, defaultValue)
    class FloatKey(name: String, defaultValue: Float = 0f) : PrefKey<Float>(name, defaultValue)
}

class GeoVaultPrefsStore(
    context: Context,
    prefsName: String,
    schemaVersion: Int = 1,
    registeredKeys: Set<PrefKey<*>> = emptySet()
) {
    private val appContext = context.applicationContext
    private val registry = PrefKeyRegistry.fromKeys(registeredKeys)
    private val restoreHelper = GeoVaultSettingsRestoreHelper(schemaVersion = schemaVersion, keyRegistry = registry)
    private val filePath = File(appContext.filesDir, "datastore/$prefsName.settings").absolutePath
    private val dataStore = dataStoreFor(
        filePath = filePath,
        restoreHelper = restoreHelper,
        configSignature = buildConfigSignature(schemaVersion, registry)
    )

    suspend fun normalize() {
        dataStore.updateData { current ->
            restoreHelper.normalize(current)
        }
    }

    /**
     * Eagerly loads and normalizes the full settings payload so DataStore is hot
     * before UI/state observers depend on it during app startup.
     */
    suspend fun preloadAllData() {
        normalize()
        dataStore.data.take(1).first()
    }

    suspend fun <T> get(key: PrefKey<T>): T {
        return observe(key).first()
    }

    suspend fun <T> put(key: PrefKey<T>, value: T) {
        dataStore.updateData { current ->
            val normalized = restoreHelper.normalize(current)
            when (key) {
                is PrefKey.BooleanKey -> normalized.copy(
                    boolValues = normalized.boolValues + (key.name to (value as Boolean))
                )
                is PrefKey.StringKey -> normalized.copy(
                    stringValues = normalized.stringValues + (key.name to (value as String))
                )
                is PrefKey.IntKey -> normalized.copy(
                    intValues = normalized.intValues + (key.name to (value as Int))
                )
                is PrefKey.LongKey -> normalized.copy(
                    longValues = normalized.longValues + (key.name to (value as Long))
                )
                is PrefKey.FloatKey -> normalized.copy(
                    floatValues = normalized.floatValues + (key.name to (value as Float))
                )
            }
        }
    }

    suspend fun remove(key: PrefKey<*>) {
        dataStore.updateData { current ->
            val normalized = restoreHelper.normalize(current)
            when (key) {
                is PrefKey.BooleanKey -> normalized.copy(
                    boolValues = normalized.boolValues - key.name
                )
                is PrefKey.StringKey -> normalized.copy(
                    stringValues = normalized.stringValues - key.name
                )
                is PrefKey.IntKey -> normalized.copy(
                    intValues = normalized.intValues - key.name
                )
                is PrefKey.LongKey -> normalized.copy(
                    longValues = normalized.longValues - key.name
                )
                is PrefKey.FloatKey -> normalized.copy(
                    floatValues = normalized.floatValues - key.name
                )
            }
        }
    }

    suspend fun clear() {
        dataStore.updateData {
            restoreHelper.defaultValue()
        }
    }

    fun clearBlocking(): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                clear()
            }
            true
        }.getOrElse {
            false
        }
    }

    fun <T> getBlocking(key: PrefKey<T>): T {
        return runBlocking(Dispatchers.IO) { get(key) }
    }

    fun <T> putBlocking(key: PrefKey<T>, value: T) {
        runBlocking(Dispatchers.IO) { put(key, value) }
    }

    fun removeBlocking(key: PrefKey<*>) {
        runBlocking(Dispatchers.IO) { remove(key) }
    }

    /**
     * Applies multiple put/remove operations in a single atomic DataStore transaction.
     * Map values that are `null` remove the key; non-null values set it.
     * Value types must match their [PrefKey] type at runtime.
     */
    suspend fun putBatch(changes: Map<PrefKey<*>, Any?>) {
        if (changes.isEmpty()) return
        dataStore.updateData { current ->
            var result = restoreHelper.normalize(current)
            for ((key, value) in changes) {
                result = if (value == null) applyRemove(result, key) else applyPut(result, key, value)
            }
            result
        }
    }

    fun putBatchBlocking(changes: Map<PrefKey<*>, Any?>) {
        runBlocking(Dispatchers.IO) { putBatch(changes) }
    }

    fun preloadAllDataBlocking(): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                preloadAllData()
            }
            true
        }.getOrElse {
            false
        }
    }

    fun <T> observe(key: PrefKey<T>, emitInitial: Boolean = true): Flow<T> {
        var flow = dataStore.data
            .map { settings -> readValue(settings, key) }
            .distinctUntilChanged()
        if (!emitInitial) {
            flow = flow.drop(1)
        }
        return flow
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyPut(settings: GeoVaultSettings, key: PrefKey<*>, value: Any): GeoVaultSettings {
        return when (key) {
            is PrefKey.BooleanKey -> settings.copy(boolValues = settings.boolValues + (key.name to (value as Boolean)))
            is PrefKey.StringKey -> settings.copy(stringValues = settings.stringValues + (key.name to (value as String)))
            is PrefKey.IntKey -> settings.copy(intValues = settings.intValues + (key.name to (value as Int)))
            is PrefKey.LongKey -> settings.copy(longValues = settings.longValues + (key.name to (value as Long)))
            is PrefKey.FloatKey -> settings.copy(floatValues = settings.floatValues + (key.name to (value as Float)))
        }
    }

    private fun applyRemove(settings: GeoVaultSettings, key: PrefKey<*>): GeoVaultSettings {
        return when (key) {
            is PrefKey.BooleanKey -> settings.copy(boolValues = settings.boolValues - key.name)
            is PrefKey.StringKey -> settings.copy(stringValues = settings.stringValues - key.name)
            is PrefKey.IntKey -> settings.copy(intValues = settings.intValues - key.name)
            is PrefKey.LongKey -> settings.copy(longValues = settings.longValues - key.name)
            is PrefKey.FloatKey -> settings.copy(floatValues = settings.floatValues - key.name)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readValue(settings: GeoVaultSettings, key: PrefKey<T>): T {
        return when (key) {
            is PrefKey.BooleanKey -> settings.boolValues[key.name] ?: key.defaultValue
            is PrefKey.StringKey -> settings.stringValues[key.name] ?: key.defaultValue
            is PrefKey.IntKey -> settings.intValues[key.name] ?: key.defaultValue
            is PrefKey.LongKey -> settings.longValues[key.name] ?: key.defaultValue
            is PrefKey.FloatKey -> settings.floatValues[key.name] ?: key.defaultValue
        } as T
    }

    private companion object {
        private val dataStoresByPath = ConcurrentHashMap<String, DataStore<GeoVaultSettings>>()
        private val signaturesByPath = ConcurrentHashMap<String, String>()

        private fun dataStoreFor(
            filePath: String,
            restoreHelper: GeoVaultSettingsRestoreHelper,
            configSignature: String
        ): DataStore<GeoVaultSettings> {
            signaturesByPath[filePath]?.let { knownSignature ->
                require(knownSignature == configSignature) {
                    "GeoVaultPrefsStore reused with conflicting schema for file: $filePath"
                }
            }
            return dataStoresByPath.getOrPut(filePath) {
                signaturesByPath[filePath] = configSignature
                DataStoreFactory.create(
                    serializer = GeoVaultSettingsSerializer(restoreHelper::defaultValue),
                    corruptionHandler = ReplaceFileCorruptionHandler {
                        restoreHelper.defaultValue()
                    },
                    produceFile = {
                        val file = File(filePath)
                        file.parentFile?.mkdirs()
                        file
                    }
                )
            }
        }

        private fun buildConfigSignature(schemaVersion: Int, registry: PrefKeyRegistry): String {
            val allKeys = buildList {
                addAll(registry.boolKeys.map { "b:$it" })
                addAll(registry.stringKeys.map { "s:$it" })
                addAll(registry.intKeys.map { "i:$it" })
                addAll(registry.longKeys.map { "l:$it" })
                addAll(registry.floatKeys.map { "f:$it" })
            }.sorted()
            return "schema=$schemaVersion;keys=${allKeys.joinToString(",")}"
        }
    }
}

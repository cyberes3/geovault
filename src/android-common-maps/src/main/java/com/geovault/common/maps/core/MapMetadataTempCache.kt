package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import com.geovault.common.maps.model.TileSourceResponse
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object MapMetadataTempCache {
    private const val TAG = "MapMetadataTempCache"
    private const val CACHE_DIR_NAME = "geovault-map-metadata"
    private const val TILE_SOURCE_PREFIX = "tile-sources-"
    private const val STYLE_PREFIX = "style-"
    private const val JSON_EXTENSION = ".json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun writeTileSources(context: Context, serverUrl: String, response: TileSourceResponse) {
        val file = tileSourcesFile(context, serverUrl)
        writeText(file, json.encodeToString(response))
    }

    fun readTileSources(context: Context, serverUrl: String): TileSourceResponse? {
        val file = tileSourcesFile(context, serverUrl)
        return readText(file)?.let { payload ->
            runCatching { json.decodeFromString<TileSourceResponse>(payload) }
                .onFailure { Log.w(TAG, "Failed reading cached tile sources: ${file.absolutePath}", it) }
                .getOrNull()
        }
    }

    fun writeStyleJson(context: Context, styleUrl: String, styleJson: String) {
        writeText(styleFile(context, styleUrl), styleJson)
    }

    fun readStyleJson(context: Context, styleUrl: String): String? =
        readText(styleFile(context, styleUrl))?.takeIf { it.isNotBlank() }

    fun clearAll(context: Context) {
        val directory = cacheDirectory(context)
        if (!directory.exists()) return
        directory.deleteRecursively()
    }

    internal fun cacheDirectory(context: Context): File =
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME)

    internal fun tileSourcesFile(context: Context, serverUrl: String): File =
        File(cacheDirectory(context), "$TILE_SOURCE_PREFIX${sha256(serverUrl.trimEnd('/'))}$JSON_EXTENSION")

    internal fun styleFile(context: Context, styleUrl: String): File =
        File(cacheDirectory(context), "$STYLE_PREFIX${sha256(styleUrl)}$JSON_EXTENSION")

    private fun writeText(file: File, payload: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload)
        }.onFailure {
            Log.w(TAG, "Failed writing map metadata cache: ${file.absolutePath}", it)
        }
    }

    private fun readText(file: File): String? {
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { Log.w(TAG, "Failed reading map metadata cache: ${file.absolutePath}", it) }
            .getOrNull()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

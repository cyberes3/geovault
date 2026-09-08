package com.geovault.places.data

import com.geovault.common.geo.LonLat
import com.geovault.common.settings.GeoVaultDocumentMigration
import com.geovault.common.settings.GeoVaultLegacySettingsBlob
import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceContent
import com.geovault.places.model.PlaceKey
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class PlaceContentDto(
    val name: String,
    val description: String = "",
    val longitude: Double,
    val latitude: Double,
    val address: String? = null,
) {
    fun toDomain(): PlaceContent? {
        val location = LonLat(longitude = longitude, latitude = latitude)
        if (!location.isValidGeographic()) return null
        if (name.isBlank()) return null
        return PlaceContent(
            name = name,
            description = description,
            location = location,
            address = address?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        fun from(content: PlaceContent): PlaceContentDto {
            return PlaceContentDto(
                name = content.name,
                description = content.description,
                longitude = content.location.longitude,
                latitude = content.location.latitude,
                address = content.address,
            )
        }
    }
}

@Serializable
sealed class PendingChangeDto {
    @Serializable
    @SerialName("create")
    data object Create : PendingChangeDto()

    @Serializable
    @SerialName("update")
    data class Update(val baseline: PlaceContentDto) : PendingChangeDto()

    @Serializable
    @SerialName("delete")
    data class Delete(val baseline: PlaceContentDto) : PendingChangeDto()

    fun toDomain(): PendingChange? {
        return when (this) {
            Create -> PendingChange.Create
            is Update -> PendingChange.Update(baseline.toDomain() ?: return null)
            is Delete -> PendingChange.Delete(baseline.toDomain() ?: return null)
        }
    }

    companion object {
        fun from(pending: PendingChange): PendingChangeDto {
            return when (pending) {
                PendingChange.Create -> Create
                is PendingChange.Update -> Update(PlaceContentDto.from(pending.baseline))
                is PendingChange.Delete -> Delete(PlaceContentDto.from(pending.baseline))
            }
        }
    }
}

@Serializable
data class PlaceRecordDto(
    val key: String,
    val serverId: Int? = null,
    val name: String,
    val description: String = "",
    val longitude: Double,
    val latitude: Double,
    val address: String? = null,
    val createdAt: String? = null,
    val pending: PendingChangeDto? = null,
) {
    fun toDomain(): Place? {
        val content = PlaceContentDto(
            name = name,
            description = description,
            longitude = longitude,
            latitude = latitude,
            address = address,
        ).toDomain() ?: return null
        return Place(
            key = PlaceKey(key),
            serverId = serverId,
            content = content,
            createdAt = createdAt,
            pending = pending?.toDomain(),
        )
    }

    companion object {
        fun from(place: Place): PlaceRecordDto {
            return PlaceRecordDto(
                key = place.key.value,
                serverId = place.serverId,
                name = place.content.name,
                description = place.content.description,
                longitude = place.content.location.longitude,
                latitude = place.content.location.latitude,
                address = place.content.address,
                createdAt = place.createdAt,
                pending = place.pending?.let { PendingChangeDto.from(it) },
            )
        }
    }
}

@Serializable
data class PlacesCacheDocument(
    val places: List<PlaceRecordDto> = emptyList(),
    val lastSyncMillis: Long = 0L,
) {
    fun toDomain(): List<Place> = places.mapNotNull { it.toDomain() }

    companion object {
        const val SCHEMA_VERSION = 2
        const val FILE_NAME = "geovault_places_cache.settings"

        val MIGRATIONS: List<GeoVaultDocumentMigration> = listOf(MigratePlacesV1ToV2)

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromLegacy(blob: GeoVaultLegacySettingsBlob): PlacesCacheDocument {
            return PlacesV1Converter.fromLists(
                cachedRaw = blob.stringValues["cached_places"],
                offlineRaw = blob.stringValues["offline_places"],
                lastSyncMillis = blob.longValues["last_sync_time"] ?: 0L,
            )
        }

        internal fun decode(raw: String): PlacesCacheDocument {
            return json.decodeFromString(serializer(), raw)
        }
    }
}

object MigratePlacesV1ToV2 : GeoVaultDocumentMigration {
    override val fromVersion: Int = 1

    override fun migrate(json: JsonObject): JsonObject {
        val cached = json["cached"] as? JsonArray ?: JsonArray(emptyList())
        val offline = json["offline"] as? JsonArray ?: JsonArray(emptyList())
        val lastSync = json["lastSyncMillis"]?.jsonPrimitive?.longOrNull ?: 0L
        val document = PlacesV1Converter.fromJsonArrays(cached, offline, lastSync)
        val payload = PlacesV1Converter.encodeDocument(document)
        return payload
    }
}

internal object PlacesV1Converter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun fromLists(
        cachedRaw: String?,
        offlineRaw: String?,
        lastSyncMillis: Long,
    ): PlacesCacheDocument {
        val cached = parseFeatureCollection(cachedRaw)
        val offline = parseOffline(offlineRaw)
        return merge(cached, offline, lastSyncMillis)
    }

    fun fromJsonArrays(
        cached: JsonArray,
        offline: JsonArray,
        lastSyncMillis: Long,
    ): PlacesCacheDocument {
        val cachedFeatures = cached.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(V1Feature.serializer(), element) }.getOrNull()
        }
        val offlineFeatures = offline.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(V1OfflineFeature.serializer(), element) }.getOrNull()
        }
        return merge(cachedFeatures, offlineFeatures, lastSyncMillis)
    }

    fun encodeDocument(document: PlacesCacheDocument): JsonObject {
        return json.encodeToJsonElement(PlacesCacheDocument.serializer(), document).jsonObject
    }

    private fun merge(
        cached: List<V1Feature>,
        offline: List<V1OfflineFeature>,
        lastSyncMillis: Long,
    ): PlacesCacheDocument {
        val byServerId = linkedMapOf<Int, PlaceRecordDto>()
        val locals = mutableListOf<PlaceRecordDto>()
        cached.forEach { feature ->
            val record = feature.toRecord(pending = null) ?: return@forEach
            val serverId = record.serverId
            if (serverId != null) {
                byServerId[serverId] = record
            } else {
                locals.add(record)
            }
        }
        offline.forEach { entry ->
            val record = entry.toRecord() ?: return@forEach
            val serverId = record.serverId
            if (serverId != null) {
                byServerId[serverId] = record
            } else {
                locals.add(record)
            }
        }
        return PlacesCacheDocument(
            places = byServerId.values.toList() + locals,
            lastSyncMillis = lastSyncMillis,
        )
    }

    private fun parseFeatureCollection(raw: String?): List<V1Feature> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(V1FeatureCollection.serializer(), raw).features
        }.getOrElse { emptyList() }
    }

    private fun parseOffline(raw: String?): List<V1OfflineFeature> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            json.decodeFromString<List<V1OfflineFeature>>(raw)
        }.getOrElse { emptyList() }
    }

    @Serializable
    private data class V1FeatureCollection(
        val features: List<V1Feature> = emptyList(),
    )

    @Serializable
    private data class V1Feature(
        val geometry: V1Geometry = V1Geometry(),
        val properties: V1Properties = V1Properties(),
    ) {
        fun toRecord(pending: PendingChangeDto?): PlaceRecordDto? {
            val coords = geometry.coordinates
            if (coords.size < 2) return null
            val location = LonLat(longitude = coords[0], latitude = coords[1])
            if (!location.isValidGeographic()) return null
            val name = properties.name?.trim().orEmpty()
            if (name.isEmpty()) return null
            val serverId = properties.database_id
            val key = if (serverId != null) PlaceKey.server(serverId) else PlaceKey.local()
            return PlaceRecordDto(
                key = key.value,
                serverId = serverId,
                name = name,
                description = properties.description?.trim().orEmpty(),
                longitude = location.longitude,
                latitude = location.latitude,
                address = properties.address?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = properties.created_at,
                pending = pending,
            )
        }
    }

    @Serializable
    private data class V1Geometry(
        val coordinates: List<Double> = emptyList(),
    )

    @Serializable
    private data class V1Properties(
        val database_id: Int? = null,
        val name: String? = null,
        val description: String? = null,
        val created_at: String? = null,
        val address: String? = null,
    )

    @Serializable
    private data class V1OfflineFeature(
        val clientLocalId: String = "",
        val feature: V1Feature,
        val original: V1Feature? = null,
    ) {
        fun toRecord(): PlaceRecordDto? {
            val localId = clientLocalId.trim().ifBlank { UUID.randomUUID().toString() }
            val serverId = feature.properties.database_id
            val pending = if (serverId != null) {
                val baseline = original?.toRecord(pending = null)?.let { record ->
                    PlaceContentDto(
                        name = record.name,
                        description = record.description,
                        longitude = record.longitude,
                        latitude = record.latitude,
                        address = record.address,
                    )
                } ?: return feature.toRecord(pending = PendingChangeDto.Create)
                PendingChangeDto.Update(baseline)
            } else {
                PendingChangeDto.Create
            }
            val record = feature.toRecord(pending) ?: return null
            val key = if (serverId != null) PlaceKey.server(serverId) else PlaceKey.local(localId)
            return record.copy(key = key.value)
        }
    }
}

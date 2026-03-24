package com.geovault.tracker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun TrackerDto.toDomainModel(): Tracker {
    return Tracker(
        id = id,
        name = name,
        color = color,
        settings = settings?.toMapAny(),
        geometry = geometry?.let { GeoJsonLineString(type = it.type, coordinates = it.coordinates) },
        point_params = point_params?.map { it.toMapAny() },
        last_point = last_point,
        bbox = bbox,
        tracker_secret = tracker_secret,
        created_at = created_at,
        updated_at = updated_at,
        is_owner = is_owner,
        visibility = visibility,
        share_params_with_recipients = share_params_with_recipients,
        share_params_with_world = share_params_with_world,
        owner_email = owner_email,
        subscriber_count = subscriber_count,
        world_share_id = world_share_id,
        world_share_url = world_share_url,
        shared_with_emails = shared_with_emails
    )
}

fun List<TrackerDto>.toDomainModels(): List<Tracker> = map { it.toDomainModel() }

fun TrackerCoordinatesResponseDto.toDomainModel(): TrackerCoordinatesResponse {
    return TrackerCoordinatesResponse(
        coordinates = coordinates,
        point_params = point_params?.map { it.toMapAny() }
    )
}

private fun JsonObject.toMapAny(): Map<String, Any?> = entries.associate { (key, value) ->
    key to value.toAnyValue()
}

private fun JsonElement.toAnyValue(): Any? {
    return when (this) {
        JsonNull -> null
        is JsonObject -> toMapAny()
        is JsonArray -> map { it.toAnyValue() }
        is JsonPrimitive -> toPrimitiveValue()
    }
}

private fun JsonPrimitive.toPrimitiveValue(): Any? {
    if (isString) {
        return content
    }
    val raw = content
    when (raw.lowercase()) {
        "true" -> return true
        "false" -> return false
    }
    raw.toLongOrNull()?.let { return it }
    raw.toDoubleOrNull()?.let { return it }
    return raw
}

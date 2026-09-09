package com.geovault.tracker

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

fun TrackerDto.toDomainModel(): Tracker {
    return Tracker(
        id = id,
        name = name,
        color = color,
        settings = settings?.toCatalogSettings(),
        geometry = geometry?.let { GeoJsonLineString(type = it.type, coordinates = it.coordinates) },
        point_params = point_params,
        geometry_status = geometry_status?.toDomainModel(),
        last_point = last_point,
        bbox = bbox,
        tracker_secret = tracker_secret,
        created_at = created_at,
        subscribed_at = subscribed_at,
        updated_at = updated_at,
        is_owner = is_owner,
        visibility = visibility,
        share_params_with_recipients = share_params_with_recipients,
        share_params_with_world = share_params_with_world,
        owner_email = owner_email,
        subscriber_count = subscriber_count,
        internal_share_id = internal_share_id,
        internal_share_url = internal_share_url,
        world_share_id = world_share_id,
        world_share_url = world_share_url,
        shared_with_emails = shared_with_emails
    )
}

fun List<TrackerDto>.toDomainModels(): List<Tracker> = map { it.toDomainModel() }

fun TrackerCoordinatesResponseDto.toDomainModel(): TrackerCoordinatesResponse {
    return TrackerCoordinatesResponse(
        coordinates = coordinates,
        point_params = point_params
    )
}

private fun TrackerGeometryStatusDto.toDomainModel(): TrackerGeometryStatus {
    return TrackerGeometryStatus(
        window = window ?: "all",
        returned_count = returned_count ?: 0,
        total_filtered_count = total_filtered_count ?: returned_count ?: 0,
        is_truncated = is_truncated ?: false,
        params_align_with_coords = params_align_with_coords ?: true,
    )
}

internal fun JsonObject.toCatalogSettings(): TrackerCatalogSettings {
    return TrackerCatalogSettings(
        hidden = jsonBooleanLoose("hidden") == true,
        recentDataWindow = jsonString("recent_data_window"),
        allowGroupReshare = jsonBooleanStrict("allow_group_reshare"),
        color = jsonString("color"),
    )
}

private fun JsonObject.jsonString(key: String): String? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return element.asString.trim().ifBlank { null }
}

private fun JsonObject.jsonBooleanStrict(key: String): Boolean? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    val primitive = element.asJsonPrimitive
    return if (primitive.isBoolean) primitive.asBoolean else null
}

private fun JsonObject.jsonBooleanLoose(key: String): Boolean? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    val primitive = element.asJsonPrimitive
    if (primitive.isBoolean) return primitive.asBoolean
    if (primitive.isNumber) return primitive.asInt != 0
    if (primitive.isString) {
        return when (primitive.asString.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }
    return null
}

internal fun JsonObject.toLooseMap(): Map<String, Any?> = entrySet().associate { (key, value) ->
    key to value.toAnyValue()
}

fun pointParamsOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val json = JsonObject()
    for ((key, value) in pairs) {
        when (value) {
            null -> json.add(key, JsonNull.INSTANCE)
            is Boolean -> json.addProperty(key, value)
            is Number -> json.addProperty(key, value)
            is String -> json.addProperty(key, value)
            else -> json.addProperty(key, value.toString())
        }
    }
    return json
}

private fun JsonElement.toAnyValue(): Any? {
    return when (this) {
        is JsonNull -> null
        is JsonObject -> toLooseMap()
        is JsonArray -> map { it.toAnyValue() }
        is JsonPrimitive -> toPrimitiveValue()
        else -> null
    }
}

private fun JsonPrimitive.toPrimitiveValue(): Any? {
    if (isString) {
        return asString
    }
    if (isBoolean) {
        return asBoolean
    }
    val raw = asString
    when (raw.lowercase()) {
        "true" -> return true
        "false" -> return false
    }
    raw.toLongOrNull()?.let { return it }
    raw.toDoubleOrNull()?.let { return it }
    return raw
}

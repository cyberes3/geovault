package com.geovault.common.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Paginated list body. Lists are never a bare array.
 */
data class ListEnvelope<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int,
) {
    companion object {
        fun parse(body: String?): ListEnvelope<JSONObject>? {
            val trimmed = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val json = JSONObject(trimmed)
                val items = json.optJSONArray("items") ?: return null
                if (!json.has("page") || !json.has("page_size")) {
                    return null
                }
                ListEnvelope(
                    items = items.toObjectList(),
                    page = json.getInt("page"),
                    pageSize = json.getInt("page_size"),
                    totalItems = json.optInt("total_items", items.length()),
                    totalPages = json.optInt("total_pages", 0),
                )
            }.getOrNull()
        }

        private fun JSONArray.toObjectList(): List<JSONObject> {
            return (0 until length()).mapNotNull { index -> optJSONObject(index) }
        }
    }
}

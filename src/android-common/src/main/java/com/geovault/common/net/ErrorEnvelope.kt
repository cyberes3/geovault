package com.geovault.common.net

import org.json.JSONObject

/**
 * Server error body. The only text field is [error].
 */
data class ErrorEnvelope(
    val error: String,
    val code: Int,
    val details: JSONObject? = null,
) {
    companion object {
        fun parse(body: String?): ErrorEnvelope? {
            val trimmed = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val json = JSONObject(trimmed)
                val error = json.optString("error").trim()
                if (error.isEmpty() || !json.has("code")) {
                    null
                } else {
                    ErrorEnvelope(
                        error = error,
                        code = json.getInt("code"),
                        details = json.optJSONObject("details"),
                    )
                }
            }.getOrNull()
        }
    }
}

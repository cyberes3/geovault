package com.geovault.tracker.presentation

data class TrackerMapSessionWindowDecision(
    val shouldResetTrackGeometry: Boolean,
    val shouldIgnorePoint: Boolean,
    val nextSessionStartMs: Long?
)

object TrackerMapSessionWindowPolicy {
    private val SESSION_WINDOWS = setOf("session", "current_session")
    private val STARTTIMESTAMP_REGEX = Regex(
        pattern = "\"starttimestamp\"\\s*:\\s*(\"?[0-9]+\"?)",
        option = RegexOption.IGNORE_CASE
    )

    fun decide(
        recentDataWindow: String?,
        currentSessionStartMs: Long?,
        incomingPropsJson: String?,
        allowResetOnNewSession: Boolean = true
    ): TrackerMapSessionWindowDecision {
        val normalizedWindow = recentDataWindow?.trim()?.lowercase()
        if (normalizedWindow !in SESSION_WINDOWS) {
            return TrackerMapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = currentSessionStartMs
            )
        }
        val incomingSessionStartMs = parseSessionStartFromPropsJson(incomingPropsJson)
            ?: return TrackerMapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = currentSessionStartMs
            )
        if (currentSessionStartMs == null) {
            return TrackerMapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = incomingSessionStartMs
            )
        }
        if (incomingSessionStartMs > currentSessionStartMs) {
            return TrackerMapSessionWindowDecision(
                shouldResetTrackGeometry = allowResetOnNewSession,
                shouldIgnorePoint = false,
                nextSessionStartMs = incomingSessionStartMs
            )
        }
        if (incomingSessionStartMs < currentSessionStartMs) {
            return TrackerMapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = true,
                nextSessionStartMs = currentSessionStartMs
            )
        }
        return TrackerMapSessionWindowDecision(
            shouldResetTrackGeometry = false,
            shouldIgnorePoint = false,
            nextSessionStartMs = currentSessionStartMs
        )
    }

    private fun parseSessionStartFromPropsJson(propsJson: String?): Long? {
        if (propsJson.isNullOrBlank()) return null
        val match = STARTTIMESTAMP_REGEX.find(propsJson) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim()?.trim('"') ?: return null
        return normalizeTimestampToMs(raw)
    }

    fun resolveLatestSessionStartMs(pointParams: List<Map<String, Any?>>?): Long? {
        if (pointParams.isNullOrEmpty()) return null
        var latest: Long? = null
        pointParams.forEach { params ->
            val value = normalizeTimestampToMs(params["starttimestamp"]) ?: return@forEach
            val currentLatest = latest
            if (currentLatest == null || value > currentLatest) {
                latest = value
            }
        }
        return latest
    }

    fun normalizeTimestampToMs(value: Any?): Long? {
        val raw = when (value) {
            null -> return null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: return null
            else -> return null
        }
        return if (raw in 1L..999_999_999_999L) raw * 1000L else raw
    }
}

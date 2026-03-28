package com.geovault.tracker.fragments.map

internal data class MapSessionWindowDecision(
    val shouldResetTrackGeometry: Boolean,
    val shouldIgnorePoint: Boolean,
    val nextSessionStartMs: Long?
)

internal object MapSessionWindowPolicy {
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
    ): MapSessionWindowDecision {
        val normalizedWindow = recentDataWindow?.trim()
        if (normalizedWindow !in SESSION_WINDOWS) {
            return MapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = currentSessionStartMs
            )
        }
        val incomingSessionStartMs = parseSessionStartFromPropsJson(incomingPropsJson)
            ?: return MapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = currentSessionStartMs
            )
        if (currentSessionStartMs == null) {
            return MapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = false,
                nextSessionStartMs = incomingSessionStartMs
            )
        }
        if (incomingSessionStartMs > currentSessionStartMs) {
            return MapSessionWindowDecision(
                shouldResetTrackGeometry = allowResetOnNewSession,
                shouldIgnorePoint = false,
                nextSessionStartMs = incomingSessionStartMs
            )
        }
        if (incomingSessionStartMs < currentSessionStartMs) {
            return MapSessionWindowDecision(
                shouldResetTrackGeometry = false,
                shouldIgnorePoint = true,
                nextSessionStartMs = currentSessionStartMs
            )
        }
        return MapSessionWindowDecision(
            shouldResetTrackGeometry = false,
            shouldIgnorePoint = false,
            nextSessionStartMs = currentSessionStartMs
        )
    }

    fun resolveLatestSessionStartMs(pointParams: List<Map<String, Any?>>?): Long? {
        if (pointParams.isNullOrEmpty()) return null
        var latest: Long? = null
        for (params in pointParams) {
            val normalized = normalizeTimestampToMs(params["starttimestamp"]) ?: continue
            if (latest == null || normalized > latest) {
                latest = normalized
            }
        }
        return latest
    }

    private fun parseSessionStartFromPropsJson(propsJson: String?): Long? {
        if (propsJson.isNullOrBlank()) return null
        val match = STARTTIMESTAMP_REGEX.find(propsJson) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim()?.trim('"') ?: return null
        return normalizeTimestampToMs(raw)
    }

    private fun normalizeTimestampToMs(value: Any?): Long? {
        val raw = when (value) {
            null -> return null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: return null
            else -> return null
        }
        return if (raw in 1L..999_999_999_999L) raw * 1000L else raw
    }
}

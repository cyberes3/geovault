package com.geovault.tracker.presentation

import com.geovault.tracker.policy.WireTimestampNormalizer

/**
 * Extract the session `starttimestamp` from a wire-format point props JSON
 * blob (the same payload `TrackingService` serializes via
 * `buildLocalPointPropsJson` and the server forwards on stream updates).
 * Returns null when the field is absent or cannot be normalized.
 *
 * Implemented with a regex instead of `org.json.JSONObject` so the parser is
 * usable from pure-JVM unit tests (Android's JSON stub raises `Stub!` outside
 * Robolectric/instrumentation contexts).
 */
object TrackerMapPointStartTimestampParser {
    // Matches the value of a top-level `starttimestamp` field. Allows whitespace and an
    // optional quoted value. Treats props JSON as a flat object (matching the producer in
    // `TrackingService.buildLocalPointPropsJson`).
    private val START_TS_PATTERN = Regex(
        pattern = """"starttimestamp"\s*:\s*"?(-?\d+)"?""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parse(propsJson: String?): Long? {
        val raw = propsJson?.takeIf { it.isNotBlank() } ?: return null
        val match = START_TS_PATTERN.find(raw) ?: return null
        return WireTimestampNormalizer.normalizeToMilliseconds(match.groupValues[1])
    }
}

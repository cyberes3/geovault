package com.geovault.tracker.presentation

import com.geovault.tracker.policy.WireTimestampNormalizer

object TrackerMapSessionWindowPolicy {
    fun normalizeTimestampToMs(value: Any?): Long? {
        return WireTimestampNormalizer.normalizeToMilliseconds(value)
    }
}

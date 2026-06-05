package com.geovault.tracker.sensor

/**
 * Marker singleton returned by [ActivityHintSource.currentHint] when a moving-activity
 * transition is active and not expired. Non-null = motion detected; null = no hint,
 * expired, permission missing, or GMS unavailable.
 */
internal object ActivityHint

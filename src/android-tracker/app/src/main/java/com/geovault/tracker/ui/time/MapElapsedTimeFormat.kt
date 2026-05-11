package com.geovault.tracker.ui.time

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.geovault.common.util.ElapsedTime
import com.geovault.tracker.R

/**
 * Localized elapsed-time projection used by the map info box and the map's top-left chip
 * (e.g. `Just updated`, `Updated 25 sec ago`, `Updated 1 min ago`). A null timestamp
 * renders as the shared "Waiting for data" placeholder, which is the correct UI when the
 * tracker has never reported.
 */
@Composable
fun mapElapsedAgoText(lastReportedAtMs: Long?, nowMs: Long): String {
    if (lastReportedAtMs == null) return stringResource(R.string.waiting_for_data)
    return when (val bucket = ElapsedTime.from(nowMs - lastReportedAtMs)) {
        ElapsedTime.Now -> stringResource(R.string.map_updated_just_now)
        is ElapsedTime.Seconds -> stringResource(
            R.string.map_updated_ago,
            bucket.value,
            stringResource(R.string.map_updated_secs),
        )
        is ElapsedTime.Minutes -> stringResource(
            R.string.map_updated_ago,
            bucket.value,
            stringResource(R.string.map_updated_mins),
        )
        is ElapsedTime.Hours -> stringResource(
            R.string.map_updated_ago,
            bucket.value,
            stringResource(R.string.map_updated_hrs),
        )
        is ElapsedTime.Days -> stringResource(
            R.string.map_updated_ago,
            bucket.value,
            stringResource(if (bucket.value == 1) R.string.map_updated_day_short else R.string.map_updated_days_short),
        )
    }
}

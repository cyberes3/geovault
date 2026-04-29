package com.geovault.tracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.geovault.tracker.R
import kotlinx.coroutines.delay

@Composable
fun MapFormatLastUpdatedTextOrWaiting(lastUpdatedMs: Long?): String {
    if (lastUpdatedMs == null) {
        return stringResource(R.string.waiting_for_data)
    }
    return MapFormatLastUpdatedText(lastDataEpochMs = lastUpdatedMs)
}

/**
 * Relative "N min ago" / "N days ago" text, matching the map selection card; recomposes on
 * an interval so the label advances while the chip is visible.
 */
@Composable
fun MapFormatLastUpdatedText(lastDataEpochMs: Long): String {
    var tick by remember(lastDataEpochMs) { mutableStateOf(0) }
    LaunchedEffect(lastDataEpochMs) {
        while (true) {
            delay(20_000L)
            tick += 1
        }
    }
    return formatRelativeAgoString(lastDataEpochMs + (tick and 0))
}

@Composable
private fun formatRelativeAgoString(lastUpdatedMs: Long): String {
    val diffMs = System.currentTimeMillis() - lastUpdatedMs
    val diffSec = (diffMs / 1000).coerceAtLeast(0)
    val (value, unit) = when {
        diffSec < 60 -> {
            val n = diffSec.toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_sec)
            } else {
                stringResource(R.string.map_updated_secs)
            }
            n to unit
        }
        diffSec < 3600 -> {
            val n = (diffSec / 60).toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_min)
            } else {
                stringResource(R.string.map_updated_mins)
            }
            n to unit
        }
        diffSec < 86400 -> {
            val n = (diffSec / 3600).toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_hr)
            } else {
                stringResource(R.string.map_updated_hrs)
            }
            n to unit
        }
        else -> {
            val n = (diffSec / 86400).toInt()
            val unit = if (n == 1) {
                stringResource(R.string.map_updated_day_short)
            } else {
                stringResource(R.string.map_updated_days_short)
            }
            n to unit
        }
    }
    return stringResource(R.string.map_updated_ago, value, unit)
}

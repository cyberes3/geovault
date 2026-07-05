package com.geovault.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R

/**
 * Surfaces [com.geovault.tracker.presentation.StreamingBatteryOptimizationHintPolicy]'s decision
 * as an actionable, dismissible banner. Without this, an OEM background-killing the streaming
 * socket only shows up as `stream_diag_heartbeat`/`stream_diag_watchdog_reconnect` capture-log
 * breadcrumbs -- informative for us, invisible and unactionable for the user actually
 * experiencing "streaming keeps dropping."
 *
 * Dismissal is intentionally local/session-scoped (mirrors [HomeScreen]'s `imuSnackbarDismissed`)
 * rather than written back into [com.geovault.tracker.presentation.TrackerMapUiState]: the
 * underlying condition can persist for as long as the OEM setting is untouched, so a
 * ViewModel-level dismiss flag would either need its own separate expiry logic or would
 * permanently silence a real, ongoing problem for the rest of the session. Re-opening the map
 * (recomposing this from scratch) is an acceptable way to see the hint again.
 */
@Composable
fun MapBatteryOptimizationHint(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) dismissed = false
    }
    if (!visible || dismissed) return

    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = GeoVaultColorTokens.MainYellow.copy(alpha = 0.95f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.map_battery_optimization_hint_message),
            style = MaterialTheme.typography.caption,
            color = GeoVaultColorTokens.Gray900,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GeoVaultInlineActionButton(
                text = stringResource(R.string.map_battery_optimization_hint_dismiss),
                enabled = true,
                onClick = { dismissed = true },
            )
            GeoVaultInlineActionButton(
                text = stringResource(R.string.map_battery_optimization_hint_action),
                enabled = true,
                onClick = {
                    dismissed = true
                    TrackerSystemSettingsIntents.openBatteryOptimizationSettings(context)
                },
            )
        }
    }
}

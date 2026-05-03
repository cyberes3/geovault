package com.geovault.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultPulseRing
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R

@Composable
fun MapGpsAccuracyIndicator(
    modifier: Modifier = Modifier,
) {
    GeoVaultPulseRing(
        color = GeoVaultColorTokens.Error,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colors.background.copy(alpha = 0.85f),
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = GeoVaultColorTokens.Error.copy(alpha = 0.45f),
                    shape = CircleShape,
                )
                .padding(10.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_satellite_warning),
                contentDescription = stringResource(R.string.map_gps_accuracy_warning_content_description),
                tint = GeoVaultColorTokens.Error,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

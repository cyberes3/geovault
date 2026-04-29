package com.geovault.tracker.ui

import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.ui.components.GeoVaultClickableWithTooltip
import com.geovault.common.ui.components.GeoVaultInstallLongPressTooltip
import com.geovault.common.ui.components.trackGeoVaultTooltipBounds
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.policy.ActiveButDeadTrackerPolicy
import com.geovault.tracker.presentation.TrackerMapTopLeftChipText
import com.geovault.tracker.presentation.TrackerMapTopLeftChipUiModel
import kotlinx.coroutines.delay

@Composable
fun MapTopLeftTrackerChip(
    model: TrackerMapTopLeftChipUiModel.Visible,
    onCardClick: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardDescription = stringResource(model.cardContentDescriptionResId)
    val cardTooltip = stringResource(R.string.tooltip_map_tracker_label_card)
    val resetTooltip = stringResource(R.string.tooltip_map_reset_tracker)
    val chipShape: Shape = RoundedCornerShape(22.dp)
    val cardInteractionSource = remember { MutableInteractionSource() }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    val suppressCardClickAfterTooltip = remember { mutableStateOf(false) }
    GeoVaultInstallLongPressTooltip(
        tooltipText = cardTooltip,
        enabled = true,
        interactionSource = cardInteractionSource,
        anchorBounds = cardBounds,
        suppressNextClickAfterTooltip = suppressCardClickAfterTooltip,
    )
    Card(
        modifier = modifier
            .trackGeoVaultTooltipBounds(cardInteractionSource) { cardBounds = it }
            .semantics(mergeDescendants = true) {
                contentDescription = cardDescription
            }
            .clickable(
                interactionSource = cardInteractionSource,
                indication = null,
                onClick = {
                    if (suppressCardClickAfterTooltip.value) {
                        suppressCardClickAfterTooltip.value = false
                    } else {
                        onCardClick()
                    }
                },
            ),
        shape = chipShape,
        elevation = 0.dp,
        backgroundColor = GeoVaultColorTokens.MainBlue,
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            val maxTitleWidth = ((maxWidth * 0.66f) - 90.dp).coerceAtLeast(72.dp)
            val titleLineHeight = 15.sp
            val subtitleLineHeight = 12.sp
            val subtitleTopSpacing = 2.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (model.showReset) 6.dp else 8.dp
                ),
            ) {
                Icon(
                    painter = painterResource(model.iconResId),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .widthIn(max = maxTitleWidth)
                        .padding(end = if (model.showReset) 0.dp else 4.dp),
                ) {
                    Text(
                        text = model.title.resolveTitle(),
                        color = MaterialTheme.colors.onPrimary,
                        fontSize = 14.sp,
                        lineHeight = titleLineHeight,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    model.subtitle?.let { subtitle ->
                        when (subtitle) {
                            is TrackerMapTopLeftChipText.RelativeLastData -> {
                                var staleEvalTick by remember(subtitle.lastDataEpochMs) {
                                    mutableStateOf(0)
                                }
                                LaunchedEffect(subtitle.lastDataEpochMs) {
                                    while (true) {
                                        delay(20_000L)
                                        staleEvalTick++
                                    }
                                }
                                val nowMs = System.currentTimeMillis() + (staleEvalTick and 0)
                                val warnStale = ActiveButDeadTrackerPolicy.isActiveButDead(
                                    nowMs = nowMs,
                                    updatedAtMs = subtitle.serverMetadataUpdatedAtMs,
                                    lastDataMs = subtitle.lastDataEpochMs,
                                )
                                val subtitleColor = if (warnStale) {
                                    GeoVaultColorTokens.Error
                                } else {
                                    MaterialTheme.colors.onPrimary
                                }
                                Text(
                                    text = MapFormatLastUpdatedText(subtitle.lastDataEpochMs),
                                    color = subtitleColor,
                                    fontSize = 12.sp,
                                    lineHeight = subtitleLineHeight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = subtitleTopSpacing),
                                )
                            }
                            is TrackerMapTopLeftChipText.Resource -> {
                                Text(
                                    text = stringResource(subtitle.resId),
                                    color = MaterialTheme.colors.onPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = subtitleLineHeight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = subtitleTopSpacing),
                                )
                            }
                            is TrackerMapTopLeftChipText.Value -> {
                                Text(
                                    text = subtitle.value,
                                    color = MaterialTheme.colors.onPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = subtitleLineHeight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = subtitleTopSpacing),
                                )
                            }
                        }
                    }
                }
                if (model.showReset) {
                    GeoVaultClickableWithTooltip(
                        onClick = onResetClick,
                        modifier = Modifier
                            .size(28.dp)
                            .padding(4.dp),
                        tooltip = resetTooltip,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(model.resetContentDescriptionResId),
                            tint = MaterialTheme.colors.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerMapTopLeftChipText.resolveTitle(): String {
    return when (this) {
        is TrackerMapTopLeftChipText.Resource -> stringResource(resId)
        is TrackerMapTopLeftChipText.Value -> value
        is TrackerMapTopLeftChipText.RelativeLastData -> ""
    }
}

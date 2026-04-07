package com.geovault.tracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.ui.components.GeoVaultCompactDismissTitleBar
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.R
import com.geovault.tracker.params.TrackerParamGridRow
import com.geovault.tracker.params.TrackerParamsBodyKind
import com.geovault.tracker.params.TrackerParamsRouteArgs
import com.geovault.tracker.presentation.TrackerParamsScreenUiState
import com.geovault.tracker.presentation.TrackerParamsViewModel

/**
 * Layout matches [fragment_tracker_params](src/android-tracker/app/src/main/res/layout/fragment_tracker_params.xml)
 * and [item_param_card](src/android-tracker/app/src/main/res/layout/item_param_card.xml).
 */
@Composable
fun TrackerParamsScreen(
    args: TrackerParamsRouteArgs,
    onDismiss: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val viewModel: TrackerParamsViewModel = viewModel(
        key = args.trackerId,
        factory = TrackerParamsViewModel.factory(application, args),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.surface),
    ) {
        GeoVaultCompactDismissTitleBar(
            title = stringResource(R.string.latest_params_title),
            onClose = onDismiss,
            closeContentDescription = stringResource(R.string.close),
        )
        GeoVaultPullRefreshLoadingContainer(
            refreshing = state.isRefreshing,
            showBlockingLoader = state.showBlockingLoader,
            onRefresh = { viewModel.loadTrackerData(refresh = true) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .fillMaxHeight(),
            loadingText = stringResource(R.string.loading_params),
            pullRefreshEnabled = true,
        ) {
            TrackerParamsScrollContent(
                state = state,
                onRetry = { viewModel.loadTrackerData(refresh = false) },
            )
        }
    }
}

@Composable
private fun TrackerParamsScrollContent(
    state: TrackerParamsScreenUiState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
    ) {
        val title = state.trackerTitle
        Text(
            text = title.orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .padding(bottom = 12.dp),
            color = MaterialTheme.colors.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )

        ParamsInfoCard(
            headerLabel = stringResource(R.string.last_update_label),
            value = state.lastUpdateText,
        )

        ParamsInfoCard(
            headerLabel = stringResource(R.string.position_label),
            value = state.positionText,
        )

        val err = state.errorMessage
        if (err != null) {
            ParamsMessageCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = err,
                        color = GeoVaultColorTokens.Error,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GeoVaultSecondaryButton(
                        text = stringResource(R.string.tracker_params_retry),
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        when (state.bodyKind) {
            TrackerParamsBodyKind.ShowingGrid -> {
                val chunks = remember(state.gridRows) { state.gridRows.chunked(2) }
                chunks.forEach { chunk ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        chunk.forEach { row ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(4.dp),
                            ) {
                                ParamGridItemCard(row = row)
                            }
                        }
                        if (chunk.size == 1) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }
            TrackerParamsBodyKind.NoExtendedParams -> {
                ParamsMessageCard {
                    Text(
                        text = stringResource(R.string.no_extended_params_latest_point),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colors.onSurface,
                        fontSize = 14.sp,
                    )
                }
            }
            TrackerParamsBodyKind.WaitingForData -> {
                ParamsMessageCard {
                    Text(
                        text = stringResource(R.string.waiting_for_data),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colors.onSurface,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        val motion = state.motionModeText
        if (motion != null) {
            Text(
                text = motion,
                modifier = Modifier.padding(top = 24.dp),
                color = GeoVaultColorTokens.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TrackerParamsStrokeCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        border = BorderStroke(1.dp, GeoVaultColorTokens.PrimaryBlue),
        backgroundColor = MaterialTheme.colors.surface,
        content = content,
    )
}

@Composable
private fun ParamsInfoCard(
    headerLabel: String,
    value: String,
) {
    TrackerParamsStrokeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = headerLabel,
                color = MaterialTheme.colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colors.onSurface,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun ParamsMessageCard(content: @Composable () -> Unit) {
    TrackerParamsStrokeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Box { content() }
    }
}

@Composable
private fun ParamGridItemCard(row: TrackerParamGridRow) {
    TrackerParamsStrokeCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = row.label,
                color = MaterialTheme.colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.value,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colors.onSurface,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

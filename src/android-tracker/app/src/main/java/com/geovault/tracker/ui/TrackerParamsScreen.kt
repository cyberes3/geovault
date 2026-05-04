package com.geovault.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geovault.common.ui.components.GeoVaultOutlinedInfoCard
import com.geovault.common.ui.components.GeoVaultOutlinedInfoCardOptions
import com.geovault.common.ui.components.GeoVaultOutlinedStrokeCard
import com.geovault.common.ui.components.GeoVaultPullRefreshLoadingContainer
import com.geovault.common.ui.components.GeoVaultSecondaryButton
import com.geovault.common.ui.components.GeoVaultSubViewScaffold
import com.geovault.common.ui.navigation.GeoVaultRegisterBackHandler
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.tracker.R
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

    DisposableEffect(args.trackerId, viewModel) {
        viewModel.onScreenStarted()
        onDispose {
            viewModel.onScreenStopped()
        }
    }

    GeoVaultRegisterBackHandler(
        priority = TrackerBackPriorities.FULL_SCREEN_OVERLAY,
        onBack = {
            onDismiss()
            true
        },
    )

    GeoVaultSubViewScaffold(
        title = stringResource(R.string.latest_params_title),
        onClose = onDismiss,
        onLeaveComposition = onDismiss,
        closeContentDescription = stringResource(R.string.close),
    ) { innerPadding ->
        GeoVaultPullRefreshLoadingContainer(
            refreshing = state.isRefreshing,
            showBlockingLoader = state.showBlockingLoader,
            onRefresh = { viewModel.loadTrackerData(refresh = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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

        GeoVaultOutlinedInfoCard(
            label = stringResource(R.string.last_update_label),
            value = state.lastUpdateText,
            modifier = Modifier.padding(bottom = 8.dp),
            options = COPY_ON_TAP,
        )

        GeoVaultOutlinedInfoCard(
            label = stringResource(R.string.position_label),
            value = state.positionText,
            modifier = Modifier.padding(bottom = 8.dp),
            options = COPY_ON_TAP,
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
                                GeoVaultOutlinedInfoCard(
                                    label = row.label,
                                    value = row.value,
                                    options = GRID_CELL_OPTIONS,
                                )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                color = geoVaultContentSecondaryColor(),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ParamsMessageCard(content: @Composable () -> Unit) {
    GeoVaultOutlinedStrokeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Box { content() }
    }
}

private val COPY_ON_TAP = GeoVaultOutlinedInfoCardOptions(copyOnTap = true)

/**
 * Compact options for the two-column extended-params grid: copy-on-tap plus tight
 * line caps so the cells don't grow unboundedly when a label or value is long.
 */
private val GRID_CELL_OPTIONS = GeoVaultOutlinedInfoCardOptions(
    copyOnTap = true,
    labelMaxLines = 1,
    valueMaxLines = 3,
)

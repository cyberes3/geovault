package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Shared pull-to-refresh + blocking loading surface used across tracker screens.
 *
 * - [refreshing] drives pull-refresh state.
 * - [showBlockingLoader] overlays a full-page loading spinner and hides the top indicator.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun GeoVaultPullRefreshLoadingContainer(
    refreshing: Boolean,
    showBlockingLoader: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    loadingText: String? = null,
    pullRefreshEnabled: Boolean = true,
    showPullRefreshIndicator: Boolean = true,
    canRefresh: Boolean = !refreshing,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            if (canRefresh) onRefresh()
        },
    )
    val containerModifier = if (pullRefreshEnabled) {
        modifier.pullRefresh(pullRefreshState)
    } else {
        modifier
    }
    Box(modifier = containerModifier) {
        content()
        if (showBlockingLoader) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                GeoVaultLoadingSpinner(bottomText = loadingText)
            }
        } else if (showPullRefreshIndicator && (refreshing || pullRefreshState.progress > 0f)) {
            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

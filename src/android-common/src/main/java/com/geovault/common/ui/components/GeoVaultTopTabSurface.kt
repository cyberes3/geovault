package com.geovault.common.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.material.Divider
import androidx.compose.material.Scaffold
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier

/**
 * Canonical rendered surface for top-tab screens.
 *
 * Owns shared structure and behavior:
 * - optional dismiss chrome (compact title + close) for tabbed sub-views
 * - standardized top tab bar
 * - optional header region (for search/filters/actions)
 * - pull-to-refresh + blocking loading body
 * - optional bottom region
 *
 * Tabbed sub-views that need a dismiss affordance pass [dismissTitle] and [onDismiss] to
 * render the shared compact dismiss bar above the tab row; this composes the same
 * [GeoVaultCompactDismissTitleBar] used by [GeoVaultSubViewScaffold], keeping one source of
 * truth for sub-view chrome.
 *
 * When dismiss chrome is shown, [onDismissLeaveComposition] mirrors the `onLeaveComposition`
 * behavior on [GeoVaultSubViewScaffold]: pass `null` if this surface is swapped for another
 * in-tab mode so composition leave must not dismiss.
 */
enum class GeoVaultTopTabSwipeMode {
    ALWAYS,
    NEVER,
    WHILE_NOT_BLOCKING,
}

data class GeoVaultTopTabBehavior<T>(
    val swipeMode: GeoVaultTopTabSwipeMode = GeoVaultTopTabSwipeMode.ALWAYS,
    val isTabRefreshing: (T) -> Boolean = { false },
    val isTabBlocking: (T) -> Boolean = { false },
    val canRefreshTab: (T) -> Boolean = { true },
    val isPullRefreshEnabled: (T) -> Boolean = { true },
    val loadingTextForTab: (T) -> String? = { null },
    val onRefreshTab: (T) -> Unit = {},
)

@Composable
fun <T> GeoVaultTopTabSurface(
    tabs: List<GeoVaultTab<T>>,
    selectedTab: T,
    onTabSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    animateTabChanges: Boolean = true,
    behavior: GeoVaultTopTabBehavior<T> = GeoVaultTopTabBehavior(),
    dismissTitle: String? = null,
    onDismiss: (() -> Unit)? = null,
    onDismissLeaveComposition: (() -> Unit)? = null,
    dismissContentDescription: String = "Close",
    headerForTab: (@Composable ColumnScope.(T) -> Unit)? = null,
    bottomForTab: (@Composable (T) -> Unit)? = null,
    contentForTab: @Composable BoxScope.(T) -> Unit,
) {
    if (tabs.isEmpty()) return

    val dismissLeave = onDismissLeaveComposition
    if (dismissTitle != null && onDismiss != null && dismissLeave != null) {
        DisposableEffect(dismissLeave) {
            onDispose { dismissLeave() }
        }
    }

    val selectedIndex = tabs.indexOfFirst { it.value == selectedTab }.let { if (it >= 0) it else 0 }
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        pageCount = { tabs.size },
    )
    LaunchedEffect(selectedIndex, tabs) {
        if (pagerState.currentPage != selectedIndex && selectedIndex in tabs.indices) {
            if (animateTabChanges) {
                pagerState.animateScrollToPage(selectedIndex)
            } else {
                pagerState.scrollToPage(selectedIndex)
            }
        }
    }
    LaunchedEffect(pagerState.settledPage, tabs) {
        val nextTab = tabs.getOrNull(pagerState.settledPage)?.value ?: return@LaunchedEffect
        if (nextTab != selectedTab) {
            onTabSelected(nextTab)
        }
    }
    val activeTab = tabs.getOrNull(pagerState.settledPage)?.value ?: selectedTab
    val isBlocking = behavior.isTabBlocking(activeTab)
    val isRefreshing = behavior.isTabRefreshing(activeTab)
    val canRefresh = behavior.canRefreshTab(activeTab)
    val pullRefreshEnabled = behavior.isPullRefreshEnabled(activeTab)
    val loadingText = behavior.loadingTextForTab(activeTab)
    val canSwipe = when (behavior.swipeMode) {
        GeoVaultTopTabSwipeMode.ALWAYS -> true
        GeoVaultTopTabSwipeMode.NEVER -> false
        GeoVaultTopTabSwipeMode.WHILE_NOT_BLOCKING -> !isBlocking
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (dismissTitle != null && onDismiss != null) {
                    GeoVaultCompactDismissTitleBar(
                        title = dismissTitle,
                        onClose = onDismiss,
                        closeContentDescription = dismissContentDescription,
                    )
                    Divider()
                }
                GeoVaultTabBar(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    indicatorPage = pagerState.currentPage,
                    indicatorOffsetFraction = pagerState.currentPageOffsetFraction,
                )
                headerForTab?.invoke(this, activeTab)
            }
        },
        bottomBar = {
            bottomForTab?.invoke(activeTab)
        },
    ) { innerPadding ->
        GeoVaultPullRefreshLoadingContainer(
            refreshing = isRefreshing,
            // For tabbed surfaces, render blocking loaders per-page so loader state slides
            // with pager motion instead of appearing as a fixed overlay that visually snaps.
            showBlockingLoader = false,
            onRefresh = { behavior.onRefreshTab(activeTab) },
            pullRefreshEnabled = pullRefreshEnabled,
            showPullRefreshIndicator = !isBlocking,
            canRefresh = canRefresh,
            loadingText = loadingText,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            content = {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = canSwipe,
                ) { page ->
                    val tab = tabs.getOrNull(page) ?: return@HorizontalPager
                    val tabValue = tab.value
                    val pageIsBlocking = behavior.isTabBlocking(tabValue)
                    Box(modifier = Modifier.fillMaxSize()) {
                        contentForTab(tabValue)
                        if (pageIsBlocking) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                GeoVaultLoadingSpinner(
                                    bottomText = behavior.loadingTextForTab(tabValue),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

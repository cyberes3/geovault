package com.geovault.common.maps.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Reusable "map surface + bottom drawer" scaffold shared by every GeoVault app.
 *
 * The scaffold is deliberately slot-based and domain-agnostic: it knows nothing about survey
 * points, trackers, or uploaded files — feature code provides the map, the header content,
 * and the drawer body, and the scaffold handles the drag physics, shape/shadow chrome, and
 * the bookkeeping consumers need to synchronize map-camera padding with drawer visibility.
 *
 * ### Layout contract
 *
 * - The map fills the entire container (it sits *under* the drawer, not *beside* it).
 * - The drawer is positioned by an offset driven by [drawerState] and clipped with the
 *   scaffold's rounded-top shape. Consumers should not apply their own offset/shape modifiers
 *   to the drawer body — the scaffold owns them.
 * - Overlay slots ([topStart], [topEnd], [bottomStart], [bottomEnd]) render above the map and
 *   below the drawer. FAB columns, chips, and banners live here.
 *
 * ### Camera-padding contract
 *
 * Consumers read [GeoVaultMapDrawerState.visibleHeightPx] and feed it into their
 * [com.geovault.common.maps.core.GeoVaultMapPaddingPolicy] / `mapPaddingDp.bottom`. The
 * scaffold *never* mutates the map directly — one-way data flow keeps MapLibre state
 * recoverable when the drawer re-anchors unexpectedly (config change, theme switch, etc.).
 */
@Composable
fun GeoVaultMapScaffold(
    modifier: Modifier = Modifier,
    drawerState: GeoVaultMapDrawerState = rememberGeoVaultMapDrawerState(),
    drawerHeader: @Composable GeoVaultMapDrawerHeaderScope.() -> Unit,
    drawerBody: @Composable ColumnScope.() -> Unit,
    topStart: (@Composable BoxScope.() -> Unit)? = null,
    topEnd: (@Composable BoxScope.() -> Unit)? = null,
    bottomStart: (@Composable BoxScope.() -> Unit)? = null,
    bottomEnd: (@Composable BoxScope.() -> Unit)? = null,
    mapContent: @Composable BoxScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val nestedScrollConnection = remember(drawerState) {
        drawerNestedScrollConnection(
            state = drawerState.anchoredDraggableState,
            onFling = { velocity ->
                coroutineScope.settleDrawerOnFling(drawerState.anchoredDraggableState, velocity)
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> drawerState.updateAnchors(size.height) },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            mapContent()
        }

        if (topStart != null) {
            Box(modifier = Modifier.align(Alignment.TopStart), content = topStart)
        }
        if (topEnd != null) {
            Box(modifier = Modifier.align(Alignment.TopEnd), content = topEnd)
        }

        DrawerLayer(
            drawerState = drawerState,
            nestedScrollModifier = Modifier.nestedScroll(nestedScrollConnection),
            drawerHeader = drawerHeader,
            drawerBody = drawerBody,
        )

        if (bottomStart != null) {
            Box(modifier = Modifier.align(Alignment.BottomStart), content = bottomStart)
        }
        if (bottomEnd != null) {
            Box(modifier = Modifier.align(Alignment.BottomEnd), content = bottomEnd)
        }
    }

    LaunchedEffect(drawerState) {
        // Ensure the drawer lands on its current target once anchors have been populated by
        // the first onSizeChanged pass. This guarantees animateTo(...) from feature code does
        // not race the initial layout.
        val target = drawerState.anchoredDraggableState.targetValue
        drawerState.snapTo(target)
    }
}

@Composable
private fun BoxScope.DrawerLayer(
    drawerState: GeoVaultMapDrawerState,
    nestedScrollModifier: Modifier,
    drawerHeader: @Composable GeoVaultMapDrawerHeaderScope.() -> Unit,
    drawerBody: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = GeoVaultMapScaffoldDefaults.DrawerCornerRadius,
        topEnd = GeoVaultMapScaffoldDefaults.DrawerCornerRadius,
    )
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .offset {
                val y = runCatching { drawerState.anchoredDraggableState.requireOffset() }
                    .getOrDefault(0f)
                    .roundToInt()
                    .coerceAtLeast(0)
                IntOffset(x = 0, y = y)
            }
            .anchoredDraggable(
                state = drawerState.anchoredDraggableState,
                orientation = Orientation.Vertical,
            )
            .then(nestedScrollModifier)
            .clip(shape),
        color = GeoVaultMapScaffoldDefaults.DrawerContainerColor,
        shape = shape,
        elevation = GeoVaultMapScaffoldDefaults.DrawerElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = GeoVaultMapScaffoldDefaults.DrawerTopBorderWidth,
                    color = GeoVaultMapScaffoldDefaults.DrawerBorderColor,
                    shape = shape,
                ),
        ) {
            DragHandle()
            DrawerHeaderRow(drawerHeader)
            drawerBody()
        }
    }
}

@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = GeoVaultMapScaffoldDefaults.DragHandleTopPadding,
                bottom = GeoVaultMapScaffoldDefaults.DragHandleBottomPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = GeoVaultMapScaffoldDefaults.DragHandleWidth,
                    height = GeoVaultMapScaffoldDefaults.DragHandleHeight,
                )
                .background(
                    color = GeoVaultMapScaffoldDefaults.DragHandleColor,
                    shape = RoundedCornerShape(GeoVaultMapScaffoldDefaults.DragHandleHeight),
                ),
        )
    }
}

@Composable
private fun DrawerHeaderRow(
    drawerHeader: @Composable GeoVaultMapDrawerHeaderScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GeoVaultMapScaffoldDefaults.HeaderMinHeight)
            .padding(
                horizontal = GeoVaultMapScaffoldDefaults.HeaderHorizontalPadding,
                vertical = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DefaultGeoVaultMapDrawerHeaderScope(rowScope = this).drawerHeader()
    }
}

package com.geovault.common.maps.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
    drawerHeader: @Composable GeoVaultMapDrawerHeaderScope.() -> Unit,
    drawerBody: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = GeoVaultMapScaffoldDefaults.DrawerCornerRadius,
        topEnd = GeoVaultMapScaffoldDefaults.DrawerCornerRadius,
    )
    // Drag is intentionally header-only: only the drag handle + title row carry the
    // [anchoredDraggable] modifier (see [DragHandle] / [DrawerHeaderRow]). Scrolling the
    // body (e.g. a LazyColumn) does not move the drawer — this mirrors the old survey app's
    // `DragFromHeaderBottomSheetBehavior`, which ignored nested scroll from the list so the
    // list could scroll freely while the drawer stayed parked at its current anchor.
    //
    // The Surface height is pinned to the full scaffold container (not the visible slice) so
    // the card is always as tall as the screen, identical to how the old app declared the
    // `MaterialCardView` with `match_parent` height and let `BottomSheetBehavior` position it
    // via translation. With this sizing:
    //   * the container background paints the entire drawer strip regardless of how little
    //     content the body holds (fixes the "short list leaves a hole" case), and
    //   * the inner [Column] has a bounded height so a [drawerBody] that uses
    //     `Modifier.weight(1f)` (e.g. a LazyColumn) gets a real height constraint and scrolls
    //     internally instead of overflowing below the drawer.
    // The bottom half of the Surface that sits below the scaffold edge is simply off-screen
    // — clipped by the enclosing Box / bottom-nav row — so overshoot costs us nothing.
    val containerHeightPx by drawerState.containerHeightPxState
    val density = LocalDensity.current
    val drawerHeightDp = with(density) { containerHeightPx.toDp() }
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
            .height(drawerHeightDp)
            .drawerTopAndSidesBorder(
                shape = shape,
                color = GeoVaultMapScaffoldDefaults.DrawerBorderColor,
                width = GeoVaultMapScaffoldDefaults.DrawerBorderWidth,
            )
            .clip(shape),
        color = GeoVaultMapScaffoldDefaults.DrawerContainerColor,
        shape = shape,
        elevation = GeoVaultMapScaffoldDefaults.DrawerElevation,
    ) {
        val headerDragModifier = Modifier.anchoredDraggable(
            state = drawerState.anchoredDraggableState,
            orientation = Orientation.Vertical,
        )
        val headerDividerColor = GeoVaultMapScaffoldDefaults.HeaderDividerColor
        Column(modifier = Modifier.fillMaxSize()) {
            DragHandle(modifier = headerDragModifier)
            DrawerHeaderRow(modifier = headerDragModifier, drawerHeader = drawerHeader)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GeoVaultMapScaffoldDefaults.HeaderDividerThickness)
                    .background(headerDividerColor),
            )
            drawerBody()
        }
    }
}

/**
 * Insets the stroked path inward by half the stroke width so the full stroke weight lies
 * inside the layer. Stroking the true outer edge is half-clipped at the top, so the top arc
 * looked thinner than the vertical sides.
 */
private fun Modifier.drawerTopAndSidesBorder(
    shape: RoundedCornerShape,
    color: Color,
    width: Dp,
): Modifier = this.drawWithContent {
    val strokePx = width.toPx()
    val halfStroke = strokePx * 0.5f
    drawContent()
    val outline = shape.createOutline(
        size = this.size,
        layoutDirection = layoutDirection,
        density = this,
    )
    clipRect(
        left = 0f,
        top = 0f,
        right = this.size.width,
        bottom = this.size.height - 2f * strokePx,
    ) {
        when (val def = outline) {
            is Outline.Rectangle -> {
                val path = Path()
                path.addRect(def.rect.deflate(halfStroke))
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokePx),
                )
            }
            is Outline.Rounded -> {
                val insetRr = def.roundRect.insetBy(halfStroke) ?: return@clipRect
                val path = Path()
                path.addRoundRect(insetRr)
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokePx),
                )
            }
            is Outline.Generic -> {
                drawPath(
                    path = def.path,
                    color = color,
                    style = Stroke(width = strokePx),
                )
            }
        }
    }
}

private fun RoundRect.insetBy(delta: Float): RoundRect? {
    if (delta <= 0f) return this
    val l = left + delta
    val t = top + delta
    val r = right - delta
    val b = bottom - delta
    if (l >= r || t >= b) return null
    fun shrink(c: CornerRadius) = CornerRadius(
        (c.x - delta).coerceAtLeast(0f),
        (c.y - delta).coerceAtLeast(0f),
    )
    return RoundRect(
        left = l,
        top = t,
        right = r,
        bottom = b,
        topLeftCornerRadius = shrink(topLeftCornerRadius),
        topRightCornerRadius = shrink(topRightCornerRadius),
        bottomRightCornerRadius = shrink(bottomRightCornerRadius),
        bottomLeftCornerRadius = shrink(bottomLeftCornerRadius),
    )
}

@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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
    modifier: Modifier = Modifier,
    drawerHeader: @Composable GeoVaultMapDrawerHeaderScope.() -> Unit,
) {
    Row(
        modifier = modifier
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

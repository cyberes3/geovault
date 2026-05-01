package com.geovault.common.maps.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBarDefaults
import com.geovault.common.ui.modifier.geoVaultStableNavigationBarsPadding

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
 * - The map fills the entire container (it sits *under* the drawer, not *beside* it). The
 *   map's pixel size is deliberately decoupled from the system navigation-bar inset: only
 *   the chrome subtree (drawer + [bottomStart] + [bottomEnd]) reserves bottom safe-area, so
 *   `MapView`'s GL surface does not re-measure when the OS animates the system bars during
 *   keyguard transitions (which would otherwise visibly squish the map on screen-off →
 *   resume).
 * - The drawer is positioned by an offset driven by [drawerState] and clipped with the
 *   scaffold's rounded-top shape. Consumers should not apply their own offset/shape modifiers
 *   to the drawer body — the scaffold owns them.
 * - Overlay slots ([topStart], [topEnd], [bottomStart], [bottomEnd]) render above the map and
 *   below the drawer. FAB columns, chips, and banners live here. [bottomStart] / [bottomEnd]
 *   inherit nav-bar safe-area through the chrome subtree; [topStart] / [topEnd] do not (the
 *   parent topbar/scaffold owns top safe-area).
 *
 * ### Camera-padding contract
 *
 * Consumers read [GeoVaultMapDrawerState.visibleHeightPx] and feed it into their
 * [com.geovault.common.maps.core.GeoVaultMapPaddingPolicy] / `mapPaddingDp.bottom`. The
 * scaffold *never* mutates the map directly — one-way data flow keeps MapLibre state
 * recoverable when the drawer re-anchors unexpectedly (config change, theme switch, etc.).
 *
 * @param drawerDragEnabled When false, the drag handle and header row do not participate in
 * anchored drag (e.g. while the map style is still loading). Programmatic [GeoVaultMapDrawerState]
 * moves still work. [GeoVaultMapDrawerHeaderScope.headerInteractionsEnabled] matches this flag so
 * built-in search/settings header buttons are disabled until the drawer is interactive again.
 * @param drawerTitle Optional leading title rendered by the scaffold before [drawerHeader]
 * actions. Prefer this over app-local title text so drawer title styling remains centralized.
 * @param drawerTitleChip Optional leading title chip rendered instead of [drawerTitle].
 * @param onDrawerClose Optional first-party close/X action rendered before the title.
 */
@Composable
fun GeoVaultMapScaffold(
    modifier: Modifier = Modifier,
    drawerState: GeoVaultMapDrawerState = rememberGeoVaultMapDrawerState(),
    drawerDragEnabled: Boolean = true,
    drawerTitle: String? = null,
    drawerTitleCentered: Boolean = false,
    drawerTitleChip: GeoVaultMapDrawerTitleChip? = null,
    onDrawerClose: (() -> Unit)? = null,
    drawerCloseContentDescription: String = "Close",
    drawerCloseTooltip: String? = "Close",
    drawerHeader: @Composable GeoVaultMapDrawerHeaderScope.() -> Unit,
    drawerBody: @Composable ColumnScope.() -> Unit,
    topStart: (@Composable BoxScope.() -> Unit)? = null,
    topEnd: (@Composable BoxScope.() -> Unit)? = null,
    bottomStart: (@Composable BoxScope.() -> Unit)? = null,
    bottomEnd: (@Composable BoxScope.() -> Unit)? = null,
    scaleBar: (@Composable () -> Unit)? = null,
    mapContent: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            mapContent()
        }

        if (topStart != null) {
            Box(modifier = Modifier.align(Alignment.TopStart), content = topStart)
        }
        if (topEnd != null) {
            Box(modifier = Modifier.align(Alignment.TopEnd), content = topEnd)
        }

        // Chrome safe-area subtree. The OUTER Box claims nav-bar inset for the chrome only —
        // the underlying map (sibling above) is intentionally not in this padded subtree, so
        // its GL surface never re-measures when the OS toggles system-bar visibility during
        // keyguard/screen-off animations. The INNER Box is what the drawer + bottom overlay
        // slots actually live in: its size equals the visible (post-padding) area, which is
        // what `onSizeChanged` must report so drawer anchor offsets land just above the
        // system bar. `clipToBounds` keeps the drawer's overflow Surface (translated past
        // the chrome bottom in the Collapsed state) from painting into the system-bar zone.
        Box(
            modifier = Modifier
                .matchParentSize()
                .geoVaultStableNavigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { size -> drawerState.updateAnchors(size.height) },
            ) {
                if (scaleBar != null) {
                    val density = LocalDensity.current
                    val bottomPadding = with(density) {
                        drawerState.peekHeightPx.toDp() + GeoVaultMapScaleBarDefaults.DrawerGap
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = GeoVaultMapScaleBarDefaults.EdgePadding,
                                bottom = bottomPadding,
                            ),
                    ) {
                        scaleBar()
                    }
                }

                DrawerLayer(
                    drawerState = drawerState,
                    drawerDragEnabled = drawerDragEnabled,
                    drawerTitle = drawerTitle,
                    drawerTitleCentered = drawerTitleCentered,
                    drawerTitleChip = drawerTitleChip,
                    onDrawerClose = onDrawerClose,
                    drawerCloseContentDescription = drawerCloseContentDescription,
                    drawerCloseTooltip = drawerCloseTooltip,
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
    drawerDragEnabled: Boolean,
    drawerTitle: String?,
    drawerTitleCentered: Boolean,
    drawerTitleChip: GeoVaultMapDrawerTitleChip?,
    onDrawerClose: (() -> Unit)?,
    drawerCloseContentDescription: String,
    drawerCloseTooltip: String?,
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
    // Drag-release fling behavior. Built via the modern (post-1.7) AnchoredDraggableDefaults
    // entry point so the user-configured [GeoVaultMapDrawerState.snapAnimationSpec] (snappy
    // spring by default) drives the settle animation instead of the foundation library's
    // unconfigured default tween. Programmatic snaps still go through
    // [GeoVaultMapDrawerState.animateTo] which forwards the same spec explicitly.
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = drawerState.anchoredDraggableState,
        animationSpec = drawerState.snapAnimationSpec,
    )
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            // graphicsLayer reads the live offset every frame on the GPU layer thread —
            // unlike Modifier.offset { ... }, which defers to the placement phase and can
            // drop intermediate frames during fast spring animations. This is the same
            // pattern Compose Material's own ModalBottomSheet uses for its sheet translation.
            //
            // shape + clip on the layer route clipping through the RenderNode outline path
            // (hardware accelerated, no per-frame path tessellation), which is decisively
            // cheaper than `Surface(shape = …)` during a fast LazyColumn fling — the latter
            // applies an extra `Modifier.clip(shape)` to the children inside the surface and
            // re-tessellates the rounded path in software each frame the body invalidates.
            .graphicsLayer {
                translationY = runCatching { drawerState.anchoredDraggableState.requireOffset() }
                    .getOrDefault(0f)
                    .coerceAtLeast(0f)
            }
            .clip(shape)
            .height(drawerHeightDp)
            .drawerTopAndSidesBorder(
                shape = shape,
                color = GeoVaultMapScaffoldDefaults.DrawerBorderColor,
                width = GeoVaultMapScaffoldDefaults.DrawerBorderWidth,
            ),
        color = GeoVaultMapScaffoldDefaults.DrawerContainerColor,
        elevation = GeoVaultMapScaffoldDefaults.DrawerElevation,
    ) {
        val headerDragModifier = if (drawerDragEnabled) {
            Modifier.anchoredDraggable(
                state = drawerState.anchoredDraggableState,
                orientation = Orientation.Vertical,
                flingBehavior = flingBehavior,
            )
        } else {
            Modifier
        }
        val headerDividerColor = GeoVaultMapScaffoldDefaults.HeaderDividerColor
        Column(modifier = Modifier.fillMaxSize()) {
            DragHandle(modifier = headerDragModifier)
            DrawerHeaderRow(
                modifier = headerDragModifier,
                headerInteractionsEnabled = drawerDragEnabled,
                drawerTitle = drawerTitle,
                drawerTitleCentered = drawerTitleCentered,
                drawerTitleChip = drawerTitleChip,
                onDrawerClose = onDrawerClose,
                drawerCloseContentDescription = drawerCloseContentDescription,
                drawerCloseTooltip = drawerCloseTooltip,
                drawerHeader = drawerHeader,
            )
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
 *
 * Implemented with [drawWithCache] (not [drawWithContent]) so the [Outline], inset
 * [RoundRect], [Path], and [Stroke] are built once and reused across draws. The drawer's
 * graphicsLayer is the closest render layer for the LazyColumn body, so during a fast
 * LazyColumn fling this draw modifier is re-invoked every frame — allocating fresh path
 * / stroke / outline objects per frame produced visible jank. The cache key (size +
 * density + style + color) only changes on real layout changes, so the per-frame work
 * collapses to a single cached `drawPath` call.
 */
private fun Modifier.drawerTopAndSidesBorder(
    shape: RoundedCornerShape,
    color: Color,
    width: Dp,
): Modifier = this.drawWithCache {
    val strokePx = width.toPx()
    val halfStroke = strokePx * 0.5f
    val stroke = Stroke(width = strokePx)
    val outline = shape.createOutline(
        size = this.size,
        layoutDirection = layoutDirection,
        density = this,
    )
    val borderPath: Path? = when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect.deflate(halfStroke)) }
        is Outline.Rounded -> outline.roundRect.insetBy(halfStroke)?.let { rr ->
            Path().apply { addRoundRect(rr) }
        }
        is Outline.Generic -> outline.path
    }
    val clipRight = this.size.width
    val clipBottom = this.size.height - 2f * strokePx
    onDrawWithContent {
        drawContent()
        if (borderPath == null) return@onDrawWithContent
        clipRect(
            left = 0f,
            top = 0f,
            right = clipRight,
            bottom = clipBottom,
        ) {
            drawPath(
                path = borderPath,
                color = color,
                style = stroke,
            )
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
    headerInteractionsEnabled: Boolean,
    drawerTitle: String?,
    drawerTitleCentered: Boolean,
    drawerTitleChip: GeoVaultMapDrawerTitleChip?,
    onDrawerClose: (() -> Unit)?,
    drawerCloseContentDescription: String,
    drawerCloseTooltip: String?,
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
        DefaultGeoVaultMapDrawerHeaderScope(
            rowScope = this,
            headerInteractionsEnabled = headerInteractionsEnabled,
        ).apply {
            onDrawerClose?.let { close ->
                CloseAction(
                    onClick = close,
                    contentDescription = drawerCloseContentDescription,
                    tooltip = drawerCloseTooltip,
                )
            }
            val titleModifier = if (drawerTitleCentered || drawerTitleChip != null) {
                Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally)
            } else {
                Modifier.weight(1f)
            }
            if (drawerTitleChip != null) {
                TitleChip(
                    icon = drawerTitleChip.icon,
                    text = drawerTitleChip.text,
                    modifier = titleModifier,
                )
            } else {
                drawerTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    PlainTitle(
                        text = title,
                        modifier = titleModifier,
                    )
                }
            }
            drawerHeader()
        }
    }
}

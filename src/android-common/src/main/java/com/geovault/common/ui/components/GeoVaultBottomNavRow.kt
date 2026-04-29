package com.geovault.common.ui.components

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.geovault.common.ui.modifier.geoVaultStableNavigationBarsPadding
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Immutable
data class GeoVaultBottomNavDestination(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val contentDescription: String = label,
    val tooltip: String? = null,
)

@Stable
class GeoVaultBottomNavVisibilityController internal constructor(
    private val onRequestHide: () -> Unit,
    private val onReleaseHide: () -> Unit,
) {
    fun requestHide() = onRequestHide()
    fun releaseHide() = onReleaseHide()
}

@Stable
class GeoVaultBottomNavDisableController internal constructor(
    private val onRequestDisable: () -> Unit,
    private val onReleaseDisable: () -> Unit,
) {
    fun requestDisable() = onRequestDisable()
    fun releaseDisable() = onReleaseDisable()
}

private val LocalGeoVaultBottomNavVisibilityController =
    staticCompositionLocalOf<GeoVaultBottomNavVisibilityController?> { null }

private val LocalGeoVaultBottomNavDisableController =
    staticCompositionLocalOf<GeoVaultBottomNavDisableController?> { null }

@Composable
fun GeoVaultRequestBottomTabsHidden(shouldHide: Boolean) {
    val controller = LocalGeoVaultBottomNavVisibilityController.current ?: return
    DisposableEffect(controller, shouldHide) {
        if (shouldHide) {
            controller.requestHide()
        }
        onDispose {
            if (shouldHide) {
                controller.releaseHide()
            }
        }
    }
}

@Composable
fun GeoVaultRequestBottomTabsDisabled(shouldDisable: Boolean) {
    val controller = LocalGeoVaultBottomNavDisableController.current ?: return
    DisposableEffect(controller, shouldDisable) {
        if (shouldDisable) {
            controller.requestDisable()
        }
        onDispose {
            if (shouldDisable) {
                controller.releaseDisable()
            }
        }
    }
}

@Composable
fun GeoVaultBottomNavScaffold(
    destinations: List<GeoVaultBottomNavDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (GeoVaultBottomNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (activeDestination: GeoVaultBottomNavDestination) -> Unit,
) {
    require(destinations.isNotEmpty()) { "GeoVaultBottomNavScaffold requires at least one destination." }
    val destinationIds = destinations.map { it.id }
    require(destinationIds.distinct().size == destinationIds.size) {
        "GeoVaultBottomNavScaffold destination IDs must be unique."
    }
    val activeDestination = destinations.firstOrNull { it.id == selectedDestinationId } ?: destinations.first()
    var hiddenRequests by remember { mutableIntStateOf(0) }
    val visibilityController = remember {
        GeoVaultBottomNavVisibilityController(
            onRequestHide = { hiddenRequests += 1 },
            onReleaseHide = {
                if (hiddenRequests > 0) {
                    hiddenRequests -= 1
                }
            },
        )
    }
    var disableRequests by remember { mutableIntStateOf(0) }
    val disableController = remember {
        GeoVaultBottomNavDisableController(
            onRequestDisable = { disableRequests += 1 },
            onReleaseDisable = {
                if (disableRequests > 0) {
                    disableRequests -= 1
                }
            },
        )
    }
    val areTabsHidden = hiddenRequests > 0
    val areTabsDisabled = disableRequests > 0

    CompositionLocalProvider(
        LocalGeoVaultBottomNavVisibilityController provides visibilityController,
        LocalGeoVaultBottomNavDisableController provides disableController,
    ) {
        // The bottom-nav scaffold owns nav-bar safe-area for its subtree: the row sits above
        // the system navigation bar and the content area stops at the system bar so list/form
        // screens never bleed underneath. We use the stable (visibility-ignoring) variant so
        // that any map subtree hosted inside a tab does not re-measure when the OS animates
        // the system bars hidden/visible during keyguard transitions. Descendants that pad
        // with the same inset type (e.g. GeoVaultMapScaffold's drawer) read zero thanks to
        // the standard "consumed by parent" semantics of windowInsetsPadding, so nesting does
        // not double-pad.
        Column(
            modifier = modifier
                .fillMaxSize()
                .geoVaultStableNavigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content(activeDestination)
            }
            if (!areTabsHidden) {
                val effectiveDestinations = if (areTabsDisabled) {
                    destinations.map { it.copy(enabled = false) }
                } else {
                    destinations
                }
                GeoVaultBottomNavRow(
                    destinations = effectiveDestinations,
                    selectedDestinationId = selectedDestinationId,
                    onDestinationSelected = onDestinationSelected,
                )
            }
        }
    }

    val activity = LocalContext.current as? ComponentActivity
    SideEffect {
        if (activity != null && !areTabsHidden) {
            val lighterNavBarBlue = ColorUtils.blendARGB(
                GeoVaultColorTokens.MainBlue.toArgb(),
                GeoVaultColorTokens.Surface.toArgb(),
                0.12f,
            )
            // Keep the Android system navigation bar blue when a bottom nav row is present.
            // This runs after child content composition so tab screen chrome can't override it.
            GeoVaultSystemBars.setNavigationBarBackground(
                activity = activity,
                navigationBarColor = lighterNavBarBlue,
                useDarkNavigationBarIcons = false,
            )
        }
    }
}

@Composable
fun GeoVaultBottomNavRow(
    destinations: List<GeoVaultBottomNavDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (GeoVaultBottomNavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GeoVaultColorTokens.MainBlue)
            // Nav-bar safe-area is applied by GeoVaultBottomNavScaffold's outer Column so the
            // tab row and the content area both rest above the system navigation bar without
            // double-padding.
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            val isSelected = destination.id == selectedDestinationId
            val tint = when {
                !destination.enabled -> Color.White.copy(alpha = 0.5f)
                isSelected -> GeoVaultColorTokens.MainYellow
                else -> Color.White
            }
            val interactionSource = remember(destination.id) { MutableInteractionSource() }
            var anchorBounds by remember { mutableStateOf<Rect?>(null) }
            val tooltipText = destination.tooltip?.takeIf { it.isNotBlank() }
            val suppressNextClickAfterTooltip = if (tooltipText != null) {
                remember(destination.id) { mutableStateOf(false) }
            } else {
                null
            }
            if (tooltipText != null) {
                GeoVaultInstallLongPressTooltip(
                    tooltipText = tooltipText,
                    enabled = destination.enabled,
                    interactionSource = interactionSource,
                    anchorBounds = anchorBounds,
                    suppressNextClickAfterTooltip = suppressNextClickAfterTooltip,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .let {
                        if (tooltipText != null) {
                            it.trackGeoVaultTooltipBounds(interactionSource) { bounds ->
                                anchorBounds = bounds
                            }
                        } else it
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = destination.enabled,
                    ) {
                        if (suppressNextClickAfterTooltip?.value == true) {
                            suppressNextClickAfterTooltip.value = false
                        } else {
                            onDestinationSelected(destination)
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.contentDescription,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = destination.label,
                    color = tint,
                    style = MaterialTheme.typography.caption.copy(
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

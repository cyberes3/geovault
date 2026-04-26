package com.geovault.common.maps.ui

import android.graphics.Rect
import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultInstallLongPressTooltip
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
import com.geovault.common.ui.components.trackGeoVaultTooltipBounds
import com.geovault.common.ui.theme.GeoVaultColorTokens

sealed class GeoVaultMapFabIcon {
    data class Vector(val imageVector: ImageVector) : GeoVaultMapFabIcon()
    data class Drawable(@param:DrawableRes val drawableResId: Int) : GeoVaultMapFabIcon()
    data class Spinner(
        val spinnerSize: Dp = 20.dp,
        val spinnerColor: Color = Color.White,
    ) : GeoVaultMapFabIcon()
}

data class GeoVaultMapFabAction(
    val id: String,
    val order: Int,
    val icon: GeoVaultMapFabIcon,
    val contentDescription: String,
    val enabled: Boolean = true,
    val emphasized: Boolean = false,
    val backgroundColor: Color? = null,
    val contentColor: Color = Color.White,
    val onTap: (() -> Unit)? = null,
    val tooltip: String? = null,
    /** Clockwise rotation applied to vector/drawable icons inside the FAB. */
    val iconRotationDegrees: Float = 0f,
    /** When true, the icon keeps its drawable intrinsic colors (no content tint). */
    val useIntrinsicIconColors: Boolean = false,
)

class GeoVaultMapFabBuilder {
    private val actions = mutableListOf<GeoVaultMapFabAction>()

    fun action(
        id: String,
        order: Int,
        icon: GeoVaultMapFabIcon,
        contentDescription: String,
        enabled: Boolean = true,
        emphasized: Boolean = false,
        backgroundColor: Color? = null,
        contentColor: Color = Color.White,
        onTap: (() -> Unit)? = null,
        tooltip: String? = null,
        iconRotationDegrees: Float = 0f,
        useIntrinsicIconColors: Boolean = false,
    ): GeoVaultMapFabBuilder {
        actions.add(
            GeoVaultMapFabAction(
                id = id,
                order = order,
                icon = icon,
                contentDescription = contentDescription,
                enabled = enabled,
                emphasized = emphasized,
                backgroundColor = backgroundColor,
                contentColor = contentColor,
                onTap = onTap,
                tooltip = tooltip,
                iconRotationDegrees = iconRotationDegrees,
                useIntrinsicIconColors = useIntrinsicIconColors,
            ),
        )
        return this
    }

    fun build(): List<GeoVaultMapFabAction> = actions.sortedBy { it.order }
}

fun buildGeoVaultMapFabActions(block: GeoVaultMapFabBuilder.() -> Unit): List<GeoVaultMapFabAction> {
    return GeoVaultMapFabBuilder().apply(block).build()
}

@Composable
fun GeoVaultMapFabColumn(
    actions: List<GeoVaultMapFabAction>,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 10.dp,
    fabSize: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    onActionTap: ((GeoVaultMapFabAction) -> Unit)? = null,
    /**
     * When true, taps and long-press tooltips are ignored; FABs keep normal enabled visuals
     * ([GeoVaultMapFabAction.enabled] and colors unchanged).
     */
    tapSuppressed: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        actions.sortedBy { it.order }.forEach { action ->
            val backgroundColor = action.backgroundColor ?: if (action.emphasized) {
                GeoVaultColorTokens.Success
            } else {
                GeoVaultColorTokens.MainBlue
            }
            val interactionSource = remember(action.id) { MutableInteractionSource() }
            var anchorBounds by remember { mutableStateOf<Rect?>(null) }
            val tooltipText = action.tooltip?.takeIf { it.isNotBlank() }
            val suppressNextClickAfterTooltip = if (tooltipText != null) {
                remember(action.id) { mutableStateOf(false) }
            } else {
                null
            }
            if (tooltipText != null) {
                GeoVaultInstallLongPressTooltip(
                    tooltipText = tooltipText,
                    enabled = action.enabled && !tapSuppressed,
                    interactionSource = interactionSource,
                    anchorBounds = anchorBounds,
                    suppressNextClickAfterTooltip = suppressNextClickAfterTooltip,
                )
            }
            FloatingActionButton(
                onClick = {
                    if (tapSuppressed) return@FloatingActionButton
                    if (!action.enabled) return@FloatingActionButton
                    if (suppressNextClickAfterTooltip?.value == true) {
                        suppressNextClickAfterTooltip.value = false
                        return@FloatingActionButton
                    }
                    action.onTap?.invoke()
                    onActionTap?.invoke(action)
                },
                modifier = Modifier
                    .size(fabSize)
                    .trackGeoVaultTooltipBounds { anchorBounds = it },
                interactionSource = interactionSource,
                shape = CircleShape,
                backgroundColor = if (action.enabled) backgroundColor else backgroundColor.copy(alpha = GeoVaultColorTokens.FabDisabledTint),
                contentColor = if (action.enabled) action.contentColor else action.contentColor.copy(alpha = 0.75f),
                elevation = androidx.compose.material.FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            ) {
                val iconRotation = action.iconRotationDegrees
                val iconModifier = Modifier
                    .size(iconSize)
                    .rotate(iconRotation)
                when (val icon = action.icon) {
                    is GeoVaultMapFabIcon.Vector -> {
                        Icon(
                            imageVector = icon.imageVector,
                            contentDescription = action.contentDescription,
                            modifier = iconModifier,
                        )
                    }
                    is GeoVaultMapFabIcon.Drawable -> {
                        val tint = if (action.useIntrinsicIconColors) {
                            Color.Unspecified
                        } else {
                            LocalContentColor.current.copy(alpha = LocalContentAlpha.current)
                        }
                        Icon(
                            painter = painterResource(id = icon.drawableResId),
                            contentDescription = action.contentDescription,
                            modifier = iconModifier,
                            tint = tint,
                        )
                    }
                    is GeoVaultMapFabIcon.Spinner -> {
                        Box(modifier = iconModifier) {
                            GeoVaultLoadingSpinner(
                                spinnerSize = icon.spinnerSize,
                                color = icon.spinnerColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

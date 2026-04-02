package com.geovault.common.maps.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultLoadingSpinner
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
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        actions.sortedBy { it.order }.forEach { action ->
            val backgroundColor = action.backgroundColor ?: if (action.emphasized) {
                GeoVaultColorTokens.Success
            } else {
                GeoVaultColorTokens.PrimaryBlue
            }
            FloatingActionButton(
                onClick = {
                    if (!action.enabled) return@FloatingActionButton
                    action.onTap?.invoke()
                    onActionTap?.invoke(action)
                },
                modifier = Modifier.size(fabSize),
                shape = CircleShape,
                backgroundColor = if (action.enabled) backgroundColor else backgroundColor.copy(alpha = 0.55f),
                contentColor = if (action.enabled) action.contentColor else action.contentColor.copy(alpha = 0.75f),
                elevation = androidx.compose.material.FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            ) {
                when (val icon = action.icon) {
                    is GeoVaultMapFabIcon.Vector -> {
                        Icon(
                            imageVector = icon.imageVector,
                            contentDescription = action.contentDescription,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                    is GeoVaultMapFabIcon.Drawable -> {
                        Icon(
                            painter = painterResource(id = icon.drawableResId),
                            contentDescription = action.contentDescription,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                    is GeoVaultMapFabIcon.Spinner -> {
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

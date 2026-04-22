package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class TopBarIconAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val tint: Color = Color.White
)

data class TopBarMenuEntry(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

object GeoVaultTopTitleBarDefaults {
    fun closeAction(
        onClick: () -> Unit,
        contentDescription: String = "Close",
        tint: Color = Color.White
    ): TopBarIconAction = TopBarIconAction(
        icon = Icons.Filled.Close,
        contentDescription = contentDescription,
        onClick = onClick,
        tint = tint
    )

    fun settingsAction(
        onClick: () -> Unit,
        contentDescription: String = "Settings",
        tint: Color = Color.White
    ): TopBarIconAction = TopBarIconAction(
        icon = Icons.Filled.Settings,
        contentDescription = contentDescription,
        onClick = onClick,
        tint = tint
    )

    fun settingsMenuEntries(
        onOpenSettings: () -> Unit,
        extraEntries: List<TopBarMenuEntry> = emptyList(),
        settingsLabel: String = "Settings"
    ): List<TopBarMenuEntry> {
        return extraEntries + TopBarMenuEntry(
            label = settingsLabel,
            onClick = onOpenSettings
        )
    }
}

/**
 * Controls when [GeoVaultTopBarSettingsMenuAction] renders its overflow menu.
 *
 * The composable previously accepted an `isAuthenticated: Boolean?` parameter that overloaded two
 * responsibilities: declaring the visibility policy and overriding the auth source-of-truth. This
 * enum makes the visibility contract explicit so call sites no longer have to lie about auth state
 * to keep settings reachable on pre-auth screens.
 */
enum class GeoVaultTopBarMenuVisibility {
    /**
     * Menu is shown only when [GeovaultAuthManager] reports the user as signed in. This is the
     * default and matches the post-login top-bar convention used by most surfaces.
     */
    AuthenticatedOnly,

    /**
     * Menu is always shown, regardless of authentication state. Use for surfaces that must expose
     * settings before sign-in — for example, changing the server URL or diagnostics while the user
     * is unauthenticated.
     */
    Always,
}

@Composable
fun RowScope.GeoVaultTopBarSettingsMenuAction(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    extraEntries: List<TopBarMenuEntry> = emptyList(),
    settingsLabel: String = "Settings",
    visibility: GeoVaultTopBarMenuVisibility = GeoVaultTopBarMenuVisibility.AuthenticatedOnly,
    iconTint: Color = Color.White,
    iconContentDescription: String = "More options",
    overflowTooltip: String? = null,
    enabled: Boolean = true,
) {
    if (visibility == GeoVaultTopBarMenuVisibility.AuthenticatedOnly) {
        val context = LocalContext.current
        if (!GeovaultAuthManager.isLoggedIn(context)) {
            return
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val entries = remember(onOpenSettings, extraEntries, settingsLabel) {
        GeoVaultTopTitleBarDefaults.settingsMenuEntries(
            onOpenSettings = onOpenSettings,
            extraEntries = extraEntries,
            settingsLabel = settingsLabel
        )
    }
    GeoVaultTopBarOverflowMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        enabled = enabled,
        iconTint = iconTint,
        iconContentDescription = iconContentDescription,
        overflowTooltip = overflowTooltip,
        modifier = modifier
    ) {
        entries.forEach { entry ->
            DropdownMenuItem(
                onClick = {
                    expanded = false
                    entry.onClick()
                },
                enabled = entry.enabled
            ) {
                Text(
                    text = entry.label,
                    color = if (entry.enabled) {
                        MaterialTheme.colors.onSurface
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    }
                )
            }
        }
    }
}

@Composable
private fun RowScope.GeoVaultTopBarOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    iconTint: Color,
    iconContentDescription: String,
    overflowTooltip: String? = null,
    modifier: Modifier = Modifier,
    menuContent: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier) {
        val icon: @Composable () -> Unit = {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = iconContentDescription,
                tint = iconTint.copy(alpha = if (enabled) 1f else 0.45f)
            )
        }
        if (!overflowTooltip.isNullOrBlank()) {
            GeoVaultIconButton(
                onClick = { onExpandedChange(true) },
                enabled = enabled,
                tooltip = overflowTooltip,
                content = icon,
            )
        } else {
            IconButton(
                onClick = { onExpandedChange(true) },
                enabled = enabled
            ) {
                icon()
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            menuContent()
        }
    }
}

@Composable
fun GeoVaultTopTitleBar(
    title: String,
    subtitle: String? = null,
    backgroundColor: Color = MaterialTheme.colors.primary,
    contentColor: Color = Color.White,
    syncSystemStatusBarColor: Boolean = true,
    hideIconActions: Boolean = false,
    rightActions: List<TopBarIconAction> = emptyList(),
    actionsContent: @Composable (RowScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    if (syncSystemStatusBarColor && activity != null) {
        SideEffect {
            GeoVaultSystemBars.setStatusBarBackground(
                activity = activity,
                statusBarColor = backgroundColor.toArgb()
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        TopAppBar(
            modifier = Modifier.statusBarsPadding(),
            title = {
                if (subtitle.isNullOrBlank()) {
                    Text(text = title, color = contentColor)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = title,
                            color = contentColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            color = contentColor.copy(alpha = 0.82f),
                            fontSize = 11.sp,
                            lineHeight = 11.sp,
                        )
                    }
                }
            },
            backgroundColor = backgroundColor,
            contentColor = contentColor,
            elevation = 0.dp,
            actions = {
                if (!hideIconActions) {
                    rightActions.forEach { action ->
                        IconButton(onClick = action.onClick) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = action.contentDescription,
                                tint = action.tint
                            )
                        }
                    }
                    actionsContent?.invoke(this)
                }
            }
        )
    }
}

/**
 * Composition primitive for the compact sub-view title bar. Internal — call sites render
 * this only via [GeoVaultSubViewScaffold] or [GeoVaultTopTabSurface]'s dismiss params so the
 * chrome has exactly one source of truth.
 */
@Composable
internal fun GeoVaultCompactDismissTitleBar(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeContentDescription: String = "Close",
    closeTooltip: String? = null,
) {
    val backgroundColor = if (MaterialTheme.colors.isLight) {
        GeoVaultColorTokens.Gray300.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.10f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = GeoVaultColorTokens.BorderLight.copy(alpha = 0.9f),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height - strokeWidth / 2f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - strokeWidth / 2f),
                    strokeWidth = strokeWidth,
                )
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.subtitle1,
            color = MaterialTheme.colors.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (closeTooltip.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = closeContentDescription,
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.size(30.dp),
                )
            }
        } else {
            GeoVaultClickableWithTooltip(
                onClick = onClose,
                modifier = Modifier.size(48.dp),
                tooltip = closeTooltip,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = closeContentDescription,
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

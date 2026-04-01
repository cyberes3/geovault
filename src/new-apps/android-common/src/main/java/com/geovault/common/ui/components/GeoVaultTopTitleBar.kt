package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.system.GeoVaultSystemBars

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

@Composable
fun RowScope.GeoVaultTopBarSettingsMenuAction(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    extraEntries: List<TopBarMenuEntry> = emptyList(),
    settingsLabel: String = "Settings",
    isAuthenticated: Boolean? = null,
    iconTint: Color = Color.White,
    iconContentDescription: String = "More options",
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val resolvedIsAuthenticated = isAuthenticated ?: GeovaultAuthManager.isLoggedIn(context)
    if (!resolvedIsAuthenticated) {
        return
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
    modifier: Modifier = Modifier,
    menuContent: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier) {
        IconButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = iconContentDescription,
                tint = iconTint.copy(alpha = if (enabled) 1f else 0.45f)
            )
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
            GeoVaultSystemBars.applyAppChrome(
                activity = activity,
                statusBarColor = backgroundColor.toArgb()
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(backgroundColor)
        )
        TopAppBar(
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

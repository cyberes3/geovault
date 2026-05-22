package com.geovault.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import com.geovault.common.ui.theme.geoVaultInputPlaceholderColor
import com.geovault.common.ui.theme.geoVaultDialogTitleColor

/**
 * One tappable row inside a [GeoVaultActionSheetDialog]. Set [destructive] to `true` to tint
 * the label with [GeoVaultColorTokens.Error]; disable with [enabled] = `false`. When disabled,
 * [onDisabledClick] runs on tap if provided (e.g. explain why the action is unavailable).
 */
data class GeoVaultActionSheetOption(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onDisabledClick: (() -> Unit)? = null,
)

/**
 * "Title + vertical list of actions" dialog — the primitive for row-level menus ("What do
 * you want to do with this file?"). Tapping a row runs its [GeoVaultActionSheetOption.onClick];
 * the caller is responsible for dismissing via [onDismissRequest] inside that callback if desired.
 */
@Composable
fun GeoVaultActionSheetDialog(
    title: String,
    options: List<GeoVaultActionSheetOption>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = geoVaultDialogSurfaceColor(),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
                    color = geoVaultDialogTitleColor(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                options.forEach { option ->
                    val tint = when {
                        !option.enabled -> geoVaultInputPlaceholderColor()
                        option.destructive -> GeoVaultColorTokens.Error
                        else -> MaterialTheme.colors.onSurface
                    }
                    val rowClick = when {
                        option.enabled -> option.onClick
                        else -> option.onDisabledClick
                    }
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.body1,
                        color = tint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { m ->
                                if (rowClick != null) m.clickable(onClick = rowClick) else m
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

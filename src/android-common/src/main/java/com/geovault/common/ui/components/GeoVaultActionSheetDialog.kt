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

/**
 * One tappable row inside a [GeoVaultActionSheetDialog]. Set [destructive] to `true` to tint
 * the label with [GeoVaultColorTokens.Error]; disable with [enabled] = `false`.
 */
data class GeoVaultActionSheetOption(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
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
            color = GeoVaultColorTokens.Surface,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
                    color = GeoVaultColorTokens.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                options.forEach { option ->
                    val tint = when {
                        !option.enabled -> GeoVaultColorTokens.TextSecondary
                        option.destructive -> GeoVaultColorTokens.Error
                        else -> GeoVaultColorTokens.TextPrimary
                    }
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.body1,
                        color = tint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { m ->
                                if (option.enabled) m.clickable(onClick = option.onClick) else m
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

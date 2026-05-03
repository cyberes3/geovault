package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.LocalContentColor
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor

object GeoVaultInfoDialogDefaults {
    @Composable
    fun titleTextStyle() =
        MaterialTheme.typography.subtitle1.copy(
            color = GeoVaultColorTokens.MainBlue,
            fontWeight = FontWeight.Bold,
        )

    @Composable
    fun bodyTextStyle() =
        MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onSurface)

    @Composable
    fun sectionHeadingTextStyle() =
        MaterialTheme.typography.subtitle2.copy(
            color = MaterialTheme.colors.onSurface,
            fontWeight = FontWeight.Bold,
        )

    @Composable
    fun closeButtonColor() =
        if (MaterialTheme.colors.isLight) {
            GeoVaultColorTokens.MainBlue
        } else {
            GeoVaultColorTokens.Gray300
        }
}

@Composable
fun GeoVaultInfoDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    closeButtonText: String = "Close",
    content: @Composable ColumnScope.() -> Unit
) {
    val titleTextStyle = GeoVaultInfoDialogDefaults.titleTextStyle()
    val bodyTextStyle = GeoVaultInfoDialogDefaults.bodyTextStyle()
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        backgroundColor = geoVaultDialogSurfaceColor(),
        title = {
            Text(
                text = title,
                style = titleTextStyle,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colors.onSurface,
                    LocalTextStyle provides bodyTextStyle,
                ) {
                    content()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(
                    text = closeButtonText,
                    color = GeoVaultInfoDialogDefaults.closeButtonColor()
                )
            }
        }
    )
}

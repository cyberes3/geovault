package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.window.DialogProperties
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import com.geovault.common.ui.theme.geoVaultDialogTitleColor

/**
 * Shared chrome for picker dialogs: platform-width [Dialog], 12dp surface, title, body.
 */
@Composable
fun GeoVaultPickerDialogShell(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    usePlatformDefaultWidth: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = usePlatformDefaultWidth),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = geoVaultDialogSurfaceColor(),
            elevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(contentPadding)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1.copy(fontWeight = FontWeight.Bold),
                    color = geoVaultDialogTitleColor(),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                content()
            }
        }
    }
}

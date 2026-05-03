package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.alpha
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultCardBorderColor

@Composable
fun GeoVaultToggleHelpCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    helpText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isLight = MaterialTheme.colors.isLight
    val borderColor = geoVaultCardBorderColor()
    val titleColor = if (isLight) GeoVaultColorTokens.ToggleTitle else GeoVaultColorTokens.Dark.ToggleTitle
    val helpColor = if (isLight) GeoVaultColorTokens.ToggleHelpText else GeoVaultColorTokens.Dark.ToggleHelpText
    Card(
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        backgroundColor = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, borderColor),
        elevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    color = titleColor,
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )
                GeoVaultSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            }
            if (helpText.isNotBlank()) {
                Text(
                    text = helpText,
                    color = helpColor,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.geovault.common.ui.theme.GeoVaultLayoutTokens

@Composable
fun GeoVaultFormSection(
    modifier: Modifier = Modifier,
    verticalGap: Dp = GeoVaultLayoutTokens.ItemGap,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalGap),
        content = content
    )
}

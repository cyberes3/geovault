package com.geovault.common.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.geoVaultHairlineDividerColor

@Composable
fun GeoVaultMapBottomActionPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(geoVaultHairlineDividerColor()),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            backgroundColor = MaterialTheme.colors.background,
            elevation = 0.dp,
        ) {
            Column(content = content)
        }
    }
}

package com.geovault.common.ui.components

import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import kotlin.math.abs
import com.geovault.common.ui.theme.GeoVaultColorTokens

data class GeoVaultTab<T>(
    val value: T,
    val label: String,
)

@Composable
fun <T> GeoVaultTabBar(
    tabs: List<GeoVaultTab<T>>,
    selectedTab: T,
    onTabSelected: (T) -> Unit,
    indicatorPage: Int? = null,
    indicatorOffsetFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = tabs.indexOfFirst { it.value == selectedTab }.coerceAtLeast(0)
    TabRow(
        selectedTabIndex = selectedIndex,
        backgroundColor = MaterialTheme.colors.surface,
        modifier = modifier,
        indicator = { tabPositions ->
            val page = indicatorPage?.coerceIn(0, tabPositions.lastIndex)
            if (page == null) {
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color = GeoVaultColorTokens.MainYellow,
                )
            } else {
                val fraction = indicatorOffsetFraction.coerceIn(-1f, 1f)
                val currentTab = tabPositions[page]
                val targetIndex = when {
                    fraction > 0f -> page + 1
                    fraction < 0f -> page - 1
                    else -> page
                }.coerceIn(0, tabPositions.lastIndex)
                val targetTab = tabPositions[targetIndex]
                val progress = abs(fraction)
                val indicatorLeft = lerp(currentTab.left, targetTab.left, progress)
                val indicatorWidth = lerp(currentTab.width, targetTab.width, progress)
                TabRowDefaults.Indicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset(x = indicatorLeft)
                        .width(indicatorWidth),
                    color = GeoVaultColorTokens.MainYellow,
                )
            }
        },
        divider = {
            Divider(color = GeoVaultColorTokens.PrimaryBlue, thickness = 2.dp)
        },
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab.value == selectedTab,
                onClick = { onTabSelected(tab.value) },
                selectedContentColor = GeoVaultColorTokens.TextPrimary,
                unselectedContentColor = GeoVaultColorTokens.TextPrimary,
                text = { Text(tab.label) },
            )
        }
    }
}

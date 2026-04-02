package com.geovault.common.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.geovault.common.ui.system.GeoVaultSystemBars
import com.geovault.common.ui.theme.GeoVaultColorTokens

@Immutable
data class GeoVaultBottomNavDestination(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val contentDescription: String = label,
)

@Composable
fun GeoVaultBottomNavScaffold(
    destinations: List<GeoVaultBottomNavDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (GeoVaultBottomNavDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (activeDestination: GeoVaultBottomNavDestination) -> Unit,
) {
    require(destinations.isNotEmpty()) { "GeoVaultBottomNavScaffold requires at least one destination." }
    val destinationIds = destinations.map { it.id }
    require(destinationIds.distinct().size == destinationIds.size) {
        "GeoVaultBottomNavScaffold destination IDs must be unique."
    }
    val activeDestination = destinations.firstOrNull { it.id == selectedDestinationId } ?: destinations.first()

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            content(activeDestination)
        }
        GeoVaultBottomNavRow(
            destinations = destinations,
            selectedDestinationId = activeDestination.id,
            onDestinationSelected = onDestinationSelected,
        )
    }

    val activity = LocalContext.current as? ComponentActivity
    SideEffect {
        if (activity != null) {
            val lighterNavBarBlue = ColorUtils.blendARGB(
                GeoVaultColorTokens.PRIMARY_BLUE_INT,
                0xFFFFFFFF.toInt(),
                0.12f,
            )
            // Keep the Android system navigation bar blue when a bottom nav row is present.
            // This runs after child content composition so tab screen chrome can't override it.
            GeoVaultSystemBars.setNavigationBarBackground(
                activity = activity,
                navigationBarColor = lighterNavBarBlue,
                useDarkNavigationBarIcons = false,
            )
        }
    }
}

@Composable
fun GeoVaultBottomNavRow(
    destinations: List<GeoVaultBottomNavDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (GeoVaultBottomNavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GeoVaultColorTokens.PrimaryBlue)
            // Do not use navigationBarsPadding here: GeoVaultTheme already applies it at the root.
            // A second inset shrinks the tab content area and squishes screens (e.g. map) above the bar.
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            val isSelected = destination.id == selectedDestinationId
            val tint = when {
                !destination.enabled -> Color.White.copy(alpha = 0.5f)
                isSelected -> GeoVaultColorTokens.MainYellow
                else -> Color.White
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = destination.enabled) {
                        onDestinationSelected(destination)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.contentDescription,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = destination.label,
                    color = tint,
                    style = MaterialTheme.typography.caption.copy(
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

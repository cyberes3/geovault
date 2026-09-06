package com.geovault.common.maps.ui.scale

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.util.MeasurementSystem
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap

object GeoVaultMapScaleBarDefaults {
    val MaxWidth: Dp = 100.dp
    val EdgePadding: Dp = 16.dp
    val DrawerGap: Dp = 8.dp
}

@Composable
fun GeoVaultMapScaleBar(
    map: GeoVaultBaseMap,
    modifier: Modifier = Modifier,
    maxWidth: Dp = GeoVaultMapScaleBarDefaults.MaxWidth,
) {
    val attachmentVersion by map.mapAttachmentVersion.collectAsState()
    val measurementSystem = MeasurementSystem.fromContext(LocalContext.current)
    val density = LocalDensity.current
    val maxWidthPx = remember(density, maxWidth) {
        with(density) { maxWidth.toPx().roundToInt() }
    }
    var measurement by remember(map) { mutableStateOf<GeoVaultMapScaleBarMeasurement?>(null) }

    DisposableEffect(map, attachmentVersion, maxWidthPx, measurementSystem) {
        val mapLibreMap = map.maplibreMap
        if (mapLibreMap == null) {
            measurement = null
            return@DisposableEffect onDispose { }
        }

        fun updateMeasurement() {
            val latitude = mapLibreMap.cameraPosition.target?.latitude ?: return
            measurement = GeoVaultMapScaleBarCalculator.calculate(
                metersPerPixel = mapLibreMap.projection.getMetersPerPixelAtLatitude(latitude),
                maxWidthPx = maxWidthPx,
                system = measurementSystem,
            )
        }

        val moveListener = MapLibreMap.OnCameraMoveListener { updateMeasurement() }
        val idleListener = MapLibreMap.OnCameraIdleListener { updateMeasurement() }
        mapLibreMap.addOnCameraMoveListener(moveListener)
        mapLibreMap.addOnCameraIdleListener(idleListener)
        updateMeasurement()

        onDispose {
            mapLibreMap.removeOnCameraMoveListener(moveListener)
            mapLibreMap.removeOnCameraIdleListener(idleListener)
        }
    }

    GeoVaultMapScaleBarSurface(
        measurement = measurement,
        maxWidth = maxWidth,
        modifier = modifier,
    )
}

@Composable
private fun GeoVaultMapScaleBarSurface(
    measurement: GeoVaultMapScaleBarMeasurement?,
    maxWidth: Dp,
    modifier: Modifier,
) {
    if (measurement == null) return

    Column(
        modifier = modifier
            .semantics { contentDescription = "Map scale ${measurement.label}" }
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Text(
            text = measurement.label,
            color = GeoVaultColorTokens.MainBlue,
            style = MaterialTheme.typography.caption,
        )
        val lineColor = GeoVaultColorTokens.MainBlue
        Canvas(
            modifier = Modifier.size(width = maxWidth, height = 8.dp),
        ) {
            val strokeWidth = 2.dp.toPx()
            val y = size.height - strokeWidth / 2f
            val scaleWidth = (size.width * measurement.widthFraction).coerceAtLeast(strokeWidth)
            val startX = strokeWidth / 2f
            val endX = scaleWidth - strokeWidth / 2f
            drawLine(
                color = lineColor,
                start = Offset(x = startX, y = y),
                end = Offset(x = endX, y = y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square,
            )
            drawLine(
                color = lineColor,
                start = Offset(x = startX, y = 0f),
                end = Offset(x = startX, y = y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square,
            )
            drawLine(
                color = lineColor,
                start = Offset(x = endX, y = 0f),
                end = Offset(x = endX, y = y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square,
            )
        }
    }
}

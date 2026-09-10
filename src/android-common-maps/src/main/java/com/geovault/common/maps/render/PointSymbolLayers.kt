package com.geovault.common.maps.render

import androidx.compose.ui.graphics.toArgb
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions

/**
 * Icon + optional text pair for one GeoJSON point source.
 *
 * [inPaintOrder] is bottom → top: markers first, names last, so labels cover points.
 */
data class PointSymbolLayers<T>(
    val icon: T,
    val label: T?,
) {
    fun inPaintOrder(): List<T> = listOfNotNull(icon, label)
}

object PointSymbolLayerFactory {
    const val CLUSTER_PROPERTY: String = "cluster"

    fun create(
        sourceId: String,
        iconLayerId: String,
        labelLayerId: String,
        config: GeoJsonRenderConfig,
        textAllowOverlap: Boolean,
        filterUnclustered: Boolean,
    ): PointSymbolLayers<SymbolLayer> {
        val iconSizeExpr = Expression.coalesce(
            Expression.get("iconSize"),
            Expression.literal(config.defaultIconSize),
        )
        val iconRotateExpr = Expression.coalesce(
            Expression.toNumber(Expression.get("iconRotationDegrees")),
            Expression.literal(0.0),
        )
        val iconLayer = SymbolLayer(iconLayerId, sourceId).withProperties(
            PropertyFactory.iconImage(Expression.get("iconImageId")),
            PropertyFactory.iconSize(iconSizeExpr),
            PropertyFactory.iconAnchor(config.defaultIconAnchor),
            PropertyFactory.iconRotate(iconRotateExpr),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).withOptionalUnclusteredFilter(filterUnclustered)
        if (config.disablePointSymbolFade) {
            val instant = TransitionOptions(0L, 0L)
            iconLayer.setIconOpacityTransition(instant)
        }
        val labelLayer = if (config.showPointTextLabels) {
            val labelPointProperties: Array<PropertyValue<*>> = buildList {
                add(PropertyFactory.iconImage(Expression.get("iconImageId")))
                add(PropertyFactory.iconOpacity(Expression.literal(0.0)))
                add(PropertyFactory.iconSize(iconSizeExpr))
                add(PropertyFactory.iconAnchor(config.defaultIconAnchor))
                add(PropertyFactory.iconRotate(iconRotateExpr))
                add(PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT))
                add(PropertyFactory.iconAllowOverlap(true))
                add(PropertyFactory.iconIgnorePlacement(true))
                add(PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT))
                add(PropertyFactory.textField(Expression.get("title")))
                add(
                    PropertyFactory.textSize(
                        Expression.coalesce(
                            Expression.get("labelTextSize"),
                            Expression.literal(config.defaultLabelTextSize),
                        ),
                    ),
                )
                add(
                    PropertyFactory.textColor(
                        Expression.coalesce(
                            Expression.get("labelTextColorHex"),
                            Expression.literal(config.defaultLabelTextColorHex),
                        ),
                    ),
                )
                if (config.pointLabelHaloWidth > 0f) {
                    add(PropertyFactory.textHaloWidth(config.pointLabelHaloWidth))
                    add(
                        PropertyFactory.textHaloColor(
                            config.pointLabelHaloColorArgb
                                ?: GeoVaultColorTokens.MapLineworkHalo.toArgb(),
                        ),
                    )
                }
                add(PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP))
                add(PropertyFactory.textOffset(arrayOf(0f, config.pointLabelTextOffsetYEm)))
                add(PropertyFactory.textAllowOverlap(textAllowOverlap))
                add(PropertyFactory.textIgnorePlacement(false))
            }.toTypedArray()
            SymbolLayer(labelLayerId, sourceId).withProperties(
                *labelPointProperties,
            ).withOptionalUnclusteredFilter(filterUnclustered).also { layer ->
                if (config.disablePointSymbolFade) {
                    val instant = TransitionOptions(0L, 0L)
                    layer.setIconOpacityTransition(instant)
                    layer.setTextOpacityTransition(instant)
                }
            }
        } else {
            null
        }
        return PointSymbolLayers(icon = iconLayer, label = labelLayer)
    }

    private fun SymbolLayer.withOptionalUnclusteredFilter(filterUnclustered: Boolean): SymbolLayer =
        if (!filterUnclustered) {
            this
        } else {
            withFilter(Expression.neq(Expression.get(CLUSTER_PROPERTY), true))
        }
}

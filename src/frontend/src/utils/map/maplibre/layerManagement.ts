/**
 * MapLibre layer management utilities
 */

import type { Map as MapLibreMap, AddLayerObject, FilterSpecification } from 'maplibre-gl'
import {
  getPointLayerConfig,
  getReplacementPointLayerConfig,
  getPointIconLayerConfig,
  getLineLayerConfig,
  getPolygonLayerConfig,
  getPolygonOutlineLayerConfig
} from './featureStyles.js'
import type { LayerConfig } from './featureStyles.js'
import type { MapLibreExpression } from './featureStyling.js'

function addConfigLayer(map: MapLibreMap, config: LayerConfig, before?: string): void {
  map.addLayer(config as unknown as AddLayerObject, before)
}

function setConfigFilter(map: MapLibreMap, layerId: string, filter: MapLibreExpression | undefined): void {
  map.setFilter(layerId, (filter ?? null) as FilterSpecification | null)
}

/**
 * Ensure all required layers exist on the map with correct ordering:
 * Bottom: polygons (fill + outlines)
 * Middle: lines
 * Top: points
 */
export function ensureLayersExist(map: MapLibreMap | null | undefined, showAllLabels = true): void {
  if (!map?.getSource('geojson-data')) return

  // Desired order from bottom to top: base tiles, polygons, polygon-outlines, lines, points, point-icons, labels
  // We'll add layers in this order, using beforeId only if the target layer exists

  // 1. Polygons fill layer (bottom of feature layers, but after base tiles) - add first
  const polygonConfig = getPolygonLayerConfig()
  if (!map.getLayer('polygons')) {
    // If there's a base tile layer, don't use beforeId - we want polygons after it
    // If no base tile layer, just add normally
    addConfigLayer(map, polygonConfig)
  } else {
    setConfigFilter(map, 'polygons', polygonConfig.filter)
  }

  // 2. Polygon outlines layer - add after polygons
  const polygonOutlineConfig = getPolygonOutlineLayerConfig()
  if (!map.getLayer('polygon-outlines')) {
    // Add after polygons if it exists, otherwise just add
    if (map.getLayer('polygons')) {
      addConfigLayer(map, polygonOutlineConfig, 'polygons')
    } else {
      addConfigLayer(map, polygonOutlineConfig)
    }
  } else {
    setConfigFilter(map, 'polygon-outlines', polygonOutlineConfig.filter)
    map.setLayoutProperty('polygon-outlines', 'line-cap', polygonOutlineConfig.layout?.['line-cap'])
    map.setLayoutProperty('polygon-outlines', 'line-join', polygonOutlineConfig.layout?.['line-join'])
  }

  // 3. Lines layer - add after polygon-outlines
  const lineConfig = getLineLayerConfig()
  if (!map.getLayer('lines')) {
    if (map.getLayer('polygon-outlines')) {
      addConfigLayer(map, lineConfig, 'polygon-outlines')
    } else if (map.getLayer('polygons')) {
      addConfigLayer(map, lineConfig, 'polygons')
    } else {
      addConfigLayer(map, lineConfig)
    }
  } else {
    setConfigFilter(map, 'lines', lineConfig.filter)
    map.setLayoutProperty('lines', 'line-cap', lineConfig.layout?.['line-cap'])
    map.setLayoutProperty('lines', 'line-join', lineConfig.layout?.['line-join'])
  }

  // 4b. Points layer (for features without icons or at low zoom) - add after lines
  const pointConfig = getPointLayerConfig()
  const circleFilter: FilterSpecification = ['all',
    ['==', ['geometry-type'], 'Point'],
    ['!', ['has', '_on_border']],
    ['!', ['has', '_isLabelPoint']], // Exclude label points
    ['!', ['has', '_isSmallFeatureReplacement']], // Exclude replacement points (separate layer)
    ['!', ['has', '_icon-id']] // Only show features without icons
  ]
  if (!map.getLayer('points')) {
    // Filter is already set in getPointLayerConfig, but we need to add the _icon-id check
    pointConfig.filter = circleFilter
    if (map.getLayer('lines')) {
      addConfigLayer(map, pointConfig, 'lines')
    } else if (map.getLayer('polygon-outlines')) {
      addConfigLayer(map, pointConfig, 'polygon-outlines')
    } else {
      addConfigLayer(map, pointConfig)
    }
    // Ensure stroke properties are set immediately after layer creation
    map.setPaintProperty('points', 'circle-stroke-width', 1)
    map.setPaintProperty('points', 'circle-stroke-color', '#000000')
    map.setPaintProperty('points', 'circle-stroke-opacity', 1)
  } else {
    setConfigFilter(map, 'points', circleFilter)
    // Update paint properties to ensure border is applied
    map.setPaintProperty('points', 'circle-stroke-width', 1)
    map.setPaintProperty('points', 'circle-stroke-color', '#000000')
    map.setPaintProperty('points', 'circle-stroke-opacity', 1)
  }

  // 4c. Replacement points layer (for small polygons/lines) - add after regular points
  const replacementPointConfig = getReplacementPointLayerConfig()
  if (!map.getLayer('replacement-points')) {
    // Filter is already set in getReplacementPointLayerConfig
    if (map.getLayer('points')) {
      addConfigLayer(map, replacementPointConfig, 'points')
    } else if (map.getLayer('lines')) {
      addConfigLayer(map, replacementPointConfig, 'lines')
    } else {
      addConfigLayer(map, replacementPointConfig)
    }
    // Ensure stroke properties are set immediately after layer creation
    map.setPaintProperty('replacement-points', 'circle-stroke-width', 1)
    map.setPaintProperty('replacement-points', 'circle-stroke-color', '#000000')
    map.setPaintProperty('replacement-points', 'circle-stroke-opacity', 1)
  } else {
    // Filter is already correct in getReplacementPointLayerConfig
    setConfigFilter(map, 'replacement-points', replacementPointConfig.filter)
    // Update paint properties to ensure border is applied
    // Only update stroke properties to avoid issues with expressions
    map.setPaintProperty('replacement-points', 'circle-stroke-width', 1)
    map.setPaintProperty('replacement-points', 'circle-stroke-color', '#000000')
    map.setPaintProperty('replacement-points', 'circle-stroke-opacity', 1)
  }

  // 4a. Point icons layer (for features with icons) - add after replacement-points
  const pointIconConfig = getPointIconLayerConfig()
  if (!map.getLayer('point-icons')) {
    // Filter is already set in getPointIconLayerConfig
    if (map.getLayer('replacement-points')) {
      addConfigLayer(map, pointIconConfig, 'replacement-points')
    } else if (map.getLayer('points')) {
      addConfigLayer(map, pointIconConfig, 'points')
    } else if (map.getLayer('lines')) {
      addConfigLayer(map, pointIconConfig, 'lines')
    } else {
      addConfigLayer(map, pointIconConfig)
    }
  } else {
    // Filter is already correct in getPointIconLayerConfig
    setConfigFilter(map, 'point-icons', pointIconConfig.filter)
  }

  // Labels are now handled by HTML markers (labelMarkers.ts), not symbol layers

  // Force correct layer ordering after all layers are set up
  enforceLayerOrder(map, showAllLabels)
}

/**
 * Force correct layer ordering
 * Desired order (bottom to top): base tiles/style layers, polygons, polygon-outlines, lines, points, replacement-points, point-icons, labels
 * MapLibre renders layers in order, with later layers on top
 */
function enforceLayerOrder(map: MapLibreMap, showAllLabels = true): void {
  if (!map.getSource('geojson-data')) return
  void showAllLabels

  const style = map.getStyle()

  // Base tile layer IDs (should be at the bottom)
  const baseTileLayerIds = ['osm-layer', 'tile-layer', 'raster-layer']

  // Feature layer IDs (should be above base tiles and style layers)
  const featureLayerIds = ['polygons', 'polygon-outlines', 'lines', 'points', 'replacement-points', 'point-icons']

  // First, ensure any base tile layers are positioned at the very bottom
  baseTileLayerIds.forEach(baseTileId => {
    if (map.getLayer(baseTileId)) {
      // Move base tile layer to the very beginning (before all layers)
      const firstLayer = style.layers[0]
      if (firstLayer.id !== baseTileId) {
        map.moveLayer(baseTileId, firstLayer.id)
      }
    }
  })

  // Get updated style after moving base tiles
  const updatedStyle = map.getStyle()

  // Ensure feature layers are positioned AFTER all style-based layers (MapTiler, etc.)
  // Find if there are any non-feature layers (style layers from MapTiler, etc.)
  const nonFeatureLayers = updatedStyle.layers.filter(l => {
    // Exclude our feature layers and base tile layers
    return !featureLayerIds.includes(l.id) && !baseTileLayerIds.includes(l.id)
  })

  const activeStyle = nonFeatureLayers.length > 0 ? (() => {
    // Move all our feature layers to the end (on top of everything)
    featureLayerIds.forEach(layerId => {
      if (map.getLayer(layerId)) {
        map.moveLayer(layerId)
      }
    })
    // Refresh style reference after moving
    return map.getStyle()
  })() : updatedStyle

  // Now ensure proper ordering among our feature layers
  const layerIndices: Record<string, number> = {}
  featureLayerIds.forEach(id => {
    if (map.getLayer(id)) {
      const index = activeStyle.layers.findIndex(l => l.id === id)
      if (index >= 0) {
        layerIndices[id] = index
      }
    }
  })

  // Work from top to bottom, ensuring each feature layer is after the previous one
  for (let i = featureLayerIds.length - 1; i > 0; i--) {
    const topLayerId = featureLayerIds[i]
    const bottomLayerId = featureLayerIds[i - 1]

    if (!map.getLayer(topLayerId) || !map.getLayer(bottomLayerId)) continue

    const topIndex = layerIndices[topLayerId]
    const bottomIndex = layerIndices[bottomLayerId]

    // If top layer is not after bottom layer, fix it
    if (topIndex <= bottomIndex) {
      // Find what comes after bottomLayerId
      const bottomLayerIndex = activeStyle.layers.findIndex(l => l.id === bottomLayerId)
      if (bottomLayerIndex >= 0 && bottomLayerIndex < activeStyle.layers.length - 1) {
        const nextLayer = activeStyle.layers[bottomLayerIndex + 1]
        if (nextLayer.id !== topLayerId) {
          // Move topLayer to be right after bottomLayer
          map.moveLayer(topLayerId, nextLayer.id)
          // Update index
          layerIndices[topLayerId] = bottomLayerIndex + 1
        }
      } else {
        // bottomLayer is last, move topLayer to end
        map.moveLayer(topLayerId)
      }
    }
  }
}

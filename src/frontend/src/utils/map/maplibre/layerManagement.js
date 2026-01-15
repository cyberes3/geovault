/**
 * MapLibre layer management utilities
 */

import {
  getPointLayerConfig,
  getReplacementPointLayerConfig,
  getPointIconLayerConfig,
  getLineLayerConfig,
  getPolygonLayerConfig,
  getPolygonOutlineLayerConfig
} from './featureStyles.js'
import { MAX_ZOOM_LEVEL } from './mapInitialization.js'

/**
 * Ensure all required layers exist on the map with correct ordering:
 * Bottom: polygons (fill + outlines)
 * Middle: lines
 * Top: points
 * @param {Object} map - MapLibre map instance
 * @param {boolean} showAllLabels - Whether to show labels
 */
export function ensureLayersExist(map, showAllLabels = true) {
  if (!map || !map.getSource('geojson-data')) return

  // Desired order from bottom to top: base tiles, polygons, polygon-outlines, lines, points, point-icons, labels
  // We'll add layers in this order, using beforeId only if the target layer exists
  
  // Find if there's a base tile layer that we should position features after
  const style = map.getStyle()
  const baseTileLayerIds = ['raster-layer', 'tile-layer', 'osm-layer']
  let baseTileLayer = null
  if (style && style.layers) {
    for (const baseTileId of baseTileLayerIds) {
      if (map.getLayer(baseTileId)) {
        baseTileLayer = baseTileId
        break
      }
    }
  }

  // 1. Polygons fill layer (bottom of feature layers, but after base tiles) - add first
  const polygonConfig = getPolygonLayerConfig()
  if (!map.getLayer('polygons')) {
    // If there's a base tile layer, don't use beforeId - we want polygons after it
    // If no base tile layer, just add normally
    map.addLayer(polygonConfig)
  } else {
    map.setFilter('polygons', polygonConfig.filter)
  }

  // 2. Polygon outlines layer - add after polygons
  const polygonOutlineConfig = getPolygonOutlineLayerConfig()
  if (!map.getLayer('polygon-outlines')) {
    // Add after polygons if it exists, otherwise just add
    if (map.getLayer('polygons')) {
      map.addLayer(polygonOutlineConfig, 'polygons')
    } else {
      map.addLayer(polygonOutlineConfig)
    }
  } else {
    map.setFilter('polygon-outlines', polygonOutlineConfig.filter)
    map.setLayoutProperty('polygon-outlines', 'line-cap', polygonOutlineConfig.layout['line-cap'])
    map.setLayoutProperty('polygon-outlines', 'line-join', polygonOutlineConfig.layout['line-join'])
  }

  // 3. Lines layer - add after polygon-outlines
  const lineConfig = getLineLayerConfig()
  if (!map.getLayer('lines')) {
    if (map.getLayer('polygon-outlines')) {
      map.addLayer(lineConfig, 'polygon-outlines')
    } else if (map.getLayer('polygons')) {
      map.addLayer(lineConfig, 'polygons')
    } else {
      map.addLayer(lineConfig)
    }
  } else {
    map.setFilter('lines', lineConfig.filter)
    map.setLayoutProperty('lines', 'line-cap', lineConfig.layout['line-cap'])
    map.setLayoutProperty('lines', 'line-join', lineConfig.layout['line-join'])
  }

  // 4b. Points layer (for features without icons or at low zoom) - add after lines
  const pointConfig = getPointLayerConfig()
  if (!map.getLayer('points')) {
    // Filter is already set in getPointLayerConfig, but we need to add the _icon-id check
    const circleFilter = ['all', 
      ['==', ['geometry-type'], 'Point'], 
      ['!', ['has', '_on_border']], 
      ['!', ['has', '_isLabelPoint']], // Exclude label points
      ['!', ['has', '_isSmallFeatureReplacement']], // Exclude replacement points (separate layer)
      ['!', ['has', '_icon-id']] // Only show features without icons
    ]
    pointConfig.filter = circleFilter
    if (map.getLayer('lines')) {
      map.addLayer(pointConfig, 'lines')
    } else if (map.getLayer('polygon-outlines')) {
      map.addLayer(pointConfig, 'polygon-outlines')
    } else {
      map.addLayer(pointConfig)
    }
  } else {
    const circleFilter = ['all', 
      ['==', ['geometry-type'], 'Point'], 
      ['!', ['has', '_on_border']], 
      ['!', ['has', '_isLabelPoint']], // Exclude label points
      ['!', ['has', '_isSmallFeatureReplacement']], // Exclude replacement points (separate layer)
      ['!', ['has', '_icon-id']] // Only show features without icons
    ]
    map.setFilter('points', circleFilter)
  }

  // 4c. Replacement points layer (for small polygons/lines) - add after regular points
  const replacementPointConfig = getReplacementPointLayerConfig()
  if (!map.getLayer('replacement-points')) {
    // Filter is already set in getReplacementPointLayerConfig
    if (map.getLayer('points')) {
      map.addLayer(replacementPointConfig, 'points')
    } else if (map.getLayer('lines')) {
      map.addLayer(replacementPointConfig, 'lines')
    } else {
      map.addLayer(replacementPointConfig)
    }
  } else {
    // Filter is already correct in getReplacementPointLayerConfig
    map.setFilter('replacement-points', replacementPointConfig.filter)
  }

  // 4a. Point icons layer (for features with icons) - add after replacement-points
  const pointIconConfig = getPointIconLayerConfig()
  if (!map.getLayer('point-icons')) {
    // Filter is already set in getPointIconLayerConfig
    if (map.getLayer('replacement-points')) {
      map.addLayer(pointIconConfig, 'replacement-points')
    } else if (map.getLayer('points')) {
      map.addLayer(pointIconConfig, 'points')
    } else if (map.getLayer('lines')) {
      map.addLayer(pointIconConfig, 'lines')
    } else {
      map.addLayer(pointIconConfig)
    }
  } else {
    // Filter is already correct in getPointIconLayerConfig
    const iconFilter = pointIconConfig.filter
    map.setFilter('point-icons', iconFilter)
  }

  // Labels are now handled by HTML markers (labelMarkers.js), not symbol layers

  // Force correct layer ordering after all layers are set up
  enforceLayerOrder(map, showAllLabels)
  
  // Debug: log final layer order
  const finalStyle = map.getStyle()
  if (finalStyle && finalStyle.layers) {
    const ourLayers = ['polygons', 'polygon-outlines', 'lines', 'points', 'replacement-points', 'point-icons']
      .filter(id => map.getLayer(id))
      .map(id => {
        const index = finalStyle.layers.findIndex(l => l.id === id)
        return { id, index }
      })
      .sort((a, b) => a.index - b.index)
  }
}

/**
 * Ensure a layer renders after another layer
 * @param {Object} map - MapLibre map instance
 * @param {string} beforeLayerId - Layer that should render first (below)
 * @param {string} afterLayerId - Layer that should render after (on top)
 */
function ensureLayerOrder(map, beforeLayerId, afterLayerId) {
  if (!map.getLayer(beforeLayerId) || !map.getLayer(afterLayerId)) return

  const style = map.getStyle()
  if (!style || !style.layers) return

  const layers = style.layers
  const beforeIndex = layers.findIndex(l => l.id === beforeLayerId)
  const afterIndex = layers.findIndex(l => l.id === afterLayerId)

  // If afterLayer is already after beforeLayer, we're good
  if (beforeIndex >= 0 && afterIndex > beforeIndex) return

  // Need to move afterLayer to be after beforeLayer
  // Find what comes after beforeLayer
  if (beforeIndex >= 0 && beforeIndex < layers.length - 1) {
    const nextLayer = layers[beforeIndex + 1]
    // Move afterLayer to be right after beforeLayer (before nextLayer)
    if (nextLayer && nextLayer.id !== afterLayerId) {
      map.moveLayer(afterLayerId, nextLayer.id)
    } else if (nextLayer && nextLayer.id === afterLayerId) {
      // Already in correct position
      return
    } else {
      // beforeLayer is last, just move afterLayer to end
      // Get the last layer
      const lastLayer = layers[layers.length - 1]
      if (lastLayer && lastLayer.id !== afterLayerId) {
        // Move after beforeLayer by moving to end, then we'll fix it
        map.moveLayer(afterLayerId)
      }
    }
  } else if (beforeIndex >= 0) {
    // beforeLayer is the last layer, move afterLayer to end
    map.moveLayer(afterLayerId)
  }
}

/**
 * Force correct layer ordering
 * Desired order (bottom to top): base tiles/style layers, polygons, polygon-outlines, lines, points, replacement-points, point-icons, labels
 * MapLibre renders layers in order, with later layers on top
 * @param {Object} map - MapLibre map instance
 * @param {boolean} showAllLabels - Whether to show labels
 */
function enforceLayerOrder(map, showAllLabels = true) {
  if (!map || !map.getSource('geojson-data')) return

  const style = map.getStyle()
  if (!style || !style.layers) return

  // Base tile layer IDs (should be at the bottom)
  const baseTileLayerIds = ['osm-layer', 'tile-layer', 'raster-layer']
  
  // Feature layer IDs (should be above base tiles and style layers)
  const featureLayerIds = ['polygons', 'polygon-outlines', 'lines', 'points', 'replacement-points', 'point-icons']
  
  // First, ensure any base tile layers are positioned at the very bottom
  baseTileLayerIds.forEach(baseTileId => {
    if (map.getLayer(baseTileId)) {
      // Move base tile layer to the very beginning (before all layers)
      const firstLayer = style.layers[0]
      if (firstLayer && firstLayer.id !== baseTileId) {
        map.moveLayer(baseTileId, firstLayer.id)
      }
    }
  })
  
  // Get updated style after moving base tiles
  const updatedStyle = map.getStyle()
  if (!updatedStyle || !updatedStyle.layers) return
  
  // Ensure feature layers are positioned AFTER all style-based layers (MapTiler, etc.)
  // Find if there are any non-feature layers (style layers from MapTiler, etc.)
  const nonFeatureLayers = updatedStyle.layers.filter(l => {
    // Exclude our feature layers and base tile layers
    return !featureLayerIds.includes(l.id) && !baseTileLayerIds.includes(l.id)
  })
  
  // If there are style layers (e.g., from MapTiler), ensure our features are on top
  if (nonFeatureLayers.length > 0) {
    // Move all our feature layers to the end (on top of everything)
    featureLayerIds.forEach(layerId => {
      if (map.getLayer(layerId)) {
        map.moveLayer(layerId)
      }
    })
    
    // Refresh style reference after moving
    const refreshedStyle = map.getStyle()
    if (!refreshedStyle || !refreshedStyle.layers) return
    
    // Now ensure proper ordering among our feature layers
    const layerIndices = {}
    featureLayerIds.forEach(id => {
      if (map.getLayer(id)) {
        const index = refreshedStyle.layers.findIndex(l => l.id === id)
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
        const bottomLayerIndex = refreshedStyle.layers.findIndex(l => l.id === bottomLayerId)
        if (bottomLayerIndex >= 0 && bottomLayerIndex < refreshedStyle.layers.length - 1) {
          const nextLayer = refreshedStyle.layers[bottomLayerIndex + 1]
          if (nextLayer && nextLayer.id !== topLayerId) {
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
  } else {
    // No style layers, just ensure ordering among feature layers
    const layerIndices = {}
    featureLayerIds.forEach(id => {
      if (map.getLayer(id)) {
        const index = updatedStyle.layers.findIndex(l => l.id === id)
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
        const bottomLayerIndex = updatedStyle.layers.findIndex(l => l.id === bottomLayerId)
        if (bottomLayerIndex >= 0 && bottomLayerIndex < updatedStyle.layers.length - 1) {
          const nextLayer = updatedStyle.layers[bottomLayerIndex + 1]
          if (nextLayer && nextLayer.id !== topLayerId) {
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
}

/**
 * Update map layer source (tile layer switching)
 * @param {Object} map - MapLibre map instance
 * @param {string} layerId - Layer ID to update
 * @param {Object} tileSource - Tile source configuration
 */
export function updateMapLayerSource(map, layerId, tileSource) {
  if (!map || !tileSource) return

  const style = map.getStyle()
  if (!style) return

  // Remove existing source if it exists
  if (map.getSource('tile-source')) {
    map.removeLayer('tile-layer')
    map.removeSource('tile-source')
  }

  // Add new source
  // Support both direct attribution and client_config.attribution
  const attribution = tileSource.attribution || tileSource.client_config?.attribution || ''
  map.addSource('tile-source', {
    type: 'raster',
    tiles: tileSource.tiles,
    tileSize: tileSource.tileSize || 256,
    attribution: attribution
  })

  // Calculate maxzoom - ensure it's at least MAX_ZOOM_LEVEL + 1 so tiles render at max zoom
  // Note: MapLibre's maxzoom is exclusive, so maxzoom: 17 means visible only at zoom < 17
  // To render at zoom 17, we need maxzoom: 18
  const sourceMaxZoom = tileSource.maxzoom || MAX_ZOOM_LEVEL
  const layerMaxZoom = Math.max(sourceMaxZoom, MAX_ZOOM_LEVEL + 1)

  // Add or update layer
  if (!map.getLayer('tile-layer')) {
    map.addLayer({
      id: 'tile-layer',
      type: 'raster',
      source: 'tile-source',
      minzoom: tileSource.minzoom || 0,
      maxzoom: layerMaxZoom
    })
  } else {
    // Update existing layer's maxzoom to ensure it renders at max zoom
    const currentMinZoom = map.getLayer('tile-layer').minzoom || 0
    map.setLayerZoomRange('tile-layer', currentMinZoom, layerMaxZoom)
  }

  // Move tile layer to bottom (below all other layers)
  const layers = style.layers
  if (layers && layers.length > 0) {
    map.moveLayer('tile-layer', layers[0].id)
  }
}


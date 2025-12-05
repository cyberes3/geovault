/**
 * MapLibre layer management utilities
 */

import {
  getPointLayerConfig,
  getPointIconLayerConfig,
  getLineLayerConfig,
  getPolygonLayerConfig,
  getPolygonOutlineLayerConfig
} from './featureStyles.js'

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

  // Desired order from bottom to top: polygons, polygon-outlines, lines, points, point-icons, labels
  // We'll add layers in this order, using beforeId only if the target layer exists

  // 1. Polygons fill layer (bottom) - add first
  const polygonConfig = getPolygonLayerConfig()
  if (!map.getLayer('polygons')) {
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
      ['!', ['has', '_icon-id']] // Only show features without icons
    ]
    map.setFilter('points', circleFilter)
  }

  // 4a. Point icons layer (for features with icons) - add after points
  const pointIconConfig = getPointIconLayerConfig()
  if (!map.getLayer('point-icons')) {
    // Filter is already set in getPointIconLayerConfig
    if (map.getLayer('points')) {
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
    const ourLayers = ['polygons', 'polygon-outlines', 'lines', 'points', 'point-icons']
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
 * Desired order (bottom to top): polygons, polygon-outlines, lines, points, labels
 * MapLibre renders layers in order, with later layers on top
 * @param {Object} map - MapLibre map instance
 * @param {boolean} showAllLabels - Whether to show labels
 */
function enforceLayerOrder(map, showAllLabels = true) {
  if (!map || !map.getSource('geojson-data')) return

  const style = map.getStyle()
  if (!style || !style.layers) return

  // Desired order from bottom to top (labels are now HTML markers, not layers)
  const desiredOrder = ['polygons', 'polygon-outlines', 'lines', 'points', 'point-icons']
  
  // Get current indices of our layers
  const layerIndices = {}
  desiredOrder.forEach(id => {
    if (map.getLayer(id)) {
      const index = style.layers.findIndex(l => l.id === id)
      if (index >= 0) {
        layerIndices[id] = index
      }
    }
  })

  // Work from top to bottom, ensuring each layer is after the previous one
  // moveLayer(layerId, beforeId) moves layerId to be before beforeId
  for (let i = desiredOrder.length - 1; i > 0; i--) {
    const topLayerId = desiredOrder[i]
    const bottomLayerId = desiredOrder[i - 1]
    
    if (!map.getLayer(topLayerId) || !map.getLayer(bottomLayerId)) continue

    const topIndex = layerIndices[topLayerId]
    const bottomIndex = layerIndices[bottomLayerId]

    // If top layer is not after bottom layer, fix it
    if (topIndex <= bottomIndex) {
      // Find what comes after bottomLayerId
      const bottomLayerIndex = style.layers.findIndex(l => l.id === bottomLayerId)
      if (bottomLayerIndex >= 0 && bottomLayerIndex < style.layers.length - 1) {
        const nextLayer = style.layers[bottomLayerIndex + 1]
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
  map.addSource('tile-source', {
    type: 'raster',
    tiles: tileSource.tiles,
    tileSize: tileSource.tileSize || 256,
    attribution: tileSource.attribution || ''
  })

  // Add or update layer
  if (!map.getLayer('tile-layer')) {
    map.addLayer({
      id: 'tile-layer',
      type: 'raster',
      source: 'tile-source',
      minzoom: tileSource.minzoom || 0,
      maxzoom: tileSource.maxzoom || 19
    })
  }

  // Move tile layer to bottom (below all other layers)
  const layers = style.layers
  if (layers && layers.length > 0) {
    map.moveLayer('tile-layer', layers[0].id)
  }
}


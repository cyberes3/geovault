/**
 * MapLibre layer management utilities
 */

/**
 * Ensure all required layers exist on the map
 * @param {Object} map - MapLibre map instance
 * @param {boolean} showAllLabels - Whether to show labels
 */
export function ensureLayersExist(map, showAllLabels = true) {
  if (!map || !map.getSource('geojson-data')) return

  // Points layer - circle layers automatically only render Point geometries
  // Filter to exclude points that are on borders (marked with _on_border property)
  if (!map.getLayer('points')) {
    map.addLayer({
      id: 'points',
      type: 'circle',
      source: 'geojson-data',
      filter: ['all', ['==', ['geometry-type'], 'Point'], ['!', ['has', '_on_border']]],
      paint: {
        'circle-radius': 6,
        'circle-color': '#3388ff',
        'circle-stroke-width': 2,
        'circle-stroke-color': '#ffffff'
      }
    })
  } else {
    // Update filter if layer already exists
    map.setFilter('points', ['all', ['==', ['geometry-type'], 'Point'], ['!', ['has', '_on_border']]])
  }

  // Lines layer - explicitly filter to only LineString and MultiLineString geometries
  const lineFilter = ['any', ['==', ['geometry-type'], 'LineString'], ['==', ['geometry-type'], 'MultiLineString']]
  if (!map.getLayer('lines')) {
    map.addLayer({
      id: 'lines',
      type: 'line',
      source: 'geojson-data',
      filter: lineFilter,
      layout: {
        'line-cap': 'round',
        'line-join': 'round'
      },
      paint: {
        'line-color': '#3388ff',
        'line-width': 2,
        'line-opacity': 1
      }
    })
  } else {
    // Update filter if layer already exists (in case it was created without filter)
    map.setFilter('lines', lineFilter)
    // Update layout properties to ensure no visible vertices
    map.setLayoutProperty('lines', 'line-cap', 'round')
    map.setLayoutProperty('lines', 'line-join', 'round')
  }

  // Polygons fill layer - explicitly filter to only Polygon and MultiPolygon geometries
  const polygonFilter = ['any', ['==', ['geometry-type'], 'Polygon'], ['==', ['geometry-type'], 'MultiPolygon']]
  if (!map.getLayer('polygons')) {
    map.addLayer({
      id: 'polygons',
      type: 'fill',
      source: 'geojson-data',
      filter: polygonFilter,
      paint: {
        'fill-color': '#3388ff',
        'fill-opacity': 0.3
      }
    })
  } else {
    // Update filter if layer already exists
    map.setFilter('polygons', polygonFilter)
  }

  // Polygon outlines - line layer for polygon borders, explicitly filter to only Polygon geometries
  if (!map.getLayer('polygon-outlines')) {
    map.addLayer({
      id: 'polygon-outlines',
      type: 'line',
      source: 'geojson-data',
      filter: polygonFilter,
      layout: {
        'line-cap': 'round',
        'line-join': 'round'
      },
      paint: {
        'line-color': '#3388ff',
        'line-width': 2,
        'line-opacity': 1
      }
    })
  } else {
    // Update filter if layer already exists
    map.setFilter('polygon-outlines', polygonFilter)
    // Update layout properties to ensure no visible vertices
    map.setLayoutProperty('polygon-outlines', 'line-cap', 'round')
    map.setLayoutProperty('polygon-outlines', 'line-join', 'round')
  }

  // Labels layer for text labels
  if (!map.getLayer('labels')) {
    map.addLayer({
      id: 'labels',
      type: 'symbol',
      source: 'geojson-data',
      layout: {
        'text-field': ['coalesce', ['get', 'name'], ''],
        'text-font': ['Noto Sans Regular', 'Arial Unicode MS Regular'],
        'text-size': 12,
        'text-offset': [0, 1.25],
        'text-anchor': 'top',
        'text-allow-overlap': false,
        'text-ignore-placement': false,
        'visibility': showAllLabels ? 'visible' : 'none'
      },
      paint: {
        'text-color': '#000000',
        'text-halo-color': '#ffffff',
        'text-halo-width': 2
      },
      filter: ['!=', ['get', 'name'], '']
    })
  } else if (map.getLayer('labels')) {
    // Update visibility if layer already exists
    map.setLayoutProperty('labels', 'visibility', showAllLabels ? 'visible' : 'none')
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


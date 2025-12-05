/**
 * MapLibre map initialization utilities
 */

import maplibregl from 'maplibre-gl'

/**
 * Initialize a MapLibre map instance
 * @param {HTMLElement} container - Map container element
 * @param {Object} config - Map configuration
 * @param {Array<number>} config.center - Initial center [lon, lat]
 * @param {number} config.zoom - Initial zoom level
 * @param {string} config.glyphsUrl - Glyphs URL template
 * @returns {Object} MapLibre map instance
 */
export function initializeMap(container, config) {
  const { center, zoom, glyphsUrl = '/api/fonts/{fontstack}/{range}.pbf' } = config

  const map = new maplibregl.Map({
    container: container,
    style: {
      version: 8,
      glyphs: glyphsUrl,
      sources: {
        'osm': {
          type: 'raster',
          tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
          tileSize: 256,
          attribution: '© OpenStreetMap contributors'
        }
      },
      layers: [
        {
          id: 'osm-layer',
          type: 'raster',
          source: 'osm',
          minzoom: 0,
          maxzoom: 19
        }
      ]
    },
    center: center, // [lon, lat]
    zoom: zoom,
    maxZoom: 20,
    attributionControl: false
  })

  return map
}

/**
 * Setup GeoJSON source on map
 * @param {Object} map - MapLibre map instance
 * @param {Function} onLoad - Callback when source is ready
 */
export function setupGeoJsonSource(map, onLoad) {
  map.on('load', () => {
    map.addSource('geojson-data', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: []
      }
    })

    if (onLoad) {
      onLoad()
    }
  })
}

/**
 * Setup map event listeners
 * @param {Object} map - MapLibre map instance
 * @param {Object} handlers - Event handlers
 * @param {Function} handlers.onMoveEnd - Handler for moveend event
 * @param {Function} handlers.onZoomEnd - Handler for zoomend event
 * @param {Function} handlers.onClick - Handler for click event
 */
export function setupMapEventListeners(map, handlers) {
  const { onMoveEnd, onZoomEnd, onClick } = handlers

  if (onMoveEnd) {
    map.on('moveend', onMoveEnd)
    map.on('zoomend', onMoveEnd)
  }

  if (onZoomEnd) {
    map.on('zoomend', onZoomEnd)
  }

  if (onClick) {
    map.on('click', onClick)
  }
}


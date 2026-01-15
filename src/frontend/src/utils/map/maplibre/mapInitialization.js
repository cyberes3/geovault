/**
 * MapLibre map initialization utilities
 */

import maplibregl from 'maplibre-gl'

// Maximum allowed zoom level for the map
export const MAX_ZOOM_LEVEL = 18

/**
 * Initialize a MapLibre map instance
 * @param {HTMLElement} container - Map container element
 * @param {Object} config - Map configuration
 * @param {Array<number>} config.center - Initial center [lon, lat]
 * @param {number} config.zoom - Initial zoom level
 * @param {string} config.glyphsUrl - Glyphs URL template
 * @param {boolean} config.antialias - Enable anti-aliasing (default: false)
 * @param {Function} config.transformRequest - Optional transformRequest function for custom headers
 * @returns {Object} MapLibre map instance
 */
export function initializeMap(container, config) {
  // Validate container before attempting to initialize
  if (!container || !(container instanceof HTMLElement)) {
    throw new Error('Invalid container: must be an HTMLElement')
  }

  const { center, zoom, glyphsUrl = '/api/fonts/{fontstack}/{range}.pbf', antialias = false, transformRequest } = config

  const mapConfig = {
    container: container,
    style: {
      version: 8,
      glyphs: glyphsUrl,
      sources: {},
      layers: []
    },
    center: center, // [lon, lat]
    zoom: zoom,
    maxZoom: MAX_ZOOM_LEVEL,
    maxPitch: 85,
    attributionControl: false,
    antialias: antialias // Enable anti-aliasing based on user setting
  }

  // Add transformRequest if provided (for custom headers like User-Agent)
  if (transformRequest) {
    mapConfig.transformRequest = transformRequest
  }

  const map = new maplibregl.Map(mapConfig)

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


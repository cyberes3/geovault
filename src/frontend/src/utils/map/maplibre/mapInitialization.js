/**
 * MapLibre map initialization utilities
 */

import maplibregl from 'maplibre-gl'

// Maximum allowed zoom level for the map
export const MAX_ZOOM_LEVEL = 18

// Hosts that require a valid Referer when loading tiles directly (OSMF / openmaps.fr policy)
const OSM_TILE_HOSTS = ['tile.openstreetmap.org', 'tile.openmaps.fr']

// ResourceType.Tile value from MapLibre (see external sources/maplibre-gl-js/src/util/request_manager.ts)
const RESOURCE_TYPE_TILE = 'Tile'

function isOsmRelatedTileUrl(url, resourceType) {
  if (resourceType !== RESOURCE_TYPE_TILE) return false
  try {
    const host = new URL(url, window.location.origin).hostname
    return OSM_TILE_HOSTS.some((h) => host === h)
  } catch {
    return false
  }
}

/**
 * Build a transformRequest that sends a valid Referer for OSM/OpenTopoMap/OpenHikingMap tile requests,
 * so direct (non-proxied) usage still complies with tile server policies.
 *
 * Note: The MapLibre copy in external sources/maplibre-gl-js does not pass referrerPolicy to fetch
 * (RequestParameters has no referrerPolicy; makeFetchRequest uses getReferrer() only). So the
 * referrerPolicy we set here is for forward compatibility if the library adds support. The library
 * already sends referrer via getReferrer() (document URL), so OSM tiles get a valid Referer by
 * default when proxying is disabled.
 *
 * @param {Function} [customTransformRequest] - Optional custom transformRequest to chain
 * @returns {Function} transformRequest(url, resourceType) returning RequestParameters
 */
export function createTransformRequest(customTransformRequest) {
  return (url, resourceType) => {
    const result = customTransformRequest
      ? customTransformRequest(url, resourceType)
      : { url }
    const out = result && typeof result === 'object' ? { ...result, url: result.url ?? url } : { url }
    if (isOsmRelatedTileUrl(out.url, resourceType)) {
      out.referrerPolicy = 'strict-origin-when-cross-origin'
    }
    return out
  }
}

/**
 * Initialize a MapLibre map instance
 * @param {HTMLElement} container - Map container element
 * @param {Object} config - Map configuration
 * @param {Array<number>} config.center - Initial center [lon, lat]
 * @param {number} config.zoom - Initial zoom level
 * @param {number} [config.pitch] - Initial pitch in degrees (default: 0)
 * @param {number} [config.bearing] - Initial bearing in degrees (default: 0)
 * @param {string} config.glyphsUrl - Glyphs URL template
 * @param {boolean} config.antialias - Enable anti-aliasing (default: false)
 * @param {Function} [config.transformRequest] - Optional transformRequest function for custom headers (chained with OSM referrer)
 * @returns {Object} MapLibre map instance
 */
export function initializeMap(container, config) {
  // Validate container before attempting to initialize
  if (!container || !(container instanceof HTMLElement)) {
    throw new Error('Invalid container: must be an HTMLElement')
  }

  const { center, zoom, pitch = 0, bearing = 0, glyphsUrl = '/api/fonts/{fontstack}/{range}.pbf', antialias = false, transformRequest = undefined } = config

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
    pitch: pitch,
    bearing: bearing,
    maxZoom: MAX_ZOOM_LEVEL,
    maxPitch: 85,
    attributionControl: false,
    antialias: antialias // Enable anti-aliasing based on user setting
  }

  // Always use a transformRequest that sends valid Referer for OSM-related tiles; chain custom if provided
  mapConfig.transformRequest = createTransformRequest(transformRequest)

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


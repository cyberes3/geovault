/**
 * MapLibre map initialization utilities
 */

import maplibregl from 'maplibre-gl'

/** @typedef {import('maplibre-gl').StyleSpecification} StyleSpecification */

// Maximum allowed zoom level for the map
export const MAX_ZOOM_LEVEL = 18

// Default glyphs URL template used by every MapLibre style built in this app
export const DEFAULT_GLYPHS_URL = '/api/fonts/{fontstack}/{range}.pbf'

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
 * Resolve the MapLibre style to use for a given tile source, so callers can pass it
 * straight into `initializeMap()`/`new maplibregl.Map()` (avoiding an empty-style flash)
 * or into `map.setStyle()` (avoiding a separate addSource/addLayer step).
 * @param {Object} [tileSource] - Tile source record (with `client_config`); falls back to a blank style if omitted
 * @param {string} [glyphsUrl] - Glyphs URL template
 * @returns {string|StyleSpecification} Either a style URL (style-based/MapTiler sources) or a full style spec object
 */
export function resolveMapStyle(tileSource, glyphsUrl = DEFAULT_GLYPHS_URL) {
  if (!tileSource) {
    return {
      version: 8,
      glyphs: glyphsUrl,
      sources: {},
      layers: []
    }
  }

  const clientConfig = tileSource.client_config
  const isStyleBased = !!clientConfig.style_url || clientConfig.type === 'maptiler'

  if (isStyleBased) {
    return clientConfig.style_url
  }

  const url = clientConfig.url ?? `/api/tiles/${tileSource.id}/{z}/{x}/{y}`
  const tiles =
    clientConfig.tileSubdomains && Array.isArray(clientConfig.tileSubdomains)
      ? clientConfig.tileSubdomains.map((subdomain) => url.replace('{s}', subdomain))
      : [url.replace('{s}', clientConfig.tileSubdomains?.[0] ?? 'a')]

  const sourceMaxZoom = clientConfig.maxzoom ?? MAX_ZOOM_LEVEL
  const layerMaxZoom = Math.max(sourceMaxZoom, MAX_ZOOM_LEVEL + 1)

  return {
    version: 8,
    glyphs: glyphsUrl,
    sources: {
      'raster-source': {
        type: 'raster',
        tiles,
        tileSize: clientConfig.tileSize ?? 256,
        attribution: clientConfig.attribution ?? ''
      }
    },
    layers: [
      {
        id: 'raster-layer',
        type: 'raster',
        source: 'raster-source',
        minzoom: clientConfig.minzoom ?? 0,
        maxzoom: layerMaxZoom
      }
    ]
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
 * @param {string|StyleSpecification} [config.style] - Initial style URL or style spec object (default: blank style, see `resolveMapStyle()`)
 * @returns {Object} MapLibre map instance
 */
export function initializeMap(container, config) {
  // Validate container before attempting to initialize
  if (!container || !(container instanceof HTMLElement)) {
    throw new Error('Invalid container: must be an HTMLElement')
  }

  const {
    center,
    zoom,
    pitch = 0,
    bearing = 0,
    glyphsUrl = DEFAULT_GLYPHS_URL,
    antialias = false,
    transformRequest = undefined,
    style = {
      version: 8,
      glyphs: glyphsUrl,
      sources: {},
      layers: []
    }
  } = config

  const mapConfig = {
    container: container,
    style: style,
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
 * Setup GeoJSON source on map. Uses `once()` (the 'load' event only ever fires once per map
 * instance) so the listener is self-removing and needs no explicit teardown.
 * @param {Object} map - MapLibre map instance
 * @param {Function} onLoad - Callback when source is ready
 */
export function setupGeoJsonSource(map, onLoad) {
  map.once('load', () => {
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
 * Setup map event listeners.
 * @param {Object} map - MapLibre map instance
 * @param {Object} handlers - Event handlers
 * @param {Function} handlers.onMoveEnd - Handler for moveend event
 * @param {Function} handlers.onZoomEnd - Handler for zoomend event
 * @param {Function} handlers.onClick - Handler for click event
 * @returns {Function} Teardown function that removes exactly the listeners this call registered.
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

  return () => {
    if (onMoveEnd) {
      map.off('moveend', onMoveEnd)
      map.off('zoomend', onMoveEnd)
    }
    if (onZoomEnd) {
      map.off('zoomend', onZoomEnd)
    }
    if (onClick) {
      map.off('click', onClick)
    }
  }
}

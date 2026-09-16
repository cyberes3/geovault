/**
 * MapLibre map initialization utilities
 */

import type { StyleSpecification, Map as MapLibreMap, RequestTransformFunction, RequestParameters, MapOptions } from 'maplibre-gl'
import type { TileSource } from '@/api/services/tilesApi'
import { loadMaplibreGl } from './lazyMaplibreGl.js'

// Maximum allowed zoom level for the map
export const MAX_ZOOM_LEVEL = 18

// Default glyphs URL template used by every MapLibre style built in this app
export const DEFAULT_GLYPHS_URL = '/api/fonts/{fontstack}/{range}.pbf'

// Hosts that require a valid Referer when loading tiles directly (OSMF / openmaps.fr policy)
const OSM_TILE_HOSTS = ['tile.openstreetmap.org', 'tile.openmaps.fr']

// ResourceType.Tile value from MapLibre (see external sources/maplibre-gl-js/src/util/request_manager.ts)
const RESOURCE_TYPE_TILE = 'Tile'

function isOsmRelatedTileUrl(url: string, resourceType: string | undefined): boolean {
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
 */
type RequestParametersWithReferrerPolicy = RequestParameters & { referrerPolicy?: ReferrerPolicy }

function applyOsmReferrer(url: string, resourceType: string | undefined, result: RequestParameters | undefined): RequestParametersWithReferrerPolicy {
  const out: RequestParametersWithReferrerPolicy = result && typeof result === 'object' ? { ...result, url: result.url } : { url }
  if (isOsmRelatedTileUrl(out.url, resourceType)) {
    out.referrerPolicy = 'strict-origin-when-cross-origin'
  }
  return out
}

export function createTransformRequest(customTransformRequest?: RequestTransformFunction | null): RequestTransformFunction {
  return (url, resourceType) => {
    const result = customTransformRequest
      ? customTransformRequest(url, resourceType)
      : { url }
    if (result && typeof (result as Promise<RequestParameters>).then === 'function') {
      return (result as Promise<RequestParameters>).then((resolved) => applyOsmReferrer(url, resourceType, resolved))
    }
    return applyOsmReferrer(url, resourceType, result as RequestParameters)
  }
}

/**
 * Resolve the MapLibre style to use for a given tile source, so callers can pass it
 * straight into `initializeMap()`/`new maplibregl.Map()` (avoiding an empty-style flash)
 * or into `map.setStyle()` (avoiding a separate addSource/addLayer step).
 */
export function resolveMapStyle(tileSource?: TileSource | null, glyphsUrl: string = DEFAULT_GLYPHS_URL): string | StyleSpecification {
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
    return clientConfig.style_url || {
      version: 8,
      glyphs: glyphsUrl,
      sources: {},
      layers: []
    }
  }

  const url = clientConfig.url ?? `/api/tiles/${tileSource.id}/{z}/{x}/{y}`
  const tiles = Array.isArray(clientConfig.tileSubdomains)
    ? clientConfig.tileSubdomains.map((subdomain) => url.replace('{s}', subdomain))
    : [url.replace('{s}', 'a')]

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

export interface InitializeMapConfig {
  /** Initial center [lon, lat] */
  center: [number, number]
  zoom: number
  /** Initial pitch in degrees (default: 0) */
  pitch?: number
  /** Initial bearing in degrees (default: 0) */
  bearing?: number
  glyphsUrl?: string
  /** Enable anti-aliasing (default: false) */
  antialias?: boolean
  /** Optional transformRequest function for custom headers (chained with OSM referrer) */
  transformRequest?: RequestTransformFunction | null
  /** Initial style URL or style spec object (default: blank style, see `resolveMapStyle()`) */
  style?: string | StyleSpecification
  /** Override the server `tilesources.show_attribution` flag. */
  attributionControl?: boolean
}

export function buildMapConstructorOptions(container: HTMLElement, config: InitializeMapConfig): MapOptions {
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
    },
    attributionControl = false,
  } = config

  return {
    container,
    style,
    center,
    zoom,
    pitch,
    bearing,
    maxZoom: MAX_ZOOM_LEVEL,
    maxPitch: 85,
    attributionControl,
    canvasContextAttributes: { antialias },
    transformRequest: createTransformRequest(transformRequest)
  }
}

export function isMapIdle(map: MapLibreMap): boolean {
  try {
    if (!map.isStyleLoaded()) return false
    if (typeof map.isMoving === 'function' && map.isMoving()) return false
    if (typeof map.isZooming === 'function' && map.isZooming()) return false
    if (typeof map.isRotating === 'function' && map.isRotating()) return false
    return true
  } catch {
    return false
  }
}

/**
 * Resolve when the map is idle enough to add sources/layers or fit bounds.
 * `idle` only fires on a busy→idle transition, so an already-idle map must resolve immediately.
 * If the style is loaded when the timeout hits, proceed; reject only when the style never became usable.
 */
export function waitForMapIdle(map: MapLibreMap, timeoutMs = 15000): Promise<void> {
  if (isMapIdle(map)) return Promise.resolve()
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (ok: boolean, error?: Error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearInterval(poll)
      map.off('idle', onIdle)
      map.off('styledata', onStyleData)
      if (ok) resolve()
      else reject(error ?? new Error('Timed out waiting for map idle'))
    }
    const onIdle = () => {
      if (isMapIdle(map)) finish(true)
    }
    const onStyleData = () => {
      if (isMapIdle(map)) finish(true)
    }
    const timer = setTimeout(() => {
      if (map.isStyleLoaded()) finish(true)
      else finish(false, new Error('Timed out waiting for map idle'))
    }, timeoutMs)
    const poll = setInterval(() => {
      if (isMapIdle(map)) finish(true)
    }, 50)
    map.once('idle', onIdle)
    map.on('styledata', onStyleData)
    if (isMapIdle(map)) finish(true)
  })
}

/** Resolve when the style can accept addSource / addLayer (MapLibre 6 throws before this). */
export function waitForStyleLoaded(map: MapLibreMap, timeoutMs = 15000): Promise<void> {
  if (map.isStyleLoaded()) return Promise.resolve()
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearInterval(poll)
      map.off('load', finish)
      map.off('styledata', onStyleData)
      resolve()
    }
    const onStyleData = () => {
      if (map.isStyleLoaded()) finish()
    }
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      clearInterval(poll)
      map.off('load', finish)
      map.off('styledata', onStyleData)
      reject(new Error('Timed out waiting for map load'))
    }, timeoutMs)
    const poll = setInterval(() => {
      if (map.isStyleLoaded()) finish()
    }, 50)
    map.once('load', finish)
    map.on('styledata', onStyleData)
    if (map.isStyleLoaded()) finish()
  })
}

/** Initialize a MapLibre map instance. Loads maplibre-gl itself (lazily, cached after the first call). */
export async function initializeMap(container: HTMLElement, config: InitializeMapConfig): Promise<MapLibreMap> {
  if (!(container instanceof HTMLElement)) {
    throw new Error('Invalid container: must be an HTMLElement')
  }

  const maplibregl = await loadMaplibreGl()
  try {
    return new maplibregl.Map(buildMapConstructorOptions(container, config))
  } catch (error) {
    remapMapInitError(error, maplibregl.GPUInitializationError);
  }
}

export function remapMapInitError(error: unknown, gpuErrorClass?: new (...args: never[]) => Error): never {
  const name = error instanceof Error ? error.name : ''
  if ((gpuErrorClass && error instanceof gpuErrorClass) || name === 'GPUInitializationError') {
    throw new Error('This browser cannot create a WebGL2 map context.')
  }
  throw error
}

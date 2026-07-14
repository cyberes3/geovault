import {
  applyEditInteractionPolicy,
  applyListDesktopInteractionPolicy,
  applyListTouchInteractionPolicy,
  getInitialCooperativeGestures,
  isTouchPointer
} from '@/utils/placesCooperativeGestures';
import {
  buildRasterSourceSpec,
  buildRasterStyle,
  getTileSourceSelectOptions,
  isStyleBasedSource,
  type TileSourceSelectOption
} from '@/utils/placesBasemap';
import type { TileSource } from '@/types/gv-core';
import type {
  MaplibreGeoJSONFeature,
  MaplibreGlNamespace,
  MaplibreMap,
  MaplibreMapMouseEvent,
  MaplibrePoint
} from '@/types/maplibre';
import type { PlaceMapFeature } from '@/types/places';

const DEFAULT_CENTER: [number, number] = [0, 0];
const DEFAULT_ZOOM = 2;
/** Reuse core's singleton catalog instance so places shares its cache/in-flight fetch with the rest of the app. */
const tileSourceCatalog = window.gv_core.tileSourceCatalog;
const OSM_TILE_SOURCE_ID = window.gv_core.OSM_TILE_SOURCE_ID;
const isValidMapLngLatPair = window.gv_core.isValidMapLngLatPair;

/** Basemap raster ids — must not collide with layers inside vector styles (e.g. MapTiler). */
const GV_PLACES_BASE_RASTER_SOURCE_ID = 'gv_places_basemap_raster';
const GV_PLACES_BASE_RASTER_LAYER_ID = 'gv_places_basemap_raster_layer';

const BLANK_MAP_STYLE = {
  version: 8,
  glyphs: '/api/fonts/{fontstack}/{range}.pbf',
  sources: {},
  layers: []
};

function getCssColor(variableName: string, fallback: string): string {
  const value = window.getComputedStyle(document.documentElement).getPropertyValue(variableName).trim();
  return value || fallback;
}

interface MarkerColors {
  default: string;
  highlighted: string;
}

function getMarkerColors(): MarkerColors {
  return {
    default: getCssColor('--color-blue-500', '#163D8A'),
    highlighted: getCssColor('--color-yellow-500', '#F4AC45'),
  };
}

/**
 * MapLibre GL JS loads lazily (see `lazyMaplibreGl.js` in core), so `window.gv_core.maplibre` may
 * still be null the first time places renders its map - nothing else is guaranteed to have loaded
 * it already (e.g. navigating straight to Places without ever visiting the main map). Await the
 * shared loader (idempotent/cached after the first call) instead of assuming it's already there.
 */
async function getMaplibre(): Promise<MaplibreGlNamespace | null> {
  return window.gv_core.maplibre ?? window.maplibregl ?? (await window.gv_core.loadMaplibreGl());
}

function applyInteractionPolicy(map: MaplibreMap, mode: string): void {
  if (mode === 'edit') {
    applyEditInteractionPolicy(map);
    return;
  }
  if (isTouchPointer()) {
    applyListTouchInteractionPolicy(map);
  } else {
    applyListDesktopInteractionPolicy(map);
  }
}

function waitForMapEvent(map: MaplibreMap, eventName: string, timeoutMs = 15000): Promise<boolean> {
  return new Promise((resolve) => {
    const timeoutId = setTimeout(() => { resolve(false); }, timeoutMs);
    map.once(eventName, () => {
      clearTimeout(timeoutId);
      resolve(true);
    });
  });
}

function getValidPointFeatures(features: PlaceMapFeature[]): PlaceMapFeature[] {
  return features.filter((feature) => isValidMapLngLatPair(feature.geometry.coordinates[0], feature.geometry.coordinates[1]));
}

export interface FitOptions {
  focusZoom?: number;
  fitPadding?: { top: number; right: number; bottom: number; left: number };
  fitMaxZoom?: number;
}

function applyViewToPointFeatures(map: MaplibreMap, maplibre: MaplibreGlNamespace, features: PlaceMapFeature[], {
  focusZoom = 12,
  fitPadding = { top: 100, right: 100, bottom: 140, left: 140 },
  fitMaxZoom = 15
}: FitOptions = {}): void {
  const valid = getValidPointFeatures(features);
  if (valid.length === 0) {
    return;
  }
  if (valid.length === 1) {
    map.jumpTo({ center: valid[0].geometry.coordinates, zoom: focusZoom });
    return;
  }
  const first = valid[0].geometry.coordinates;
  const bounds = new maplibre.LngLatBounds(first, first);
  for (let i = 1; i < valid.length; i += 1) {
    bounds.extend(valid[i].geometry.coordinates);
  }
  const camera = map.cameraForBounds(bounds, { padding: fitPadding, maxZoom: fitMaxZoom });
  if (camera) {
    map.jumpTo(camera);
  }
}

function resolveInitialBaseSource(tileSources: TileSource[], preferredId: string = OSM_TILE_SOURCE_ID): TileSource {
  return tileSourceCatalog.resolveSource(tileSources, preferredId);
}

export interface CreatePlacesMapOptions {
  container: HTMLElement;
  mode?: 'list' | 'edit';
  sourceId: string;
  layerId: string;
  preferredSourceId?: string;
  minZoom?: number;
  maxZoom?: number;
  initialPointFeatures?: PlaceMapFeature[] | null;
  initialFitOptions?: FitOptions;
}

export interface PlacesMapController {
  map: MaplibreMap;
  setPointFeatures(features: PlaceMapFeature[]): void;
  fitToPointFeatures(features: PlaceMapFeature[], options?: FitOptions): void;
  queryFirstPointAt(point: MaplibrePoint): MaplibreGeoJSONFeature | null;
  getBaseSourceOptions(): TileSourceSelectOption[];
  getCurrentBaseSourceId(): string;
  setBaseSource(nextSourceId: string): Promise<string>;
  resizeNow(): void;
  destroy(): void;
}

export async function createPlacesMap({
  container,
  mode = 'list',
  sourceId,
  layerId,
  preferredSourceId = OSM_TILE_SOURCE_ID,
  minZoom = 1,
  maxZoom = 18,
  initialPointFeatures = null,
  initialFitOptions = {}
}: CreatePlacesMapOptions): Promise<PlacesMapController> {
  const maplibre = await getMaplibre();
  if (!maplibre) {
    throw new Error('MapLibre is not available on window.gv_core.maplibre or window.maplibregl');
  }

  const markerColors = getMarkerColors();
  const initialCoop = getInitialCooperativeGestures(mode);
  const tileSources = await tileSourceCatalog.load();

  const resolveInitialStyle = (baseSource: TileSource): string | Record<string, unknown> => {
    const useStyleUrl = isStyleBasedSource(baseSource) && !!baseSource.client_config.style_url;
    return useStyleUrl && baseSource.client_config.style_url
      ? baseSource.client_config.style_url
      : buildRasterStyle(baseSource, {
        sourceId: GV_PLACES_BASE_RASTER_SOURCE_ID,
        layerId: GV_PLACES_BASE_RASTER_LAYER_ID
      });
  };

  let activeBaseSource = resolveInitialBaseSource(tileSources, preferredSourceId);
  let currentFeatures = initialPointFeatures ?? [];
  const style = resolveInitialStyle(activeBaseSource);

  const map = new maplibre.Map({
    container,
    style,
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    minZoom,
    maxZoom,
    dragRotate: false,
    pitchWithRotate: false,
    attributionControl: false,
    cooperativeGestures: initialCoop
  });

  if (currentFeatures.length > 0) {
    applyViewToPointFeatures(map, maplibre, currentFeatures, initialFitOptions);
  }

  const applyPlacesBasemap = async (baseSource: TileSource): Promise<void> => {
    const clientConfig = baseSource.client_config;
    const useStyleUrl = isStyleBasedSource(baseSource) && !!clientConfig.style_url;

    if (useStyleUrl && clientConfig.style_url) {
      map.setStyle(clientConfig.style_url);
      const ok = await waitForMapEvent(map, 'styledata', 30000);
      if (!ok) {
        throw new Error('Timed out waiting for basemap style to load');
      }
      map.setMaxZoom(maxZoom);
      return;
    }

    map.setStyle(BLANK_MAP_STYLE);
    const blankOk = await waitForMapEvent(map, 'styledata', 30000);
    if (!blankOk) {
      throw new Error('Timed out waiting for blank basemap style');
    }
    map.setMaxZoom(maxZoom);

    try {
      if (map.getLayer(GV_PLACES_BASE_RASTER_LAYER_ID)) {
        map.removeLayer(GV_PLACES_BASE_RASTER_LAYER_ID);
      }
    } catch {
      /* ignore */
    }
    try {
      if (map.getSource(GV_PLACES_BASE_RASTER_SOURCE_ID)) {
        map.removeSource(GV_PLACES_BASE_RASTER_SOURCE_ID);
      }
    } catch {
      /* ignore */
    }

    const spec = buildRasterSourceSpec(baseSource);
    const minzoom = clientConfig.minzoom ?? 0;
    const layerMaxZoom = Math.max(clientConfig.maxzoom ?? maxZoom, maxZoom + 1);

    map.addSource(GV_PLACES_BASE_RASTER_SOURCE_ID, spec);
    map.addLayer({
      id: GV_PLACES_BASE_RASTER_LAYER_ID,
      type: 'raster',
      source: GV_PLACES_BASE_RASTER_SOURCE_ID,
      minzoom,
      maxzoom: layerMaxZoom
    });
  };

  const listeners: Array<{ event: string; handler: (event: MaplibreMapMouseEvent) => void }> = [];
  const on = (event: string, handler: (event: MaplibreMapMouseEvent) => void): void => {
    map.on(event, handler);
    listeners.push({ event, handler });
  };

  const resizeNow = (): void => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        map.resize();
      });
    });
  };

  const resizeHandler = (): void => {
    resizeNow();
  };
  window.addEventListener('resize', resizeHandler);
  const mapHostEl = container.parentElement ?? container;
  const resizeObserver = new ResizeObserver(() => {
    resizeNow();
  });
  resizeObserver.observe(mapHostEl);

  on('load', () => {
    applyInteractionPolicy(map, mode);
    resizeNow();
  });

  const reinstallPlacesOverlay = (): void => {
    try {
      if (map.getLayer(layerId)) {
        map.removeLayer(layerId);
      }
    } catch {
      /* ignore */
    }
    try {
      if (map.getSource(sourceId)) {
        map.removeSource(sourceId);
      }
    } catch {
      /* ignore */
    }
    map.addSource(sourceId, {
      type: 'geojson',
      data: { type: 'FeatureCollection', features: currentFeatures }
    });
    map.addLayer({
      id: layerId,
      type: 'circle',
      source: sourceId,
      paint: {
        'circle-radius': 7,
        'circle-color': ['case', ['==', ['get', 'is_highlighted'], 1], markerColors.highlighted, markerColors.default],
        'circle-stroke-color': ['case', ['==', ['get', 'is_highlighted'], 1], '#000000', '#FFFFFF'],
        'circle-stroke-width': 2
      }
    });
    try {
      map.moveLayer(layerId);
    } catch {
      /* ignore */
    }
  };

  await new Promise<void>((resolve) => {
    map.once('load', () => { resolve(); });
  });
  reinstallPlacesOverlay();

  const setPointFeatures = (features: PlaceMapFeature[]): void => {
    currentFeatures = features;
    const source = map.getSource(sourceId);
    if (!source) return;
    source.setData({
      type: 'FeatureCollection',
      features: currentFeatures
    });
  };

  const fitToPointFeatures = (features: PlaceMapFeature[], options: FitOptions = {}): void => {
    applyViewToPointFeatures(map, maplibre, features, options);
  };

  const queryFirstPointAt = (point: MaplibrePoint): MaplibreGeoJSONFeature | null => {
    const features = map.queryRenderedFeatures(point, { layers: [layerId] });
    return features[0] ?? null;
  };

  const getBaseSourceOptions = (): TileSourceSelectOption[] => getTileSourceSelectOptions(tileSources);
  const getCurrentBaseSourceId = (): string => activeBaseSource.id || OSM_TILE_SOURCE_ID;

  const setBaseSource = async (nextSourceId: string): Promise<string> => {
    const nextBaseSource = resolveInitialBaseSource(tileSources, nextSourceId);
    const nextId = nextBaseSource.id || OSM_TILE_SOURCE_ID;
    if (nextId === getCurrentBaseSourceId()) {
      return nextId;
    }

    const currentViewState = {
      center: map.getCenter(),
      zoom: map.getZoom(),
      bearing: map.getBearing(),
      pitch: map.getPitch()
    };

    try {
      await applyPlacesBasemap(nextBaseSource);
      await waitForMapEvent(map, 'idle', 8000);
      reinstallPlacesOverlay();
      applyInteractionPolicy(map, mode);
      map.jumpTo(currentViewState);
      resizeNow();
      activeBaseSource = nextBaseSource;
      return nextId;
    } catch (error) {
      try {
        await applyPlacesBasemap(activeBaseSource);
        await waitForMapEvent(map, 'idle', 8000);
        reinstallPlacesOverlay();
      } catch {
        /* best-effort recovery */
      }
      applyInteractionPolicy(map, mode);
      map.jumpTo(currentViewState);
      resizeNow();
      throw error;
    }
  };

  const destroy = (): void => {
    listeners.forEach(({ event, handler }) => { map.off(event, handler); });
    listeners.length = 0;
    window.removeEventListener('resize', resizeHandler);
    resizeObserver.disconnect();
    map.remove();
  };

  return {
    map,
    setPointFeatures,
    fitToPointFeatures,
    queryFirstPointAt,
    getBaseSourceOptions,
    getCurrentBaseSourceId,
    setBaseSource,
    resizeNow,
    destroy
  };
}

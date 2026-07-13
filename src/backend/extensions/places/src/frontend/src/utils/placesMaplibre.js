import {
  applyEditInteractionPolicy,
  applyListDesktopInteractionPolicy,
  applyListTouchInteractionPolicy,
  getInitialCooperativeGestures,
  isTouchPointer
} from '@/utils/placesCooperativeGestures.js';
import {
  buildRasterSourceSpec,
  buildRasterStyle,
  getTileSourceSelectOptions,
  isStyleBasedSource
} from '@/utils/placesBasemap.js';

const DEFAULT_CENTER = [0, 0];
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

function getCssColor(variableName, fallback) {
  const value = getComputedStyle(document.documentElement).getPropertyValue(variableName).trim();
  return value || fallback;
}

function getMarkerColors() {
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
async function getMaplibre() {
  return window.gv_core?.maplibre ?? window.maplibregl ?? (await window.gv_core?.loadMaplibreGl?.()) ?? null;
}

function applyInteractionPolicy(map, mode) {
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

function waitForMapEvent(map, eventName, timeoutMs = 15000) {
  return new Promise((resolve) => {
    const timeoutId = setTimeout(() => resolve(false), timeoutMs);
    map.once(eventName, () => {
      clearTimeout(timeoutId);
      resolve(true);
    });
  });
}

function getValidPointFeatures(features) {
  if (!Array.isArray(features) || features.length === 0) {
    return [];
  }
  return features.filter((feature) => {
    const coordinates = feature?.geometry?.coordinates;
    return (
      Array.isArray(coordinates) &&
      coordinates.length >= 2 &&
      isValidMapLngLatPair(coordinates[0], coordinates[1])
    );
  });
}

function applyViewToPointFeatures(map, maplibre, features, {
  focusZoom = 12,
  fitPadding = {top: 100, right: 100, bottom: 140, left: 140},
  fitMaxZoom = 15
} = {}) {
  const valid = getValidPointFeatures(features);
  if (valid.length === 0) {
    return;
  }
  if (valid.length === 1) {
    map.jumpTo({center: valid[0].geometry.coordinates, zoom: focusZoom});
    return;
  }
  const first = valid[0].geometry.coordinates;
  const bounds = new maplibre.LngLatBounds(first, first);
  for (let i = 1; i < valid.length; i += 1) {
    bounds.extend(valid[i].geometry.coordinates);
  }
  const camera = map.cameraForBounds(bounds, {padding: fitPadding, maxZoom: fitMaxZoom});
  if (camera) {
    map.jumpTo(camera);
  }
}

function resolveInitialBaseSource(tileSources, preferredId = OSM_TILE_SOURCE_ID) {
  return tileSourceCatalog.resolveSource(tileSources, preferredId);
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
}) {
  const maplibre = await getMaplibre();
  if (!maplibre) {
    throw new Error('MapLibre is not available on window.gv_core.maplibre or window.maplibregl');
  }
  if (!container) {
    throw new Error('Map container is required');
  }

  const markerColors = getMarkerColors();
  const initialCoop = getInitialCooperativeGestures(mode);
  const tileSources = await tileSourceCatalog.load();

  const resolveInitialStyle = (baseSource) => {
    const useStyleUrl = isStyleBasedSource(baseSource) && !!baseSource?.client_config?.style_url;
    return useStyleUrl
      ? baseSource.client_config.style_url
      : buildRasterStyle(baseSource, {
        sourceId: GV_PLACES_BASE_RASTER_SOURCE_ID,
        layerId: GV_PLACES_BASE_RASTER_LAYER_ID
      });
  };

  let activeBaseSource = resolveInitialBaseSource(tileSources, preferredSourceId);
  let currentFeatures = Array.isArray(initialPointFeatures) ? initialPointFeatures : [];
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

  const applyPlacesBasemap = async (baseSource) => {
    const clientConfig = baseSource?.client_config || {};
    const useStyleUrl = isStyleBasedSource(baseSource) && !!clientConfig.style_url;

    if (useStyleUrl) {
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
    } catch (_) {
      /* ignore */
    }
    try {
      if (map.getSource(GV_PLACES_BASE_RASTER_SOURCE_ID)) {
        map.removeSource(GV_PLACES_BASE_RASTER_SOURCE_ID);
      }
    } catch (_) {
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

  const listeners = [];
  const on = (event, handler) => {
    map.on(event, handler);
    listeners.push({event, handler});
  };

  const resizeNow = () => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => map.resize());
    });
  };

  let resizeObserver = null;
  const resizeHandler = () => resizeNow();
  window.addEventListener('resize', resizeHandler);
  const mapHostEl = container.parentElement || container;
  if (typeof ResizeObserver !== 'undefined' && mapHostEl) {
    resizeObserver = new ResizeObserver(() => resizeNow());
    resizeObserver.observe(mapHostEl);
  }

  on('load', () => {
    applyInteractionPolicy(map, mode);
    resizeNow();
  });

  const reinstallPlacesOverlay = () => {
    try {
      if (map.getLayer(layerId)) {
        map.removeLayer(layerId);
      }
    } catch (_) {
      /* ignore */
    }
    try {
      if (map.getSource(sourceId)) {
        map.removeSource(sourceId);
      }
    } catch (_) {
      /* ignore */
    }
    map.addSource(sourceId, {
      type: 'geojson',
      data: {type: 'FeatureCollection', features: currentFeatures}
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
    } catch (_) {
      /* ignore */
    }
  };

  await new Promise((resolve) => map.once('load', resolve));
  reinstallPlacesOverlay();

  const setPointFeatures = (features) => {
    currentFeatures = Array.isArray(features) ? features : [];
    const source = map.getSource(sourceId);
    if (!source) return;
    source.setData({
      type: 'FeatureCollection',
      features: currentFeatures
    });
  };

  const fitToPointFeatures = (features, options = {}) => {
    applyViewToPointFeatures(map, maplibre, features, options);
  };

  const queryFirstPointAt = (point) => {
    const features = map.queryRenderedFeatures(point, {layers: [layerId]});
    return features[0] || null;
  };

  const getBaseSourceOptions = () => getTileSourceSelectOptions(tileSources);
  const getCurrentBaseSourceId = () => activeBaseSource?.id || OSM_TILE_SOURCE_ID;

  const setBaseSource = async (nextSourceId) => {
    const nextBaseSource = resolveInitialBaseSource(tileSources, nextSourceId);
    const nextId = nextBaseSource?.id || OSM_TILE_SOURCE_ID;
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
      } catch (_) {
        /* best-effort recovery */
      }
      applyInteractionPolicy(map, mode);
      map.jumpTo(currentViewState);
      resizeNow();
      throw error;
    }
  };

  const destroy = () => {
    listeners.forEach(({event, handler}) => map.off(event, handler));
    listeners.length = 0;
    window.removeEventListener('resize', resizeHandler);
    if (resizeObserver) {
      resizeObserver.disconnect();
      resizeObserver = null;
    }
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

import {
  buildRasterStyle,
  fetchVisibleTileSources,
  getTileSourceSelectOptions,
  isStyleBasedSource,
  resolveInitialBaseSource
} from '@/utils/tileSources.js';
import {
  applyEditInteractionPolicy,
  applyListDesktopInteractionPolicy,
  applyListTouchInteractionPolicy,
  getInitialCooperativeGestures,
  isTouchPointer
} from '@/utils/placesCooperativeGestures.js';
const DEFAULT_CENTER = [0, 0];
const DEFAULT_ZOOM = 2;

function getMaplibre() {
  return window.gv_core?.maplibre || window.maplibregl || null;
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

/** After setStyle(), `style.load` may never fire if the style fails; cap wait so callers never hang. */
function waitForNextStyleLoad(map, timeoutMs = 15000) {
  return new Promise((resolve) => {
    if (typeof map.isStyleLoaded === 'function' && map.isStyleLoaded()) {
      resolve();
      return;
    }
    const timer = setTimeout(resolve, timeoutMs);
    map.once('style.load', () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

export async function createPlacesMap({
  container,
  mode = 'list',
  sourceId,
  layerId,
  preferredSourceId = 'osm',
  minZoom = 1,
  maxZoom = 18
}) {
  const maplibre = getMaplibre();
  if (!maplibre) {
    throw new Error('MapLibre is not available on window.gv_core.maplibre or window.maplibregl');
  }
  if (!container) {
    throw new Error('Map container is required');
  }

  const initialCoop = getInitialCooperativeGestures(mode);

  const tileSources = await fetchVisibleTileSources();
  const resolveStyle = (baseSource) => {
    const useStyleUrl = isStyleBasedSource(baseSource) && !!baseSource?.client_config?.style_url;
    return useStyleUrl ? baseSource.client_config.style_url : buildRasterStyle(baseSource);
  };

  let activeBaseSource = resolveInitialBaseSource(tileSources, preferredSourceId);
  let currentFeatures = [];
  const style = resolveStyle(activeBaseSource);

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

  const ensurePlacesSourceAndLayer = () => {
    if (!map.getSource(sourceId)) {
      map.addSource(sourceId, {
        type: 'geojson',
        data: {type: 'FeatureCollection', features: currentFeatures}
      });
    }
    if (!map.getLayer(layerId)) {
      map.addLayer({
        id: layerId,
        type: 'circle',
        source: sourceId,
        paint: {
          'circle-radius': 7,
          'circle-color': ['case', ['==', ['get', 'is_highlighted'], 1], '#F4AC45', '#163D8A'],
          'circle-stroke-color': ['case', ['==', ['get', 'is_highlighted'], 1], '#000000', '#FFFFFF'],
          'circle-stroke-width': 2
        }
      });
    }
  };

  await new Promise((resolve) => map.once('load', resolve));
  ensurePlacesSourceAndLayer();

  const setPointFeatures = (features) => {
    currentFeatures = Array.isArray(features) ? features : [];
    const source = map.getSource(sourceId);
    if (!source) return;
    source.setData({
      type: 'FeatureCollection',
      features: currentFeatures
    });
  };

  const fitToPointFeatures = (features, {
    focusZoom = 12,
    fitPadding = {top: 100, right: 100, bottom: 140, left: 140},
    fitMaxZoom = 15
  } = {}) => {
    if (!features || features.length === 0) return;
    if (features.length === 1) {
      map.easeTo({center: features[0].geometry.coordinates, zoom: focusZoom, duration: 0});
      return;
    }
    const bounds = new maplibre.LngLatBounds(features[0].geometry.coordinates, features[0].geometry.coordinates);
    for (let i = 1; i < features.length; i += 1) {
      bounds.extend(features[i].geometry.coordinates);
    }
    map.fitBounds(bounds, {padding: fitPadding, maxZoom: fitMaxZoom, duration: 0});
  };

  const queryFirstPointAt = (point) => {
    const features = map.queryRenderedFeatures(point, {layers: [layerId]});
    return features[0] || null;
  };

  const getBaseSourceOptions = () => getTileSourceSelectOptions(tileSources);

  const getCurrentBaseSourceId = () => activeBaseSource?.id || 'osm';

  const setBaseSource = async (nextSourceId) => {
    const nextBaseSource = resolveInitialBaseSource(tileSources, nextSourceId);
    const nextId = nextBaseSource?.id || 'osm';
    if (nextId === getCurrentBaseSourceId()) {
      return nextId;
    }

    const currentViewState = {
      center: map.getCenter(),
      zoom: map.getZoom(),
      bearing: map.getBearing(),
      pitch: map.getPitch()
    };

    map.setStyle(resolveStyle(nextBaseSource));
    await waitForNextStyleLoad(map);

    ensurePlacesSourceAndLayer();
    applyInteractionPolicy(map, mode);
    map.jumpTo(currentViewState);
    resizeNow();

    const source = map.getSource(sourceId);
    if (source) {
      source.setData({
        type: 'FeatureCollection',
        features: currentFeatures
      });
    }

    activeBaseSource = nextBaseSource;
    return nextId;
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

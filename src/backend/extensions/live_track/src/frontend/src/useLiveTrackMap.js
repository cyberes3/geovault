import { getCoordsSortedByTime, getTrackDirectionAngle, splitTrackIntoSegments } from './trackGeometry.js';
import { getArrowImageId, ensureArrowImage } from './trackArrowMap.js';
import {
  buildAccuracyCircleLayerSpec,
  DEFAULT_ACCURACY_CIRCLE_LAYER_ID,
  resolveSelectedTrackAccuracyMeters
} from './mapAccuracyCircle.js';
import { getRasterSourceSpec, getRasterLayerMaxZoom, replaceRasterBaseLayer } from './mapTileUtils.js';
import { setupMapFollowListeners } from './mapFollowLock.js';
import { isHiddenOwnedTracker } from './sharingSelectors.js';
import { createCoalescedTask } from './asyncTaskCoalescer.js';

const { isValidMapLngLatPair, setupCopyMapCoordinatesOnContextMenu } = window.gv_core;

/**
 * MapLibre GL JS loads lazily (see lazyMaplibreGl.js in core), so `window.gv_core.maplibre` may
 * still be null at the moment this module is first evaluated. Read it at call time in every
 * function that needs it instead of caching it once at module scope.
 */
function getMaplibreGl() {
  return window.gv_core?.maplibre || window.maplibregl || null;
}

const LINES_SOURCE_ID = 'live-track-lines';
const POINTS_SOURCE_ID = 'live-track-points';
const LINES_LAYER_ID = 'live-track-lines';
const LINES_WHITE_OUTLINE_LAYER_ID = 'live-track-lines-white-outline';
const LINES_BLACK_OUTLINE_LAYER_ID = 'live-track-lines-black-outline';
const POINTS_LAYER_ID = 'live-track-points';
const ACCURACY_CIRCLE_LAYER_ID = DEFAULT_ACCURACY_CIRCLE_LAYER_ID;
const BASE_SOURCE_ID = 'base-raster';
const BASE_LAYER_ID = 'base-raster-layer';
const MIN_ZOOM = 0;
const MAX_ZOOM = 18;
const LAYER_MAX_ZOOM = MAX_ZOOM + 1;
/** Duration (ms) for minimal map snap animations. Exported for the one direct `map.easeTo` fallback (empty-state "go home") the view still owns. */
export const MAP_SNAP_DURATION = 200;
const LAST_POINTS_FIT = 10;

const lineWhiteOutlineLayerSpec = {
  id: LINES_WHITE_OUTLINE_LAYER_ID,
  type: 'line',
  source: LINES_SOURCE_ID,
  paint: {
    'line-color': '#fff',
    'line-width': ['case', ['get', 'selected'], 7, 5],
    'line-opacity': 1
  },
  layout: { 'line-join': 'round', 'line-cap': 'round' }
};
const lineBlackOutlineLayerSpec = {
  id: LINES_BLACK_OUTLINE_LAYER_ID,
  type: 'line',
  source: LINES_SOURCE_ID,
  paint: {
    'line-color': '#000',
    'line-width': ['case', ['get', 'selected'], 6, 4],
    'line-opacity': 1
  },
  layout: { 'line-join': 'round', 'line-cap': 'round' }
};
const lineLayerSpec = {
  id: LINES_LAYER_ID,
  type: 'line',
  source: LINES_SOURCE_ID,
  paint: {
    'line-color': ['get', 'color'],
    'line-width': ['case', ['get', 'selected'], 3, 2],
    'line-opacity': 1
  },
  layout: { 'line-join': 'round', 'line-cap': 'round' }
};
const accuracyCircleLayerSpec = buildAccuracyCircleLayerSpec({
  layerId: ACCURACY_CIRCLE_LAYER_ID,
  sourceId: POINTS_SOURCE_ID
});
const pointsLayerSpec = {
  id: POINTS_LAYER_ID,
  type: 'symbol',
  source: POINTS_SOURCE_ID,
  layout: {
    'icon-image': ['get', 'iconImage'],
    'icon-size': 0.2,
    'icon-rotate': ['get', 'rotation'],
    'icon-anchor': 'center',
    'icon-pitch-alignment': 'viewport',
    'icon-rotation-alignment': 'map',
    'icon-allow-overlap': true,
    'icon-ignore-placement': true
  }
};

function hexToRgb(hex) {
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex || '#6C93DE');
  return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [51, 136, 255];
}

/**
 * Owns the MapLibre map instance and everything needed to render live tracks on it: layer/source
 * setup, base-layer switching, GeoJSON feature building, and camera moves (fit/center/pan).
 *
 * `updateMapFeatures()` is coalesced via {@link createCoalescedTask} so that bursts of calls
 * (e.g. several `track_updated` socket events arriving in the same frame) collapse into a single
 * `setData()`/layer rebuild instead of running once per call.
 *
 * View-specific reactions to map clicks (list tab switching, scrolling a row into view, etc.)
 * are intentionally NOT handled here - they're delegated to `onFeatureClick`/`onBackgroundClick`
 * so this composable only deals with map mechanics.
 *
 * @param {object} deps
 * @param {import('vue').Ref} deps.mapContainer
 * @param {import('vue').Ref} deps.tileSources
 * @param {import('vue').Ref<string>} deps.selectedLayer
 * @param {import('vue').Ref<Array>} deps.trackers
 * @param {import('vue').Ref} deps.selectedId
 * @param {import('vue').Ref} deps.activeGroupId
 * @param {import('vue').ComputedRef} deps.activeGroup
 * @param {import('vue').Ref<boolean>} deps.followLocked
 * @param {import('vue').Ref<boolean>} deps.isAutoMoving
 * @param {() => object} deps.getMapPadding
 * @param {(trackId: unknown) => void} [deps.onFeatureClick]
 * @param {() => void} [deps.onBackgroundClick]
 */
export function useLiveTrackMap({
  mapContainer,
  tileSources,
  selectedLayer,
  trackers,
  selectedId,
  activeGroupId,
  activeGroup,
  followLocked,
  isAutoMoving,
  getMapPadding,
  onFeatureClick,
  onBackgroundClick
}) {
  let map = null;
  const styleReadyListeners = [];

  function getMap() {
    return map;
  }

  /** Register a callback fired whenever the map's style (re)loads - e.g. to re-sync the user location marker. */
  function onStyleReady(cb) {
    if (typeof cb === 'function') styleReadyListeners.push(cb);
  }

  function notifyStyleReady() {
    for (const cb of styleReadyListeners) cb();
  }

  function buildLinesGeoJSON() {
    const groupId = activeGroupId.value;
    const groupTrackIds =
      groupId != null && activeGroup.value
        ? new Set((activeGroup.value.track_ids || []).map((id) => String(id)))
        : null;
    const features = [];
    for (const track of trackers.value) {
      if (isHiddenOwnedTracker(track)) continue;
      if (groupTrackIds != null && !groupTrackIds.has(String(track.id))) continue;
      const coordsSorted = getCoordsSortedByTime(track);
      const coords = coordsSorted.map((c) => [c[0], c[1]]);
      if (coords.length < 2) continue;
      const segments = splitTrackIntoSegments(coords);
      const props = {
        trackId: track.id,
        color: track.color || '#6C93DE',
        selected: selectedId.value === track.id
      };
      for (const segment of segments) {
        features.push({
          type: 'Feature',
          properties: props,
          geometry: { type: 'LineString', coordinates: segment }
        });
      }
    }
    return { type: 'FeatureCollection', features };
  }

  function buildPointsGeoJSON() {
    const groupId = activeGroupId.value;
    const groupTrackIds =
      groupId != null && activeGroup.value
        ? new Set((activeGroup.value.track_ids || []).map((id) => String(id)))
        : null;
    const features = [];
    for (const track of trackers.value) {
      if (isHiddenOwnedTracker(track)) continue;
      if (groupTrackIds != null && !groupTrackIds.has(String(track.id))) continue;
      const coordsSorted = getCoordsSortedByTime(track);
      const last = coordsSorted.length ? coordsSorted[coordsSorted.length - 1] : null;
      const pos = (last && last.length >= 2) ? [last[0], last[1]] : (track.last_position ? [track.last_position.lon, track.last_position.lat] : null);
      if (!pos) continue;
      const color = track.color || '#6C93DE';
      const selected = selectedId.value === track.id;
      const accuracy = resolveSelectedTrackAccuracyMeters(track, selected);
      const props = {
        trackId: track.id,
        color,
        selected,
        rotation: getTrackDirectionAngle(track),
        iconImage: getArrowImageId(color, selected),
        accuracy
      };
      if (accuracy > 0) props.colorRgb = hexToRgb(color);
      props.latitude = pos[1];
      features.push({
        type: 'Feature',
        properties: props,
        geometry: { type: 'Point', coordinates: pos }
      });
    }
    return { type: 'FeatureCollection', features };
  }

  async function runUpdateMapFeatures() {
    if (!map || !maplibregl) return;
    const lineSource = map.getSource(LINES_SOURCE_ID);
    const pointSource = map.getSource(POINTS_SOURCE_ID);
    if (lineSource) lineSource.setData(buildLinesGeoJSON());
    if (!pointSource) return;
    const pointsGeoJSON = buildPointsGeoJSON();
    const imageKeys = [...new Set(pointsGeoJSON.features.map((f) => `${f.properties.color}:${f.properties.selected}`))];
    await Promise.all(
      imageKeys.map((key) => {
        const lastColon = key.lastIndexOf(':');
        const color = key.slice(0, lastColon);
        const selected = key.slice(lastColon + 1) === 'true';
        return ensureArrowImage(map, color, selected);
      })
    );
    pointSource.setData(pointsGeoJSON);
  }

  /** Coalesced so bursts of live-update events collapse into one rebuild+setData per frame. */
  const updateMapFeatures = createCoalescedTask(runUpdateMapFeatures);

  async function addLiveTrackLayersAndData() {
    if (!map || !map.getStyle()) return;
    if (!map.getSource(LINES_SOURCE_ID)) {
      map.addSource(LINES_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    if (!map.getSource(POINTS_SOURCE_ID)) {
      map.addSource(POINTS_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    }
    if (!map.getLayer(POINTS_LAYER_ID)) {
      await ensureArrowImage(map, '#6C93DE', false);
      map.addLayer(pointsLayerSpec);
    }
    if (!map.getLayer(LINES_WHITE_OUTLINE_LAYER_ID)) {
      map.addLayer(lineWhiteOutlineLayerSpec, POINTS_LAYER_ID);
    }
    if (!map.getLayer(LINES_BLACK_OUTLINE_LAYER_ID)) {
      map.addLayer(lineBlackOutlineLayerSpec, POINTS_LAYER_ID);
    }
    if (!map.getLayer(LINES_LAYER_ID)) {
      map.addLayer(lineLayerSpec, POINTS_LAYER_ID);
    }
    if (!map.getLayer(ACCURACY_CIRCLE_LAYER_ID)) {
      map.addLayer(accuracyCircleLayerSpec, LINES_WHITE_OUTLINE_LAYER_ID);
    }
    await runUpdateMapFeatures();
  }

  /** Disable map rotation (drag, touch pinch, keyboard) so north stays up. See maplibre disable-map-rotation example. */
  function disableMapRotation() {
    if (!map) return;
    if (map.dragRotate && map.dragRotate.disable) map.dragRotate.disable();
    if (map.touchZoomRotate && map.touchZoomRotate.disableRotation) map.touchZoomRotate.disableRotation();
    if (map.keyboard && map.keyboard.disableRotation) map.keyboard.disableRotation();
  }

  function getClickableTrackLayers() {
    const TRACK_LAYER_IDS = [POINTS_LAYER_ID, LINES_LAYER_ID, LINES_BLACK_OUTLINE_LAYER_ID, LINES_WHITE_OUTLINE_LAYER_ID];
    return TRACK_LAYER_IDS.filter((id) => map.getLayer(id));
  }

  function setupMapFollowListenersForView() {
    if (!map) return;
    setupMapFollowListeners(map, {
      getLocked: () => followLocked.value,
      setLocked: (v) => { followLocked.value = v; if (!v) selectedId.value = null; }
    });
    map.on('click', (e) => {
      const layers = getClickableTrackLayers();
      if (layers.length === 0) return;
      const features = map.queryRenderedFeatures(e.point, { layers });
      const feature = features[0];
      if (feature?.properties?.trackId) {
        onFeatureClick?.(feature.properties.trackId);
      } else {
        onBackgroundClick?.();
      }
    });
    map.on('mousemove', (e) => {
      const canvas = map.getCanvas();
      if (!canvas) return;
      const layers = getClickableTrackLayers();
      if (layers.length === 0) {
        canvas.style.cursor = '';
        return;
      }
      const features = map.queryRenderedFeatures(e.point, { layers });
      canvas.style.cursor = features.length ? 'pointer' : '';
    });
    setupCopyMapCoordinatesOnContextMenu(map);
  }

  function getLastNCoords(track, n) {
    const coords = getCoordsSortedByTime(track);
    const slice = coords.length ? coords.slice(-n) : [];
    return slice.map((c) => [c[0], c[1]]);
  }

  function fitBoundsFromCoords(coords, { duration = MAP_SNAP_DURATION } = {}) {
    if (!map || !coords.length) return;
    let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;
    for (const c of coords) {
      const lon = c[0];
      const lat = c[1];
      if (!isValidMapLngLatPair(lon, lat)) continue;
      minLon = Math.min(minLon, lon);
      minLat = Math.min(minLat, lat);
      maxLon = Math.max(maxLon, lon);
      maxLat = Math.max(maxLat, lat);
    }
    if (minLon === Infinity) return;
    const pad = 0.002;
    if (maxLon <= minLon) { minLon -= pad; maxLon += pad; }
    if (maxLat <= minLat) { minLat -= pad; maxLat += pad; }
    map.fitBounds([[minLon, minLat], [maxLon, maxLat]], {
      padding: getMapPadding(),
      maxZoom: 15,
      duration
    });
  }

  /** @param {{ duration?: number }} [options] - pass `{ duration: 0 }` for an instant fit (e.g. right after map construction, before first paint). */
  function fitMapToTracks({ duration = MAP_SNAP_DURATION } = {}) {
    if (!map || trackers.value.length === 0) return;
    const allCoords = [];
    for (const track of trackers.value) {
      if (isHiddenOwnedTracker(track)) continue;
      allCoords.push(...getLastNCoords(track, LAST_POINTS_FIT));
    }
    fitBoundsFromCoords(allCoords, { duration });
  }

  function fitMapToSelectedTrack() {
    if (!map || !selectedId.value) return;
    const track = trackers.value.find((t) => t.id === selectedId.value);
    if (!track) return;
    const coords = getLastNCoords(track, LAST_POINTS_FIT);
    if (coords.length === 0) return;
    fitBoundsFromCoords(coords);
  }

  function fitMapToGroupTracks(group) {
    if (!map || !group?.track_ids?.length) return;
    const trackIds = new Set(group.track_ids.map((id) => String(id)));
    const coords = [];
    for (const track of trackers.value) {
      if (!trackIds.has(String(track.id))) continue;
      if (isHiddenOwnedTracker(track)) continue;
      coords.push(...getLastNCoords(track, LAST_POINTS_FIT));
    }
    if (coords.length === 0) return;
    fitBoundsFromCoords(coords);
  }

  /** Ease the camera to a single [lon, lat] pair, optionally zooming in to at least `minZoom`. */
  function panToPoint(coordPair, { minZoom } = {}) {
    if (!map || !coordPair || coordPair.length < 2) return;
    if (!isValidMapLngLatPair(coordPair[0], coordPair[1])) return;
    isAutoMoving.value = true;
    const zoom = minZoom != null ? Math.max(map.getZoom(), minZoom) : map.getZoom();
    map.easeTo({ center: coordPair, zoom, duration: MAP_SNAP_DURATION, padding: getMapPadding() });
    setTimeout(() => {
      isAutoMoving.value = false;
    }, MAP_SNAP_DURATION + 50);
  }

  /** Ease the camera to a track's last point (used by list/group click handlers). */
  function panToTrackLastPoint(track, { minZoom } = {}) {
    const coords = getLastNCoords(track, 1);
    if (coords.length === 0) return;
    panToPoint(coords[0], { minZoom });
  }

  function centerOnSelectedTrackLastPoint() {
    if (!map || !selectedId.value) return;
    const track = trackers.value.find((t) => t.id === selectedId.value);
    if (!track) return;
    panToPoint(getLastNCoords(track, 1)[0]);
  }

  let centerDebounceId = null;
  const CENTER_DEBOUNCE_MS = 220;

  /** Debounced camera re-center on the selected track's last point, for high-frequency live updates. */
  function scheduleCenterOnSelectedTrack() {
    if (centerDebounceId) clearTimeout(centerDebounceId);
    centerDebounceId = setTimeout(() => {
      centerDebounceId = null;
      if (followLocked.value && selectedId.value && map) centerOnSelectedTrackLastPoint();
    }, CENTER_DEBOUNCE_MS);
  }

  async function switchMapLayer(layerValue) {
    const maplibregl = getMaplibreGl();
    if (!map || !maplibregl) return;
    const tileSource = tileSources.value.find((s) => s.id === layerValue);
    if (!tileSource) return;
    const clientConfig = tileSource.client_config || {};
    const isStyleBased = !!(clientConfig.style_url || clientConfig.type === 'maptiler');

    if (isStyleBased) {
      const styleUrl = clientConfig.style_url;
      if (!styleUrl) return;
      const center = map.getCenter();
      const zoom = map.getZoom();
      const bearing = map.getBearing();
      map.setStyle(styleUrl);
      map.once('styledata', async () => {
        if (!map) return;
        map.resize();
        await addLiveTrackLayersAndData();
        notifyStyleReady();
        setTimeout(() => {
          if (map) {
            map.resize();
            map.jumpTo({ center, zoom, bearing, duration: 0 });
          }
        }, 0);
      });
    } else {
      const wasStyleBased = !map.getSource(BASE_SOURCE_ID);
      if (wasStyleBased) {
        const center = map.getCenter();
        const zoom = map.getZoom();
        const bearing = map.getBearing();
        map.remove();
        map = null;
        const rasterSpec = getRasterSourceSpec(layerValue, tileSource);
        const layerMaxZoom = getRasterLayerMaxZoom(clientConfig);
        const style = {
          version: 8,
          sources: {
            [BASE_SOURCE_ID]: rasterSpec,
            [LINES_SOURCE_ID]: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
            [POINTS_SOURCE_ID]: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } }
          },
          layers: [
            { id: BASE_LAYER_ID, type: 'raster', source: BASE_SOURCE_ID, minzoom: clientConfig.minzoom ?? 0, maxzoom: layerMaxZoom },
            accuracyCircleLayerSpec,
            lineWhiteOutlineLayerSpec,
            lineBlackOutlineLayerSpec,
            lineLayerSpec
          ]
        };
        map = new maplibregl.Map({
          container: mapContainer.value,
          style,
          center: [center.lng, center.lat],
          zoom,
          bearing,
          minZoom: MIN_ZOOM,
          maxZoom: MAX_ZOOM,
          maxPitch: 0,
          attributionControl: false
        });
        map.addControl(new maplibregl.NavigationControl({ showCompass: false, showZoom: true }), 'top-right');
        setupMapFollowListenersForView();
        disableMapRotation();
        map.once('load', () => {
          if (!map) return;
          map.resize();
          ensureArrowImage(map, '#6C93DE', false).then(() => {
            if (!map || !map.getStyle()) return;
            if (!map.getLayer(POINTS_LAYER_ID)) map.addLayer(pointsLayerSpec);
            runUpdateMapFeatures().then(() => {
              notifyStyleReady();
              setTimeout(() => {
                if (map) {
                  map.resize();
                  map.jumpTo({ center: [center.lng, center.lat], zoom, bearing, duration: 0 });
                }
              }, 0);
            }).catch(() => {
              setTimeout(() => {
                if (map) {
                  map.resize();
                  map.jumpTo({ center: [center.lng, center.lat], zoom, bearing, duration: 0 });
                }
              }, 0);
            });
          });
        });
      } else {
        const spec = getRasterSourceSpec(layerValue, tileSource);
        const layerMaxZoom = getRasterLayerMaxZoom(clientConfig);
        replaceRasterBaseLayer(map, {
          sourceId: BASE_SOURCE_ID,
          layerId: BASE_LAYER_ID,
          sourceSpec: spec,
          layerSpec: {
            id: BASE_LAYER_ID,
            type: 'raster',
            source: BASE_SOURCE_ID,
            minzoom: clientConfig.minzoom ?? 0,
            maxzoom: layerMaxZoom
          },
          insertBeforeLayerId: ACCURACY_CIRCLE_LAYER_ID
        });
      }
    }
  }

  function initMap() {
    const maplibregl = getMaplibreGl();
    if (!mapContainer.value || !maplibregl) return;

    const layerValue = selectedLayer.value;
    const tileSource = tileSources.value.find((s) => s.id === layerValue) || tileSources.value[0];
    const clientConfig = tileSource?.client_config || {};
    const isStyleBased = !!(clientConfig.style_url || clientConfig.type === 'maptiler');

    if (isStyleBased && clientConfig.style_url) {
      map = new maplibregl.Map({
        container: mapContainer.value,
        style: clientConfig.style_url,
        center: [0, 0],
        zoom: 2,
        minZoom: MIN_ZOOM,
        maxZoom: MAX_ZOOM,
        maxPitch: 0,
        attributionControl: false
      });
      map.addControl(new maplibregl.NavigationControl({ showCompass: false, showZoom: true }), 'top-right');
      setupMapFollowListenersForView();
      disableMapRotation();
      // Fit to the already-fetched tracker data now, before the browser paints the [0,0]/zoom 2
      // construction default, instead of waiting for 'load' and animating into place.
      fitMapToTracks({ duration: 0 });
      map.on('load', () => {
        if (!map) return;
        map.resize();
        addLiveTrackLayersAndData().then(() => {
          notifyStyleReady();
          setTimeout(() => {
            if (map) {
              map.resize();
              fitMapToTracks({ duration: 0 });
            }
          }, 0);
        }).catch(() => {
          setTimeout(() => {
            if (map) {
              map.resize();
              fitMapToTracks({ duration: 0 });
            }
          }, 0);
        });
      });
      return;
    }

    const rasterSpec = getRasterSourceSpec(layerValue, tileSource);
    const layerMaxZoom = getRasterLayerMaxZoom(clientConfig);
    const style = {
      version: 8,
      sources: {
        [BASE_SOURCE_ID]: rasterSpec,
        [LINES_SOURCE_ID]: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
        [POINTS_SOURCE_ID]: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } }
      },
      layers: [
        { id: BASE_LAYER_ID, type: 'raster', source: BASE_SOURCE_ID, minzoom: clientConfig.minzoom ?? 0, maxzoom: layerMaxZoom },
        accuracyCircleLayerSpec,
        lineWhiteOutlineLayerSpec,
        lineBlackOutlineLayerSpec,
        lineLayerSpec
      ]
    };

    map = new maplibregl.Map({
      container: mapContainer.value,
      style,
      center: [0, 0],
      zoom: 2,
      minZoom: MIN_ZOOM,
      maxZoom: MAX_ZOOM,
      maxPitch: 0,
      attributionControl: false
    });

    map.addControl(new maplibregl.NavigationControl({ showCompass: false, showZoom: true }), 'top-right');
    setupMapFollowListenersForView();
    disableMapRotation();

    // Fit to the already-fetched tracker data now, before the browser paints the [0,0]/zoom 2
    // construction default, instead of waiting for 'load' and animating into place.
    fitMapToTracks({ duration: 0 });

    map.once('load', () => {
      if (!map) return;
      map.resize();
      ensureArrowImage(map, '#6C93DE', false).then(() => {
        if (!map || !map.getStyle()) return;
        if (!map.getLayer(POINTS_LAYER_ID)) map.addLayer(pointsLayerSpec);
        runUpdateMapFeatures().then(() => {
          notifyStyleReady();
          setTimeout(() => {
            if (map) {
              map.resize();
              fitMapToTracks({ duration: 0 });
            }
          }, 0);
        }).catch(() => {
          setTimeout(() => {
            if (map) {
              map.resize();
              fitMapToTracks({ duration: 0 });
            }
          }, 0);
        });
      });
    });
  }

  function destroyMap() {
    if (centerDebounceId) {
      clearTimeout(centerDebounceId);
      centerDebounceId = null;
    }
    if (map) {
      map.remove();
      map = null;
    }
  }

  return {
    getMap,
    onStyleReady,
    initMap,
    destroyMap,
    switchMapLayer,
    updateMapFeatures,
    fitMapToTracks,
    fitMapToSelectedTrack,
    fitMapToGroupTracks,
    panToTrackLastPoint,
    centerOnSelectedTrackLastPoint,
    scheduleCenterOnSelectedTrack
  };
}

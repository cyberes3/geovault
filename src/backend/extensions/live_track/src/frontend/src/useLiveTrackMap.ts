import type { Ref, ComputedRef } from 'vue';
import type { Map as MapLibreMap, PaddingOptions } from 'maplibre-gl';
import { getCoordsSortedByTime, getTrackDirectionAngle, splitTrackIntoSegments } from './trackGeometry';
import { resolveTrackLastCoordinate } from './trackLastPoint';
import { getArrowImageId, ensureArrowImage } from './trackArrowMap';
import {
  buildAccuracyCircleLayerSpec,
  DEFAULT_ACCURACY_CIRCLE_LAYER_ID,
  resolveSelectedTrackAccuracyMeters
} from './mapAccuracyCircle';
import { getRasterSourceSpec, getRasterLayerMaxZoom, replaceRasterBaseLayer } from './mapTileUtils';
import { setupMapFollowListeners } from './mapFollowLock';
import { isHiddenOwnedTracker } from './sharingSelectors';
import { createCoalescedTask } from './asyncTaskCoalescer';
import type { LiveTrack, LiveTrackGroup } from './types/track';
import type { TileSource } from './types/gv-core';

type LonLat = [number, number];

const { isValidMapLngLatPair, setupCopyMapCoordinatesOnContextMenu } = window.gv_core;

/**
 * MapLibre GL JS loads lazily (see lazyMaplibreGl.js in core), so `window.gv_core.maplibre` may
 * still be null at the moment this module is first evaluated - and nothing else is guaranteed to
 * have loaded it before this view's own map needs it (e.g. navigating straight to a live-track
 * link without ever visiting the main map). Await the shared loader (idempotent/cached after the
 * first call) instead of assuming it's already populated.
 */
async function getMaplibreGl(): Promise<typeof import('maplibre-gl') | null> {
  return window.gv_core.maplibre ?? window.maplibregl ?? (await window.gv_core.loadMaplibreGl());
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
/** Duration (ms) for minimal map snap animations. Exported for the one direct `map.easeTo` fallback (empty-state "go home") the view still owns. */
export const MAP_SNAP_DURATION = 200;
const LAST_POINTS_FIT = 10;

const lineWhiteOutlineLayerSpec: Record<string, unknown> = {
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
const lineBlackOutlineLayerSpec: Record<string, unknown> = {
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
const lineLayerSpec: Record<string, unknown> = {
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
const pointsLayerSpec: Record<string, unknown> = {
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

function hexToRgb(hex: string | null | undefined): [number, number, number] {
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex ?? '#6C93DE');
  return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [51, 136, 255];
}

interface LineFeatureProps {
  trackId: string | number;
  color: string;
  selected: boolean;
}

interface PointFeatureProps {
  trackId: string | number;
  color: string;
  selected: boolean;
  rotation: number;
  iconImage: string;
  accuracy: number;
  colorRgb?: [number, number, number];
  latitude?: number;
}

interface LiveTrackGeoJSON<P> {
  type: 'FeatureCollection';
  features: Array<{ type: 'Feature'; properties: P; geometry: { type: 'LineString' | 'Point'; coordinates: LonLat | LonLat[] } }>;
}

export interface UseLiveTrackMapDeps {
  mapContainer: Ref<HTMLElement | null>;
  tileSources: Ref<TileSource[]>;
  selectedLayer: Ref<string>;
  trackers: Ref<LiveTrack[]>;
  selectedId: Ref<string | number | null>;
  activeGroupId: Ref<string | number | null>;
  activeGroup: ComputedRef<LiveTrackGroup | null | undefined>;
  followLocked: Ref<boolean>;
  getMapPadding: () => number | PaddingOptions;
  onFeatureClick?: (trackId: string | number) => void;
  onBackgroundClick?: () => void;
}

export interface FitMapToTracksOptions {
  duration?: number;
}

export interface PanToPointOptions {
  minZoom?: number;
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
  getMapPadding,
  onFeatureClick,
  onBackgroundClick
}: UseLiveTrackMapDeps) {
  let map: MapLibreMap | null = null;
  const styleReadyListeners: Array<() => void> = [];

  function getMap(): MapLibreMap | null {
    return map;
  }

  /** Register a callback fired whenever the map's style (re)loads - e.g. to re-sync the user location marker. */
  function onStyleReady(cb: () => void): void {
    if (typeof cb === 'function') styleReadyListeners.push(cb);
  }

  function notifyStyleReady(): void {
    for (const cb of styleReadyListeners) cb();
  }

  function buildLinesGeoJSON(): LiveTrackGeoJSON<LineFeatureProps> {
    const groupId = activeGroupId.value;
    const groupTrackIds =
      groupId != null && activeGroup.value
        ? new Set((activeGroup.value.track_ids ?? []).map((id) => String(id)))
        : null;
    const features: LiveTrackGeoJSON<LineFeatureProps>['features'] = [];
    for (const track of trackers.value) {
      if (isHiddenOwnedTracker(track)) continue;
      if (groupTrackIds != null && !groupTrackIds.has(String(track.id))) continue;
      const coordsSorted = getCoordsSortedByTime(track);
      const coords = coordsSorted.map((c): LonLat => [c[0], c[1]]);
      if (coords.length < 2) continue;
      const segments = splitTrackIntoSegments(coords);
      const props: LineFeatureProps = {
        trackId: track.id,
        color: track.color ?? '#6C93DE',
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

  function buildPointsGeoJSON(): LiveTrackGeoJSON<PointFeatureProps> {
    const groupId = activeGroupId.value;
    const groupTrackIds =
      groupId != null && activeGroup.value
        ? new Set((activeGroup.value.track_ids ?? []).map((id) => String(id)))
        : null;
    const features: LiveTrackGeoJSON<PointFeatureProps>['features'] = [];
    for (const track of trackers.value) {
      if (isHiddenOwnedTracker(track)) continue;
      if (groupTrackIds != null && !groupTrackIds.has(String(track.id))) continue;
      const last = resolveTrackLastCoordinate(track);
      const pos: LonLat | null = (last && last.length >= 2) ? [last[0], last[1]] : null;
      if (!pos) continue;
      const color = track.color ?? '#6C93DE';
      const selected = selectedId.value === track.id;
      const accuracy = resolveSelectedTrackAccuracyMeters(track, selected);
      const props: PointFeatureProps = {
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

  async function runUpdateMapFeatures(): Promise<void> {
    if (!map) return;
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

  async function addLiveTrackLayersAndData(): Promise<void> {
    if (!map?.getStyle()) return;
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
  function disableMapRotation(): void {
    if (!map) return;
    map.dragRotate.disable();
    map.touchZoomRotate.disableRotation();
    map.keyboard.disableRotation();
  }

  function getClickableTrackLayers(): string[] {
    const TRACK_LAYER_IDS = [POINTS_LAYER_ID, LINES_LAYER_ID, LINES_BLACK_OUTLINE_LAYER_ID, LINES_WHITE_OUTLINE_LAYER_ID];
    return TRACK_LAYER_IDS.filter((id) => map?.getLayer(id));
  }

  function setupMapFollowListenersForView(): void {
    if (!map) return;
    setupMapFollowListeners(map, {
      getLocked: () => followLocked.value,
      setLocked: (v) => { followLocked.value = v; if (!v) selectedId.value = null; }
    });
    map.on('click', (e) => {
      const layers = getClickableTrackLayers();
      if (layers.length === 0 || !map) return;
      const features = map.queryRenderedFeatures(e.point, { layers });
      const feature = features[0];
      const trackId = feature.properties?.trackId as string | number | undefined;
      if (trackId != null) {
        onFeatureClick?.(trackId);
      } else {
        onBackgroundClick?.();
      }
    });
    map.on('mousemove', (e) => {
      const canvas = map?.getCanvas();
      if (!canvas || !map) return;
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

  function getLastNCoords(track: LiveTrack, n: number): LonLat[] {
    if (n === 1) {
      const last = resolveTrackLastCoordinate(track);
      if (!last || last.length < 2) return [];
      return [[last[0], last[1]]];
    }
    const coords = getCoordsSortedByTime(track);
    const slice = coords.length ? coords.slice(-n) : [];
    return slice.map((c): LonLat => [c[0], c[1]]);
  }

  function fitBoundsFromCoords(coords: LonLat[], { duration = MAP_SNAP_DURATION }: FitMapToTracksOptions = {}): void {
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

  /** Pass `{ duration: 0 }` for an instant fit (e.g. right after map construction, before first paint). */
  function fitMapToTracks({ duration = MAP_SNAP_DURATION }: FitMapToTracksOptions = {}): void {
    if (!map || trackers.value.length === 0) return;
    const allCoords: LonLat[] = [];
    for (const track of trackers.value) {
      if (isHiddenOwnedTracker(track)) continue;
      allCoords.push(...getLastNCoords(track, LAST_POINTS_FIT));
    }
    fitBoundsFromCoords(allCoords, { duration });
  }

  function fitMapToSelectedTrack(): void {
    if (!map || !selectedId.value) return;
    const track = trackers.value.find((t) => t.id === selectedId.value);
    if (!track) return;
    const coords = getLastNCoords(track, LAST_POINTS_FIT);
    if (coords.length === 0) return;
    fitBoundsFromCoords(coords);
  }

  function fitMapToGroupTracks(group: LiveTrackGroup | null | undefined): void {
    if (!map || !group?.track_ids?.length) return;
    const trackIds = new Set(group.track_ids.map((id) => String(id)));
    const coords: LonLat[] = [];
    for (const track of trackers.value) {
      if (!trackIds.has(String(track.id))) continue;
      if (isHiddenOwnedTracker(track)) continue;
      coords.push(...getLastNCoords(track, LAST_POINTS_FIT));
    }
    if (coords.length === 0) return;
    fitBoundsFromCoords(coords);
  }

  /** Ease the camera to a single [lon, lat] pair, optionally zooming in to at least `minZoom`. */
  function panToPoint(coordPair: LonLat | null | undefined, { minZoom }: PanToPointOptions = {}): void {
    if (!map || !coordPair) return;
    if (!isValidMapLngLatPair(coordPair[0], coordPair[1])) return;
    const zoom = minZoom != null ? Math.max(map.getZoom(), minZoom) : map.getZoom();
    map.easeTo({ center: coordPair, zoom, duration: MAP_SNAP_DURATION, padding: getMapPadding() });
  }

  /** Ease the camera to a track's last point (used by list/group click handlers). */
  function panToTrackLastPoint(track: LiveTrack, { minZoom }: PanToPointOptions = {}): void {
    const coords = getLastNCoords(track, 1);
    if (coords.length === 0) return;
    panToPoint(coords[0], { minZoom });
  }

  function centerOnSelectedTrackLastPoint(): void {
    if (!map || !selectedId.value) return;
    const track = trackers.value.find((t) => t.id === selectedId.value);
    if (!track) return;
    panToPoint(getLastNCoords(track, 1)[0]);
  }

  let centerDebounceId: ReturnType<typeof setTimeout> | null = null;
  const CENTER_DEBOUNCE_MS = 220;

  /** Debounced camera re-center on the selected track's last point, for high-frequency live updates. */
  function scheduleCenterOnSelectedTrack(): void {
    if (centerDebounceId) clearTimeout(centerDebounceId);
    centerDebounceId = setTimeout(() => {
      centerDebounceId = null;
      if (followLocked.value && selectedId.value && map) centerOnSelectedTrackLastPoint();
    }, CENTER_DEBOUNCE_MS);
  }

  async function switchMapLayer(layerValue: string): Promise<void> {
    const maplibregl = await getMaplibreGl();
    if (!map || !maplibregl) return;
    const tileSource = tileSources.value.find((s) => s.id === layerValue);
    if (!tileSource) return;
    const clientConfig = tileSource.client_config ?? {};
    const isStyleBased = clientConfig.style_url != null || clientConfig.type === 'maptiler';

    if (isStyleBased) {
      const styleUrl = clientConfig.style_url;
      if (!styleUrl) return;
      const center = map.getCenter();
      const zoom = map.getZoom();
      const bearing = map.getBearing();
      map.setStyle(styleUrl);
      map.once('styledata', () => {
        if (!map) return;
        map.resize();
        addLiveTrackLayersAndData().then(() => {
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
        const style: Record<string, unknown> = {
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
          container: mapContainer.value ?? '',
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
            if (!map?.getStyle()) return;
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
          }).catch(() => {});
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

  async function initMap(): Promise<void> {
    const maplibregl = await getMaplibreGl();
    if (!mapContainer.value || !maplibregl) return;

    const layerValue = selectedLayer.value;
    const tileSource = tileSources.value.find((s) => s.id === layerValue) ?? tileSources.value[0];
    const clientConfig = tileSource.client_config ?? {};
    const isStyleBased = clientConfig.style_url != null || clientConfig.type === 'maptiler';

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
    const style: Record<string, unknown> = {
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
        if (!map?.getStyle()) return;
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
      }).catch(() => {});
    });
  }

  function destroyMap(): void {
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

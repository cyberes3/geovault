<template>
  <div class="flex-1 min-h-0 flex flex-col bg-gray-50 overflow-hidden">
    <div v-if="error" class="flex-1 flex flex-col items-center justify-center p-8 text-center">
      <p class="text-lg font-medium text-gray-900">{{ error }}</p>
      <p class="text-sm text-gray-500 mt-1">The share link may have been removed or expired.</p>
    </div>
    <div v-else-if="loading" class="flex-1 flex flex-col items-center justify-center p-8">
      <Loader size="md" message="Loading..." />
    </div>
    <template v-else>
      <header class="flex-shrink-0 px-4 py-2 bg-white border-b border-gray-200 flex items-center gap-2">
        <ShareIcon class="w-5 h-5 text-gray-500" />
        <h1 class="text-lg font-semibold text-gray-900 truncate">{{ trackName || 'Shared tracker' }}</h1>
      </header>
      <div ref="mapWrapperRef" class="relative flex-1 min-h-0 w-full">
        <div ref="mapContainer" class="absolute inset-0 w-full h-full bg-gray-100" />
        <SingleTrackMapControls
          :follow-locked="followLocked"
          :show-params-button="showParamsButton"
          @toggle-follow="toggleFollowLock"
          @open-params="openParamsSidebar"
          @open-layer="openLayerSidebar"
        />
      </div>
      <LatestParamsModal
        v-if="showParamsSidebar"
        :track="paramsTrack"
        :param-labels="{}"
        :container-ref="mapWrapperRef"
        :disable-animations="true"
        @close="showParamsSidebar = false"
      />
      <LiveTrackSidebar
        v-if="showLayerSidebar"
        title="Map Settings"
        :container-ref="mapWrapperRef"
        :disable-animations="true"
        @close="showLayerSidebar = false"
      >
        <MapLayerSidebar
          :tile-sources="tileSources"
          :selected-layer="selectedLayer"
          @update:selected-layer="onLayerSidebarChange"
        />
      </LiveTrackSidebar>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { ShareIcon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import LatestParamsModal from './LatestParamsModal.vue';
import LiveTrackSidebar from './LiveTrackSidebar.vue';
import MapLayerSidebar from './MapLayerSidebar.vue';
import SingleTrackMapControls from './SingleTrackMapControls.vue';
import { getCoordsSortedByTime, buildLineFeatures, buildPointFeature, fitMapToSingleTrack, centerMapOnTrackLastPoint } from './trackGeometry.js';
import { setupMapFollowListeners } from './mapFollowLock.js';
import { ensureArrowImage } from './trackArrowMap.js';
import { trackToParamsModalShape } from './trackParamsShape.js';
import { getRasterSourceSpec, getRasterLayerMaxZoom, replaceRasterBaseLayer } from './mapTileUtils.js';
import { useTileSources } from './useTileSources.js';

const BASE_URL = '/api/extensions/live-track/world/share';
const LINES_SOURCE_ID = 'world-share-lines';
const POINTS_SOURCE_ID = 'world-share-points';
const LINES_LAYER_ID = 'world-share-lines-layer';
const LINES_BLACK_OUTLINE_LAYER_ID = 'world-share-lines-layer-black-outline';
const POINTS_LAYER_ID = 'world-share-points-layer';
const BASE_SOURCE_ID = 'world-share-base';
const BASE_LAYER_ID = 'world-share-base-layer';
const MIN_ZOOM = 0;
const MAX_ZOOM = 18;
const LAYER_MAX_ZOOM = 19;
const POLL_INTERVAL_MS = 5000;

function getShareIdFromUrl() {
  const hash = typeof window !== 'undefined' ? window.location.hash : '';
  const q = hash.indexOf('?');
  if (q === -1) return null;
  const params = new URLSearchParams(hash.slice(q));
  return params.get('id');
}

export default {
  name: 'WorldShareView',
  components: { ShareIcon, SingleTrackMapControls, Loader, LatestParamsModal, LiveTrackSidebar, MapLayerSidebar },
  setup() {
    const loading = ref(true);
    const error = ref('');
    const trackName = ref('');
    const trackData = ref(null);
    const mapContainer = ref(null);
    const mapWrapperRef = ref(null);
    const { tileSources, selectedLayer, fetchTileSources } = useTileSources({ apiUrl: '/api/tiles/sources/' });
    const showLayerSidebar = ref(false);
    const showParamsSidebar = ref(false);
    function openParamsSidebar() {
      showLayerSidebar.value = false;
      showParamsSidebar.value = true;
    }

    function openLayerSidebar() {
      showParamsSidebar.value = false;
      showLayerSidebar.value = true;
    }

    function onLayerSidebarChange(layerId) {
      selectedLayer.value = layerId || selectedLayer.value;
      onLayerChange();
    }

    const followLocked = ref(false);
    const shareIdRef = ref(null);
    let map = null;
    let pollTimerId = null;

    const showParamsButton = computed(() => {
      const t = trackData.value;
      if (!t?.share_params_with_recipients) return false;
      const hasPoints = (t.point_params?.length || t.geometry?.coordinates?.length) > 0;
      return hasPoints;
    });

    const paramsTrack = computed(() => trackToParamsModalShape(trackData.value));

    async function updateMapData() {
      if (!map || !trackData.value) return;
      if (!map.getStyle()) return;
      const selected = followLocked.value;
      const lineSource = map.getSource(LINES_SOURCE_ID);
      const pointSource = map.getSource(POINTS_SOURCE_ID);
      if (lineSource) {
        const lineFeatures = buildLineFeatures(trackData.value, selected);
        lineSource.setData({ type: 'FeatureCollection', features: lineFeatures });
      }
      if (pointSource) {
        const color = trackData.value?.color || '#6C93DE';
        await ensureArrowImage(map, color, false);
        await ensureArrowImage(map, color, true);
        const pointFeature = buildPointFeature(trackData.value, selected);
        pointSource.setData({
          type: 'FeatureCollection',
          features: pointFeature ? [pointFeature] : []
        });
      }
    }

    async function addWorldShareTrackLayers() {
      if (!map || !map.getStyle()) return;
      if (!map.getSource(LINES_SOURCE_ID)) {
        map.addSource(LINES_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      }
      if (!map.getSource(POINTS_SOURCE_ID)) {
        map.addSource(POINTS_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      }
      if (!map.getLayer(LINES_LAYER_ID)) {
        map.addLayer({
          id: LINES_LAYER_ID,
          type: 'line',
          source: LINES_SOURCE_ID,
          paint: {
            'line-color': ['get', 'color'],
            'line-width': ['case', ['get', 'selected'], 3, 2],
            'line-opacity': 1
          },
          layout: { 'line-join': 'round', 'line-cap': 'round' }
        });
      }
      if (!map.getLayer(LINES_BLACK_OUTLINE_LAYER_ID)) {
        map.addLayer({
          id: LINES_BLACK_OUTLINE_LAYER_ID,
          type: 'line',
          source: LINES_SOURCE_ID,
          paint: {
            'line-color': '#000',
            'line-width': ['case', ['get', 'selected'], 6, 4],
            'line-opacity': 1
          },
          layout: { 'line-join': 'round', 'line-cap': 'round' }
        },
        LINES_LAYER_ID
      );
      }
      if (!map.getLayer(POINTS_LAYER_ID)) {
        const color = trackData.value?.color || '#6C93DE';
        await ensureArrowImage(map, color, false);
        await ensureArrowImage(map, color, true);
        if (map && map.getStyle() && !map.getLayer(POINTS_LAYER_ID)) {
          map.addLayer({
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
          });
        }
      }
      updateMapData();
    }

    function fitMapToTrack() {
      fitMapToSingleTrack(map, trackData.value);
    }

    function centerOnTrack() {
      centerMapOnTrackLastPoint(map, trackData.value);
    }

    function toggleFollowLock() {
      followLocked.value = !followLocked.value;
      if (followLocked.value) {
        centerOnTrack();
      }
      updateMapData();
    }

    function setupMapFollowListenersForView() {
      if (!map) return;
      setupMapFollowListeners(map, {
        getLocked: () => followLocked.value,
        setLocked: (v) => { followLocked.value = v; },
        onUnlock: () => updateMapData().catch(() => {})
      });
    }

    function onLayerChange() {
      if (!map) return;
      const maplibregl = window.gv_core?.maplibre || window.maplibregl;
      const tileSource = tileSources.value.find((s) => s.id === selectedLayer.value);
      if (!tileSource || !maplibregl) return;
      const clientConfig = tileSource.client_config || {};
      const isStyleBased = !!(clientConfig.style_url || clientConfig.type === 'maptiler');

      if (isStyleBased && clientConfig.style_url) {
        const center = map.getCenter();
        const zoom = map.getZoom();
        const bearing = map.getBearing();
        map.once('error', () => {
          if (!map) return;
          console.warn('WorldShareView: style failed to load, switching to OSM');
          const fallbackId = tileSources.value.find((s) => {
            const cc = s.client_config || {};
            return !cc.style_url && cc.type !== 'maptiler';
          })?.id || tileSources.value[0]?.id || 'osm';
          selectedLayer.value = fallbackId;
          map.remove();
          map = null;
          initMap().then(() => {
            if (map) {
              requestAnimationFrame(() => {
                if (map) map.jumpTo({ center: [center.lng, center.lat], zoom, bearing, duration: 0 });
              });
            }
          });
        });
        map.setStyle(clientConfig.style_url);
        map.once('styledata', async () => {
          if (!map) return;
          map.resize();
          await addWorldShareTrackLayers();
          requestAnimationFrame(() => {
            if (!map) return;
            map.resize();
            map.jumpTo({ center, zoom, bearing, duration: 0 });
          });
        });
        return;
      }

      const wasStyleBased = !map.getSource(BASE_SOURCE_ID);
      if (wasStyleBased) {
        const center = map.getCenter();
        const zoom = map.getZoom();
        const bearing = map.getBearing();
        map.remove();
        map = null;
        initMap().then(() => {
          if (map) {
            requestAnimationFrame(() => {
              if (map) {
                map.resize();
                map.jumpTo({ center: [center.lng, center.lat], zoom, bearing, duration: 0 });
              }
            });
          }
        });
        return;
      }

      const spec = getRasterSourceSpec(selectedLayer.value, tileSource);
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
        insertBeforeLayerId: LINES_LAYER_ID
      });
    }

    onMounted(async () => {
      const shareId = getShareIdFromUrl();
      if (!shareId) {
        error.value = 'Invalid share link';
        loading.value = false;
        return;
      }
      shareIdRef.value = shareId;
      try {
        const [infoRes, _] = await Promise.all([
          fetch(`${BASE_URL}/${encodeURIComponent(shareId)}/info/`),
          fetchTileSources()
        ]);
        if (!infoRes.ok) {
          error.value = 'Invalid share link';
          loading.value = false;
          return;
        }
        const info = await infoRes.json();
        trackName.value = info.track_name || 'Shared tracker';

        const dataRes = await fetch(`${BASE_URL}/${encodeURIComponent(shareId)}/`);
        if (!dataRes.ok) {
          error.value = 'Invalid share link';
          loading.value = false;
          return;
        }
        trackData.value = await dataRes.json();
        loading.value = false;

        await nextTick();
        await new Promise((r) => setTimeout(r, 50));
        await initMap();

        if (!error.value && shareIdRef.value) {
          pollTimerId = setInterval(async () => {
            if (!shareIdRef.value) return;
            try {
              const res = await fetch(`${BASE_URL}/${encodeURIComponent(shareIdRef.value)}/`);
              if (!res.ok) return;
              const data = await res.json();
              trackData.value = data;
              await updateMapData();
              if (followLocked.value && map) centerOnTrack();
            } catch (_) {
              // ignore poll errors
            }
          }, POLL_INTERVAL_MS);
        }
      } catch (e) {
        error.value = 'Failed to load share';
        loading.value = false;
      }
    });

    function initMap() {
      const maplibregl = window.gv_core?.maplibre || window.maplibregl;
      if (!mapContainer.value || !maplibregl || !trackData.value) {
        if (!maplibregl) console.warn('WorldShareView: MapLibre not available');
        return Promise.resolve();
      }

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
        map.on('error', (e) => {
          console.warn('WorldShareView: map error', e.error?.message || e);
        });
        return new Promise((resolve) => {
          map.once('load', async () => {
            if (!map) {
              resolve();
              return;
            }
            map.resize();
            await addWorldShareTrackLayers();
            setupMapFollowListenersForView();
            requestAnimationFrame(() => {
              if (!map) {
                resolve();
                return;
              }
              map.resize();
              fitMapToTrack();
              resolve();
            });
          });
        });
      }

      const baseSpec = getRasterSourceSpec(layerValue, tileSource);
      const layerMaxZoom = getRasterLayerMaxZoom(clientConfig);
      const lineFeatures = buildLineFeatures(trackData.value, false);
      const pointFeature = buildPointFeature(trackData.value, false);
      const lineGeoJSON = { type: 'FeatureCollection', features: lineFeatures };
      const pointGeoJSON = {
        type: 'FeatureCollection',
        features: pointFeature ? [pointFeature] : []
      };

      const style = {
        version: 8,
        sources: {
          [BASE_SOURCE_ID]: baseSpec,
          [LINES_SOURCE_ID]: { type: 'geojson', data: lineGeoJSON },
          [POINTS_SOURCE_ID]: { type: 'geojson', data: pointGeoJSON }
        },
        layers: [
          { id: BASE_LAYER_ID, type: 'raster', source: BASE_SOURCE_ID, minzoom: clientConfig.minzoom ?? 0, maxzoom: layerMaxZoom },
          {
            id: LINES_BLACK_OUTLINE_LAYER_ID,
            type: 'line',
            source: LINES_SOURCE_ID,
            paint: {
              'line-color': '#000',
              'line-width': ['case', ['get', 'selected'], 6, 4],
              'line-opacity': 1
            },
            layout: { 'line-join': 'round', 'line-cap': 'round' }
          },
          {
            id: LINES_LAYER_ID,
            type: 'line',
            source: LINES_SOURCE_ID,
            paint: {
              'line-color': ['get', 'color'],
              'line-width': ['case', ['get', 'selected'], 3, 2],
              'line-opacity': 1
            },
            layout: { 'line-join': 'round', 'line-cap': 'round' }
          }
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

      return new Promise((resolve) => {
        map.once('load', async () => {
          if (!map) {
            resolve();
            return;
          }
          await addWorldShareTrackLayers();
          setupMapFollowListenersForView();
          requestAnimationFrame(() => {
            if (!map) {
              resolve();
              return;
            }
            map.resize();
            fitMapToTrack();
            resolve();
          });
        });
      });
    }

    onBeforeUnmount(() => {
      if (pollTimerId) {
        clearInterval(pollTimerId);
        pollTimerId = null;
      }
      if (map) {
        map.remove();
        map = null;
      }
    });

    return {
      loading,
      error,
      trackName,
      mapContainer,
      mapWrapperRef,
      tileSources,
      selectedLayer,
      showLayerSidebar,
      showParamsSidebar,
      showParamsButton,
      paramsTrack,
      followLocked,
      openParamsSidebar,
      openLayerSidebar,
      onLayerSidebarChange,
      toggleFollowLock,
      onLayerChange
    };
  }
};
</script>

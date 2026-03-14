<template>
  <div class="flex-1 min-h-0 flex flex-col bg-gray-50">
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
      <div class="relative flex-1 w-full min-h-[300px]">
        <div ref="mapContainer" class="absolute inset-0 w-full h-full bg-gray-100" />
        <div class="absolute z-10 bottom-4 right-4 flex flex-col gap-2 bg-white border border-gray-200 rounded overflow-hidden">
          <button
            type="button"
            class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
            :title="followLocked ? 'Unlock map' : 'Lock map to tracker'"
            @click="toggleFollowLock"
          >
            <LockClosedIcon v-if="followLocked" class="w-5 h-5" />
            <LockOpenIcon v-else class="w-5 h-5" />
          </button>
          <button
            type="button"
            class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
            title="Map Settings"
            @click="showLayerModal = true"
          >
            <Square3Stack3DIcon class="w-5 h-5" />
          </button>
        </div>
      </div>
    </template>

    <BaseModal
      :is-open="showLayerModal"
      title="Map Settings"
      @close="showLayerModal = false"
    >
      <div class="p-4">
        <label for="public-share-layer-select" class="block text-sm font-medium text-gray-700 mb-2">Layer</label>
        <select
          id="public-share-layer-select"
          v-model="selectedLayer"
          class="w-full text-sm border border-gray-300 rounded-md px-2.5 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          title="Map layer"
          @change="onLayerChange"
        >
          <option v-for="source in tileSources" :key="source.id" :value="source.id">
            {{ source.name }}
          </option>
        </select>
      </div>
      <template #actions>
        <BaseButton variant="white" size="sm" @click="showLayerModal = false">
          Close
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { ShareIcon, Square3Stack3DIcon, LockClosedIcon, LockOpenIcon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import { getCoordsSortedByTime, buildLineFeatures, buildPointFeature } from './trackGeometry.js';
import { getArrowImageId, rasterizeArrowToImageData } from './trackArrowMap.js';
import { getRasterSourceSpec, getRasterLayerMaxZoom, defaultOsmSource } from './mapTileUtils.js';

const BASE_URL = '/api/extensions/live-track/public/share';
const TILE_SOURCES_API_URL = '/api/tiles/sources/';
const LINES_SOURCE_ID = 'public-share-lines';
const POINTS_SOURCE_ID = 'public-share-points';
const LINES_LAYER_ID = 'public-share-lines-layer';
const LINES_BLACK_OUTLINE_LAYER_ID = 'public-share-lines-layer-black-outline';
const POINTS_LAYER_ID = 'public-share-points-layer';
const BASE_SOURCE_ID = 'public-share-base';
const BASE_LAYER_ID = 'public-share-base-layer';
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
  name: 'PublicShareView',
  components: { ShareIcon, Square3Stack3DIcon, LockClosedIcon, LockOpenIcon, Loader, BaseModal, BaseButton },
  setup() {
    const loading = ref(true);
    const error = ref('');
    const trackName = ref('');
    const trackData = ref(null);
    const mapContainer = ref(null);
    const tileSources = ref([defaultOsmSource]);
    const selectedLayer = ref('osm');
    const showLayerModal = ref(false);
    const followLocked = ref(false);
    const shareIdRef = ref(null);
    let map = null;
    let pollTimerId = null;

    async function fetchTileSources() {
      try {
        const response = await fetch(TILE_SOURCES_API_URL);
        const data = await response.json();
        if (data.sources && Array.isArray(data.sources)) {
          tileSources.value = data.sources.filter((s) => !s.hidden);
        }
        if (tileSources.value.length === 0) {
          tileSources.value = [defaultOsmSource];
        }
        if (!tileSources.value.some((s) => s.id === selectedLayer.value)) {
          selectedLayer.value = tileSources.value[0]?.id || 'osm';
        }
      } catch (e) {
        console.warn('PublicShareView: fetch tile sources failed', e);
        tileSources.value = [defaultOsmSource];
        selectedLayer.value = 'osm';
      }
    }

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

    function ensureArrowImage(mapInstance, color, selected = false) {
      const id = getArrowImageId(color, selected);
      if (mapInstance.getStyle() && mapInstance.hasImage(id)) return Promise.resolve();
      return rasterizeArrowToImageData(color, selected).then((imageData) => {
        if (imageData && mapInstance && mapInstance.getStyle() && !mapInstance.hasImage(id)) {
          mapInstance.addImage(id, imageData, { pixelRatio: 1 });
        }
      });
    }

    async function addPublicShareTrackLayers() {
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
      if (!map || !trackData.value) return;
      const coords = getCoordsSortedByTime(trackData.value).map((c) => [c[0], c[1]]);
      if (coords.length >= 2) {
        const lons = coords.map((c) => c[0]);
        const lats = coords.map((c) => c[1]);
        map.fitBounds(
          [
            [Math.min(...lons), Math.min(...lats)],
            [Math.max(...lons), Math.max(...lats)]
          ],
          { padding: 40, maxZoom: 16, duration: 0 }
        );
      } else if (coords.length === 1) {
        map.jumpTo({ center: coords[0], zoom: 14, duration: 0 });
      }
    }

    function centerOnTrack() {
      if (!map || !trackData.value) return;
      const coords = getCoordsSortedByTime(trackData.value).map((c) => [c[0], c[1]]);
      const last = coords.length ? coords[coords.length - 1] : null;
      if (!last) return;
      map.panTo(last, { duration: 200 });
    }

    function toggleFollowLock() {
      followLocked.value = !followLocked.value;
      if (followLocked.value) {
        centerOnTrack();
      }
      updateMapData();
    }

    function setupMapFollowListeners() {
      if (!map) return;
      const breakLock = () => {
        if (!followLocked.value) return;
        followLocked.value = false;
        updateMapData().catch(() => {
          // ignore transient map update errors while styles/sources are reloading
        });
      };
      map.on('dragstart', breakLock);
      map.on('wheel', breakLock);
      map.on('zoomstart', (e) => {
        const type = e.originalEvent?.type;
        if (type === 'touchstart' || type === 'touchmove' || type === 'wheel') {
          breakLock();
        }
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
          console.warn('PublicShareView: style failed to load, switching to OSM');
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
          await addPublicShareTrackLayers();
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

      if (map.getLayer(BASE_LAYER_ID)) map.removeLayer(BASE_LAYER_ID);
      if (map.getSource(BASE_SOURCE_ID)) map.removeSource(BASE_SOURCE_ID);
      const spec = getRasterSourceSpec(selectedLayer.value, tileSource);
      const layerMaxZoom = getRasterLayerMaxZoom(clientConfig);
      map.addSource(BASE_SOURCE_ID, spec);
      map.addLayer(
        {
          id: BASE_LAYER_ID,
          type: 'raster',
          source: BASE_SOURCE_ID,
          minzoom: clientConfig.minzoom ?? 0,
          maxzoom: layerMaxZoom
        },
        LINES_LAYER_ID
      );
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
        if (!maplibregl) console.warn('PublicShareView: MapLibre not available');
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
          console.warn('PublicShareView: map error', e.error?.message || e);
        });
        return new Promise((resolve) => {
          map.once('load', async () => {
            if (!map) {
              resolve();
              return;
            }
            map.resize();
            await addPublicShareTrackLayers();
            setupMapFollowListeners();
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
          await addPublicShareTrackLayers();
          setupMapFollowListeners();
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
      tileSources,
      selectedLayer,
      showLayerModal,
      followLocked,
      toggleFollowLock,
      onLayerChange
    };
  }
};
</script>

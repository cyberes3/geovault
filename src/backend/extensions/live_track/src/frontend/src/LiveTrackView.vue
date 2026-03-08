<template>
  <div class="h-full min-h-0 relative flex flex-col sm:grid sm:grid-cols-[1fr_3fr] sm:grid-rows-[auto_1fr] bg-gray-50 overflow-hidden">
    <!-- Header: Absolute on mobile, static on desktop -->
    <header class="z-30 h-16 px-4 py-2 flex items-center justify-between gap-3 flex-shrink-0 bg-white border-b border-gray-200 sm:bg-gray-50 sm:col-span-1">
      <h2 class="text-xl font-bold text-gray-900 truncate min-w-0 tracking-tight">Trackers</h2>
      <div class="flex items-center gap-2 flex-shrink-0">
        <select
          v-model="sortBy"
          class="select-custom text-sm px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          title="Sort by"
        >
          <option value="alphabetical">Name</option>
          <option value="last_updated">Activity</option>
          <option value="num_points">Points</option>
          <option value="newest">Created</option>
        </select>
        <button
          type="button"
          title="Add track"
          class="px-2.5 py-2 rounded-md bg-blue-600 text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1"
          @click="openCreateModal"
        >
          <PlusIcon class="h-5 w-5" />
        </button>
      </div>
    </header>

    <!-- Main Content (Map) -->
    <main class="flex-1 relative min-h-0 sm:col-start-2 sm:row-start-1 sm:row-span-2">
      <div ref="mapContainer" class="absolute inset-0 w-full h-full bg-gray-100" />
      
      <!-- In-Map Overlays (Bottom Left) -->
      <div class="absolute z-10 bottom-4 left-4 flex flex-col gap-2 bg-white border border-gray-200 rounded overflow-hidden">
        <button
          type="button"
          class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
          title="Map Settings"
          @click="showLayerModal = true"
        >
          <Square3Stack3DIcon class="w-5 h-5" />
        </button>
        <button
          type="button"
          class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
          title="Go to home extent"
          @click="goHome"
        >
          <HomeIcon class="w-5 h-5" />
        </button>
      </div>
    </main>

    <!-- Tracker List: Desktop sidebar (hidden on small viewports via hidden sm:flex) -->
    <aside
      class="hidden sm:flex z-20 flex flex-col w-full h-full min-h-0 border-r border-gray-200 bg-white"
    >
      <div class="flex-1 min-h-0 overflow-hidden flex flex-col px-2 pt-2 pb-2">
        <div v-if="loading" class="flex-1 flex flex-col items-center justify-center p-4">
          <Loader size="md" :show-message="false" layout="inline" />
          <p class="text-sm text-black mt-4">Loading...</p>
        </div>

        <div v-else-if="sortedTrackers.length === 0" class="flex-1 flex flex-col items-center justify-center py-12 px-6 text-center">
          <div class="w-16 h-16 bg-gray-50 rounded-lg flex items-center justify-center mb-4 text-gray-400">
            <PlusIcon class="w-8 h-8" />
          </div>
          <h3 class="text-base font-semibold text-gray-900 mb-1">No trackers yet</h3>
          <p class="text-sm text-gray-500 max-w-xs">Start by creating your first tracker to begin recording data.</p>
        </div>

        <div
          v-else
          ref="listScrollContainerDesktop"
          class="flex-1 min-h-0 overflow-y-auto space-y-3 px-1 py-1 custom-scrollbar"
          @click.self="highlightedId = null"
        >
          <div
            v-for="track in sortedTrackers"
            :key="track.id"
            :data-track-id="track.id"
            :class="[
              'group flex items-center gap-3 p-4 rounded-2xl cursor-pointer border transition-all',
              selectedId === track.id
                ? 'border-blue-500 bg-blue-100'
                : 'border-blue-100 bg-white hover:bg-blue-50 hover:border-blue-300',
              highlightedId === track.id && selectedId !== track.id ? 'ring-2 ring-blue-500' : ''
            ]"
            @click="onTrackListClick(track)"
          >
            <div 
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
              :style="{ borderLeftColor: track.color || '#3388ff', borderLeftWidth: '4px' }"
            >
              <TrackDirectionIcon
                :color="track.color || '#3388ff'"
                :angle="getTrackDirectionAngle(track)"
                :size="26"
                :selected="selectedId === track.id"
                reserve-circle
              />
            </div>
            
            <div class="flex-1 min-w-0">
              <div class="font-bold text-gray-900 truncate tracking-tight break-all" :title="track.name">{{ track.name }}</div>
              <div class="flex items-center gap-1.5 mt-0.5">
                <div class="text-xs font-medium text-gray-500 truncate">
                  {{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'Waiting for data...' }}
                </div>
              </div>
            </div>

            <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 sm:opacity-60 focus-within:opacity-100 transition-opacity">
              <button
                type="button"
                title="Latest params"
                class="p-2 rounded-xl text-gray-400 hover:text-gray-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="paramsModalTrackId = track.id"
              >
                <TableCellsIcon class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Edit"
                class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="openEditModal(track)"
              >
                <PencilIcon class="h-5 w-5" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </aside>

    <!-- Tracker List: Mobile bottom sheet -->
    <BottomSheet
      v-model="isSheetOpen"
      :blocking="true"
      :can-swipe-close="false"
      :snap-points="[80, '60%']"
      :initial-snap-point="0"
      :swipe-close-threshold="0.9"
    >
      <template #header>
        <div class="w-full flex justify-center py-4 cursor-grab active:cursor-grabbing">
          <!-- The handle is automatically injected by :before, but we add space for it -->
          <div class="h-1" />
        </div>
      </template>
      <div class="flex-1 min-h-0 overflow-hidden flex flex-col px-2 pb-2 h-full">
        <div v-if="loading" class="flex-1 flex flex-col items-center justify-center p-4">
          <Loader size="md" :show-message="false" layout="inline" />
          <p class="text-sm text-black mt-4">Loading...</p>
        </div>
        <div v-else-if="sortedTrackers.length === 0" class="flex-1 flex flex-col items-center justify-center py-12 px-6 text-center">
          <div class="w-16 h-16 bg-gray-50 rounded-lg flex items-center justify-center mb-4 text-gray-400">
            <PlusIcon class="w-8 h-8" />
          </div>
          <h3 class="text-base font-semibold text-gray-900 mb-1">No trackers yet</h3>
          <p class="text-sm text-gray-500 max-w-xs">Start by creating your first tracker to begin recording data.</p>
        </div>
        <div
          v-else
          ref="listScrollContainerMobile"
          class="flex-1 min-h-0 overflow-y-auto space-y-3 px-1 py-1 custom-scrollbar"
          @click.self="highlightedId = null"
        >
          <div
            v-for="track in sortedTrackers"
            :key="track.id"
            :data-track-id="track.id"
            :class="[
              'group flex items-center gap-3 p-4 rounded-2xl cursor-pointer border transition-all',
              selectedId === track.id
                ? 'border-blue-500 bg-blue-100'
                : 'border-blue-100 bg-white hover:bg-blue-50 hover:border-blue-300',
              highlightedId === track.id && selectedId !== track.id ? 'ring-2 ring-blue-500' : ''
            ]"
            @click="onTrackListClick(track)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
              :style="{ borderLeftColor: track.color || '#3388ff', borderLeftWidth: '4px' }"
            >
              <TrackDirectionIcon
                :color="track.color || '#3388ff'"
                :angle="getTrackDirectionAngle(track)"
                :size="26"
                :selected="selectedId === track.id"
                reserve-circle
              />
            </div>
            <div class="flex-1 min-w-0">
              <div class="font-bold text-gray-900 truncate tracking-tight break-all" :title="track.name">{{ track.name }}</div>
              <div class="flex items-center gap-1.5 mt-0.5">
                <div class="text-xs font-medium text-gray-500 truncate">
                  {{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'Waiting for data...' }}
                </div>
              </div>
            </div>
            <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 sm:opacity-60 focus-within:opacity-100 transition-opacity">
              <button
                type="button"
                title="Latest params"
                class="p-2 rounded-xl text-gray-400 hover:text-gray-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="paramsModalTrackId = track.id"
              >
                <TableCellsIcon class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Edit"
                class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="openEditModal(track)"
              >
                <PencilIcon class="h-5 w-5" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </BottomSheet>

    <BaseModal
      :is-open="showLayerModal"
      title="Map Settings"
      @close="showLayerModal = false"
    >
      <div class="p-4">
        <label for="live-track-layer-select" class="block text-sm font-medium text-gray-700 mb-2">Layer</label>
        <select
          id="live-track-layer-select"
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

    <TrackModal
      v-if="showModal"
      :mode="modalMode"
      :track="modalTrack"
      :loading="modalTrackLoading"
      :user-login="userLogin"
      @close="showModal = false"
      @saved="onModalSaved"
      @deleted="onTrackDeleted"
    />
    <LatestParamsModal
      v-if="paramsModalTrackId != null"
      :track="paramsModalTrack"
      :param-labels="paramLabels"
      @close="paramsModalTrackId = null"
    />
  </div>
</template>

<script>
import { ref, computed, onMounted, onActivated, onBeforeUnmount, inject, watch, nextTick } from 'vue';
import { PlusIcon, PencilIcon, HomeIcon, Square3Stack3DIcon, TableCellsIcon } from '@heroicons/vue/24/outline';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import { getIngressBodyTemplate } from './ingressBodyTemplateCache.js';
import { trackersLiveSocket } from './trackersLiveSocket.js';
import TrackModal from './TrackModal.vue';
import TrackDirectionIcon from './TrackDirectionIcon.vue';
import LatestParamsModal from './LatestParamsModal.vue';
import BottomSheet from '@douxcode/vue-spring-bottom-sheet';
import '@douxcode/vue-spring-bottom-sheet/dist/style.css';

const maplibregl = window.gv_core?.maplibre || window.maplibregl;

const LINES_SOURCE_ID = 'live-track-lines';
const POINTS_SOURCE_ID = 'live-track-points';
const LINES_LAYER_ID = 'live-track-lines';
const POINTS_LAYER_ID = 'live-track-points';
const ACCURACY_CIRCLE_LAYER_ID = 'live-track-accuracy-circle';
const BASE_SOURCE_ID = 'base-raster';
const BASE_LAYER_ID = 'base-raster-layer';
const MIN_ZOOM = 0;
const MAX_ZOOM = 18;
/** Do not draw track across jumps larger than this (meters). 100 miles. Same as Android tracker. */
const MAX_JUMP_METERS = 100 * 1609.344;
const LAYER_MAX_ZOOM = MAX_ZOOM + 1;
const TILE_SOURCES_API_URL = '/api/tiles/sources/';
const DEFAULT_MAP_KEY = 'extensions.live_track.default_map';
const DEFAULT_SORT_KEY = 'extensions.live_track.default_sort';
const VALID_SORT_VALUES = new Set(['alphabetical', 'last_updated', 'num_points', 'newest']);
const CENTER_DEBOUNCE_MS = 220;
/** Duration (ms) for minimal map snap animations. */
const MAP_SNAP_DURATION = 200;
const ARROW_PATH_D =
  'M29.9,28.6l-13-26c-0.3-0.7-1.4-0.7-1.8,0l-13,26c-0.2,0.4-0.1,0.8,0.2,1.1C2.5,30,3,30.1,3.4,29.9L16,25.1l12.6,4.9c0.1,0,0.2,0.1,0.4,0.1c0.3,0,0.5-0.1,0.7-0.3C30,29.4,30.1,28.9,29.9,28.6z';

function getArrowImageId(color, selected) {
  const base = (color || '#3388ff').replace('#', '');
  return 'track-arrow-' + (selected ? 'selected-' : '') + base;
}

/** 96px gives more source pixels for MapLibre's LINEAR sampling so scaled-down icons look cleaner (see draw_symbol.ts). */
const ARROW_RASTER_SIZE = 96;

/** SVG data URL for the direction arrow. Same chevron for both; selected adds white circle with black border. */
function getTrackArrowDataURL(color, selected) {
  const fill = color || '#3388ff';
  const circle =
    selected
      ? '<circle cx="16" cy="16" r="15" fill="white" stroke="#000" stroke-width="1.5"/>'
      : '';
  const pathTransform = ' transform="translate(16,2.6) scale(0.8) translate(-16,-2.6)"';
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="' + ARROW_RASTER_SIZE + '" height="' + ARROW_RASTER_SIZE + '" shape-rendering="geometricPrecision">' +
    circle +
    '<path' + pathTransform + ' fill="' + fill + '" stroke="#000" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" stroke-miterlimit="10" shape-rendering="geometricPrecision" d="' + ARROW_PATH_D + '"/>' +
    '</svg>';
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

/**
 * Rasterize arrow SVG and return MapLibre image spec { width, height, data } so sprite size stays consistent.
 * Passing a canvas can cause "mismatched image size" when the sprite reads dimensions; explicit data avoids that.
 */
function rasterizeArrowToImageData(color, selected) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = ARROW_RASTER_SIZE;
      canvas.height = ARROW_RASTER_SIZE;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        resolve(null);
        return;
      }
      ctx.imageSmoothingEnabled = true;
      if (ctx.imageSmoothingQuality) ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, ARROW_RASTER_SIZE, ARROW_RASTER_SIZE);
      const imageData = ctx.getImageData(0, 0, ARROW_RASTER_SIZE, ARROW_RASTER_SIZE);
      resolve({
        width: ARROW_RASTER_SIZE,
        height: ARROW_RASTER_SIZE,
        data: new Uint8Array(imageData.data)
      });
    };
    img.onerror = () => resolve(null);
    img.src = getTrackArrowDataURL(color, selected);
  });
}

export default {
  name: 'LiveTrackView',
  components: { BaseModal, TrackModal, TrackDirectionIcon, LatestParamsModal, PlusIcon, PencilIcon, HomeIcon, Square3Stack3DIcon, TableCellsIcon, BottomSheet },
  setup() {
    const api = inject('extensionApi');
    const trackers = ref([]);
    const sortBy = ref('alphabetical');
    const loading = ref(true);
    const selectedId = ref(null);
    const followLocked = ref(false);
    const isAutoMoving = ref(false);
    const isMobileView = ref(
      typeof window !== 'undefined' ? window.matchMedia('(max-width: 639px)').matches : false
    );
    const isSheetOpen = ref(false);
    let mobileQueryListener = null;

    function isRecentlyUpdated(track) {
      if (!track.last_timestamp_ms) return false;
      const fiveMinutesAgo = Date.now() - 5 * 60 * 1000;
      return track.last_timestamp_ms > fiveMinutesAgo;
    }

    const sortedTrackers = computed(() => {
      const list = [...trackers.value];
      switch (sortBy.value) {
        case 'alphabetical':
          return list.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }));
        case 'last_updated':
          return list.sort((a, b) => (b.last_timestamp_ms ?? 0) - (a.last_timestamp_ms ?? 0));
        case 'num_points': {
          const len = (t) => (t.geometry?.coordinates?.length ?? 0);
          return list.sort((a, b) => len(b) - len(a));
        }
        case 'newest': {
          const ts = (t) => (t.created_at ? new Date(t.created_at).getTime() : 0);
          return list.sort((a, b) => ts(b) - ts(a));
        }
        default:
          return list.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }));
      }
    });
    const showModal = ref(false);
    const showLayerModal = ref(false);
    const paramsModalTrackId = ref(null);
    const paramsModalTrack = computed(() => {
      const id = paramsModalTrackId.value;
      if (id == null) return null;
      const t = trackers.value.find((tr) => tr.id === id);
      return t ?? null;
    });
    const paramLabels = ref({});
    const modalMode = ref('create');
    const modalTrack = ref(null);
    const modalTrackLoading = ref(false);
    const mapContainer = ref(null);
    const listScrollContainerDesktop = ref(null);
    const listScrollContainerMobile = ref(null);
    const listScrollContainer = computed(() =>
      isMobileView.value ? listScrollContainerMobile.value : listScrollContainerDesktop.value
    );
    const highlightedId = ref(null);
    const userLogin = ref('');
    const tileSources = ref([]);
    const selectedLayer = ref('osm');
    let map = null;
    let trackUpdatedHandler = null;
    let centerDebounceId = null;

    async function fetchTileSources() {
      try {
        const response = await fetch(TILE_SOURCES_API_URL);
        const data = await response.json();
        if (data.sources && Array.isArray(data.sources)) {
          tileSources.value = data.sources.filter((s) => !s.hidden);
        }
        if (tileSources.value.length === 0) {
          tileSources.value = [{
            id: 'osm',
            name: 'OpenStreetMap',
            type: 'osm',
            requires_proxy: false,
            client_config: {
              type: 'osm',
              url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
              tileSize: 256,
              attribution: '© OpenStreetMap'
            }
          }];
        }
        applyDefaultMapFromStore();
        if (!tileSources.value.some((s) => s.id === selectedLayer.value)) {
          selectedLayer.value = tileSources.value[0]?.id || 'osm';
        }
      } catch (e) {
        console.error('Live Track: fetch tile sources failed', e);
        tileSources.value = [{
          id: 'osm',
          name: 'OpenStreetMap',
          type: 'osm',
          requires_proxy: false,
          client_config: {
            type: 'osm',
            url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            tileSize: 256,
            attribution: '© OpenStreetMap'
          }
        }];
        selectedLayer.value = 'osm';
      }
    }

    function formatTime(ms) {
      if (!ms) return '';
      const d = new Date(ms);
      return d.toLocaleString();
    }

    /** Degrees from north (0 = up), clockwise. Uses two most recent points by time (sorted so order is reliable). */
    function getTrackDirectionAngle(track) {
      const coords = getCoordsSortedByTime(track);
      if (coords.length < 2) return 0;
      const prev = coords[coords.length - 2];
      const last = coords[coords.length - 1];
      const dLon = last[0] - prev[0];
      const dLat = last[1] - prev[1];
      if (dLon === 0 && dLat === 0) return 0;
      return (Math.atan2(dLon, dLat) * 180) / Math.PI;
    }

    function normalizeTrackForMemory(track) {
      const geom = track.geometry || { type: 'LineString', coordinates: [] };
      const coords = geom.coordinates || [];
      const last = coords[coords.length - 1];
      // Use last_point from metadata when geometry has no coordinates (e.g. list or failed geometry fetch)
      const lastPoint = last ?? track.last_point;
      const { point_params, last_point: _lp, ...rest } = track;
      const latestPointParams = (point_params && point_params.length)
        ? point_params[point_params.length - 1]
        : {};
      return {
        ...rest,
        geometry: geom,
        last_position: lastPoint && lastPoint.length >= 2 ? { lon: lastPoint[0], lat: lastPoint[1] } : null,
        last_timestamp_ms: lastPoint && lastPoint.length >= 3 ? lastPoint[2] : null,
        latestPointParams
      };
    }

    async function fetchTrackers() {
      loading.value = true;
      try {
        const res = await api.get('/trackers/');
        const raw = Array.isArray(res.data) ? res.data : [];
        // Fetch full geometry for each tracker (list endpoint returns metadata only)
        const withGeometry = await Promise.all(
          raw.map(async (t) => {
            try {
              const geomRes = await api.get(`/trackers/${t.id}/geometry/`);
              return normalizeTrackForMemory(geomRes.data);
            } catch {
              return normalizeTrackForMemory({ ...t, geometry: { type: 'LineString', coordinates: [] } });
            }
          })
        );
        trackers.value = withGeometry;
        if (withGeometry.length === 1 && !selectedId.value) selectedId.value = withGeometry[0].id;
        updateMapFeatures();
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load trackers');
        }
      } finally {
        loading.value = false;
      }
    }

    /** Distance in meters between two [lon, lat] points (Haversine). */
    function distanceMeters(lon1, lat1, lon2, lat2) {
      const R = 6371000;
      const dLat = ((lat2 - lat1) * Math.PI) / 180;
      const dLon = ((lon2 - lon1) * Math.PI) / 180;
      const a =
        Math.sin(dLat / 2) ** 2 +
        Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
      const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
      return R * c;
    }

    /** Returns track coordinates sorted by timestamp ascending (oldest first) so the track always draws in chronological order. */
    function getCoordsSortedByTime(track) {
      const geom = track.geometry || {};
      const coords = geom.coordinates || [];
      if (coords.length <= 1) return [...coords];
      return [...coords].sort((a, b) => {
        const ta = typeof a[2] === 'number' ? a[2] : 0;
        const tb = typeof b[2] === 'number' ? b[2] : 0;
        return ta - tb;
      });
    }

    /**
     * Split track coordinates into segments so that no segment spans more than MAX_JUMP_METERS.
     * Consecutive points farther apart than that start a new segment (the jump is not drawn).
     * coords: array of [lon, lat]. Returns array of segments (each segment is [lon, lat][]).
     */
    function splitTrackIntoSegments(coords) {
      if (coords.length < 2) return [];
      const segments = [];
      let current = [coords[0]];
      for (let i = 1; i < coords.length; i++) {
        const prev = coords[i - 1];
        const curr = coords[i];
        const dist = distanceMeters(prev[0], prev[1], curr[0], curr[1]);
        if (dist > MAX_JUMP_METERS) {
          if (current.length >= 2) segments.push(current);
          current = [curr];
        } else {
          current.push(curr);
        }
      }
      if (current.length >= 2) segments.push(current);
      return segments;
    }

    function buildLinesGeoJSON() {
      const features = [];
      for (const track of trackers.value) {
        const coordsSorted = getCoordsSortedByTime(track);
        const coords = coordsSorted.map((c) => [c[0], c[1]]);
        if (coords.length < 2) continue;
        const segments = splitTrackIntoSegments(coords);
        const props = {
          trackId: track.id,
          color: track.color || '#3388ff',
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

    function hexToRgb(hex) {
      const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex || '#3388ff');
      return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [51, 136, 255];
    }

    function buildPointsGeoJSON() {
      const features = [];
      for (const track of trackers.value) {
        const coordsSorted = getCoordsSortedByTime(track);
        const last = coordsSorted.length ? coordsSorted[coordsSorted.length - 1] : null;
        const pos = (last && last.length >= 2) ? [last[0], last[1]] : (track.last_position ? [track.last_position.lon, track.last_position.lat] : null);
        if (!pos) continue;
        const color = track.color || '#3388ff';
        const selected = selectedId.value === track.id;
        const acc =
          track.latestPointParams?.acc ?? track.point_params?.[track.point_params?.length - 1]?.acc;
        const accuracy =
          selected && typeof acc === 'number' && Number.isFinite(acc) && acc > 0 ? acc : 0;
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

    function ensureArrowImage(color, selected) {
      const id = getArrowImageId(color, selected);
      if (map.hasImage(id)) return Promise.resolve();
      return rasterizeArrowToImageData(color, selected).then((imageData) => {
        if (imageData && map && map.getStyle() && !map.hasImage(id)) {
          map.addImage(id, imageData, { pixelRatio: 1 });
        }
      });
    }

    async function updateMapFeatures() {
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
          return ensureArrowImage(color, selected);
        })
      );
      pointSource.setData(pointsGeoJSON);
    }

    function getRasterSourceSpec(layerValue, tileSource) {
      const clientConfig = tileSource?.client_config || {};
      const url = clientConfig.url || `/api/tiles/${layerValue}/{z}/{x}/{y}`;
      let tiles;
      if (clientConfig.tileSubdomains && Array.isArray(clientConfig.tileSubdomains)) {
        tiles = clientConfig.tileSubdomains.map((sub) => url.replace('{s}', sub));
      } else {
        tiles = [url.replace('{s}', clientConfig.tileSubdomains?.[0] || 'a')];
      }
      return {
        type: 'raster',
        tiles,
        tileSize: clientConfig.tileSize || 256,
        attribution: clientConfig.attribution || ''
      };
    }

    function getRasterLayerMaxZoom(clientConfig) {
      return Math.max(clientConfig?.maxzoom ?? MAX_ZOOM, LAYER_MAX_ZOOM);
    }

    const lineBlackOutlineLayerSpec = {
      id: `${LINES_LAYER_ID}-black-outline`,
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
    // Meters-to-pixels factor: 256*2^zoom / (40075016.686 * cos(lat * pi / 180)). Zoom must be input to interpolate/step per MapLibre spec.
    const METERS_TO_PIXELS_ZOOM_0_EQ = 256 / 40075016.686;
    const METERS_TO_PIXELS_ZOOM_24_EQ = (256 * Math.pow(2, 24)) / 40075016.686;
    const accuracyCircleLayerSpec = {
      id: ACCURACY_CIRCLE_LAYER_ID,
      type: 'circle',
      source: POINTS_SOURCE_ID,
      filter: ['all', ['>', ['get', 'accuracy'], 0]],
      paint: {
        'circle-color': ['rgba', 51, 136, 255, 0.25],
        'circle-stroke-color': '#3388ff',
        'circle-stroke-width': 1,
        'circle-radius': [
          'interpolate',
          ['exponential', 2],
          ['zoom'],
          0,
          ['max', 6, ['*', ['get', 'accuracy'], ['/', METERS_TO_PIXELS_ZOOM_0_EQ, ['max', 0.001, ['cos', ['*', ['get', 'latitude'], Math.PI / 180]]]]]],
          24,
          ['max', 6, ['*', ['get', 'accuracy'], ['/', METERS_TO_PIXELS_ZOOM_24_EQ, ['max', 0.001, ['cos', ['*', ['get', 'latitude'], Math.PI / 180]]]]]]
        ]
      }
    };

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

    async function addLiveTrackLayersAndData() {
      if (!map || !map.getStyle()) return;
      if (!map.getSource(LINES_SOURCE_ID)) {
        map.addSource(LINES_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      }
      if (!map.getSource(POINTS_SOURCE_ID)) {
        map.addSource(POINTS_SOURCE_ID, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      }
      if (!map.getLayer(POINTS_LAYER_ID)) {
        const defaultColor = '#3388ff';
        const defaultId = getArrowImageId(defaultColor, false);
        const imageData = await rasterizeArrowToImageData(defaultColor, false);
        if (imageData && !map.hasImage(defaultId)) map.addImage(defaultId, imageData, { pixelRatio: 1 });
        map.addLayer(pointsLayerSpec);
      }
      if (!map.getLayer(`${LINES_LAYER_ID}-black-outline`)) {
        map.addLayer(lineBlackOutlineLayerSpec, POINTS_LAYER_ID);
      }
      if (!map.getLayer(LINES_LAYER_ID)) {
        map.addLayer(lineLayerSpec, POINTS_LAYER_ID);
      }
      if (!map.getLayer(ACCURACY_CIRCLE_LAYER_ID)) {
        map.addLayer(accuracyCircleLayerSpec, `${LINES_LAYER_ID}-black-outline`);
      }
      await updateMapFeatures();
    }

    async function switchMapLayer(layerValue) {
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
          setupMapFollowListeners();
          disableMapRotation();
          map.once('load', () => {
            if (!map) return;
            map.resize();
            const defaultColor = '#3388ff';
            const defaultId = getArrowImageId(defaultColor, false);
            rasterizeArrowToImageData(defaultColor, false).then((imageData) => {
              if (!map || !map.getStyle()) return;
              if (!imageData) return;
              if (!map.hasImage(defaultId)) map.addImage(defaultId, imageData, { pixelRatio: 1 });
              if (!map.getLayer(POINTS_LAYER_ID)) map.addLayer(pointsLayerSpec);
              updateMapFeatures().then(() => {
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
          if (map.getLayer(BASE_LAYER_ID)) map.removeLayer(BASE_LAYER_ID);
          if (map.getSource(BASE_SOURCE_ID)) map.removeSource(BASE_SOURCE_ID);
          const spec = getRasterSourceSpec(layerValue, tileSource);
          const layerMaxZoom = getRasterLayerMaxZoom(clientConfig);
          map.addSource(BASE_SOURCE_ID, spec);
          const firstTrackLayerId = ACCURACY_CIRCLE_LAYER_ID;
          map.addLayer(
            {
              id: BASE_LAYER_ID,
              type: 'raster',
              source: BASE_SOURCE_ID,
              minzoom: clientConfig.minzoom ?? 0,
              maxzoom: layerMaxZoom
            },
            firstTrackLayerId
          );
        }
      }
    }

    function onLayerChange() {
      switchMapLayer(selectedLayer.value);
    }

    function initMap() {
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
        setupMapFollowListeners();
        disableMapRotation();
        map.on('load', () => {
          if (!map) return;
          map.resize();
          addLiveTrackLayersAndData().then(() => {
            setTimeout(() => {
              if (map) {
                map.resize();
                fitMapToTracks();
              }
            }, 0);
          }).catch(() => {
            setTimeout(() => {
              if (map) {
                map.resize();
                fitMapToTracks();
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
      setupMapFollowListeners();
      disableMapRotation();

      map.once('load', () => {
        if (!map) return;
        map.resize();
        const defaultColor = '#3388ff';
        const defaultId = getArrowImageId(defaultColor, false);
        rasterizeArrowToImageData(defaultColor, false).then((imageData) => {
          if (!map || !map.getStyle()) return;
          if (!imageData) return;
          if (!map.hasImage(defaultId)) map.addImage(defaultId, imageData, { pixelRatio: 1 });
          if (!map.getLayer(POINTS_LAYER_ID)) map.addLayer(pointsLayerSpec);
          updateMapFeatures().then(() => {
            setTimeout(() => {
              if (map) {
                map.resize();
                fitMapToTracks();
              }
            }, 0);
          }).catch(() => {
            setTimeout(() => {
              if (map) {
                map.resize();
                fitMapToTracks();
              }
            }, 0);
          });
        });
      });
    }

    const LAST_POINTS_FIT = 10;

    function getLastNCoords(track, n) {
      const coords = getCoordsSortedByTime(track);
      const slice = coords.length ? coords.slice(-n) : [];
      return slice.map((c) => [c[0], c[1]]);
    }

    function getSelectedTrackLastPoint() {
      if (!selectedId.value) return null;
      const track = trackers.value.find((t) => t.id === selectedId.value);
      if (!track) return null;
      const coords = getLastNCoords(track, 1);
      return coords.length ? coords[0] : null;
    }

    function centerOnSelectedTrackLastPoint() {
      if (!map) return;
      const center = getSelectedTrackLastPoint();
      if (!center) return;
      isAutoMoving.value = true;
      map.panTo(center, { duration: MAP_SNAP_DURATION });
      setTimeout(() => {
        isAutoMoving.value = false;
      }, MAP_SNAP_DURATION + 50);
    }

    function setupMapFollowListeners() {
      if (!map) return;
      
      const breakLock = () => {
        if (followLocked.value) {
          followLocked.value = false;
          selectedId.value = null;
        }
      };

      // Only unlock when the user explicitly interacts with the map (drag, wheel, pinch).
      // Map +/- controls keep the lock.
      map.on('dragstart', breakLock);
      map.on('wheel', breakLock);
      map.on('dblclick', breakLock);
      map.on('zoomstart', (e) => {
        const type = e.originalEvent?.type;
        // Break lock for touch pinch or mouse wheel zoom, but ignore click on +/- buttons
        if (type === 'touchstart' || type === 'touchmove' || type === 'wheel') {
          breakLock();
        }
      });
      const TRACK_LAYER_IDS = [
        POINTS_LAYER_ID,
        LINES_LAYER_ID,
        `${LINES_LAYER_ID}-black-outline`
      ];
      function getClickableTrackLayers() {
        return TRACK_LAYER_IDS.filter((id) => map.getLayer(id));
      }
      map.on('click', (e) => {
        const layers = getClickableTrackLayers();
        if (layers.length === 0) return;
        const features = map.queryRenderedFeatures(e.point, { layers });
        const feature = features[0];
        if (feature?.properties?.trackId) {
          const trackId = feature.properties.trackId;
          highlightedId.value = trackId;
          nextTick(() => {
            const el = listScrollContainer.value?.querySelector(`[data-track-id="${trackId}"]`);
            el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
          });
        } else {
          highlightedId.value = null;
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
    }

    /** Disable map rotation (drag, touch pinch, keyboard) so north stays up. See maplibre disable-map-rotation example. */
    function disableMapRotation() {
      if (!map) return;
      if (map.dragRotate && map.dragRotate.disable) map.dragRotate.disable();
      if (map.touchZoomRotate && map.touchZoomRotate.disableRotation) map.touchZoomRotate.disableRotation();
      if (map.keyboard && map.keyboard.disableRotation) map.keyboard.disableRotation();
    }

    function onTrackListClick(track) {
      highlightedId.value = null;
      if (selectedId.value === track.id) {
        selectedId.value = null;
        followLocked.value = false;
        return;
      }
      selectedId.value = track.id;
      followLocked.value = true;
      updateMapFeatures();
      const lastPoint = getLastNCoords(track, 1);
      if (map && lastPoint.length > 0) {
        isAutoMoving.value = true;
        const zoom = Math.max(map.getZoom(), 14);
        map.easeTo({ center: lastPoint[0], zoom, duration: MAP_SNAP_DURATION });
        setTimeout(() => {
          isAutoMoving.value = false;
        }, MAP_SNAP_DURATION + 50);
      }
    }

    function fitBoundsFromCoords(coords) {
      if (!map || !coords.length) return;
      let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;
      for (const [lon, lat] of coords) {
        minLon = Math.min(minLon, lon);
        minLat = Math.min(minLat, lat);
        maxLon = Math.max(maxLon, lon);
        maxLat = Math.max(maxLat, lat);
      }
      if (!Number.isFinite(minLon)) return;
      const pad = 0.002;
      if (maxLon <= minLon) { minLon -= pad; maxLon += pad; }
      if (maxLat <= minLat) { minLat -= pad; maxLat += pad; }
      map.fitBounds([[minLon, minLat], [maxLon, maxLat]], {
        padding: { top: 50, bottom: 80, left: 80, right: 80 },
        maxZoom: 15,
        duration: MAP_SNAP_DURATION
      });
    }

    function fitMapToTracks() {
      if (!map || trackers.value.length === 0) return;
      const allCoords = [];
      for (const track of trackers.value) {
        allCoords.push(...getLastNCoords(track, LAST_POINTS_FIT));
      }
      fitBoundsFromCoords(allCoords);
    }

    function fitMapToSelectedTrack() {
      if (!map || !selectedId.value) return;
      const track = trackers.value.find((t) => t.id === selectedId.value);
      if (!track) return;
      const coords = getLastNCoords(track, LAST_POINTS_FIT);
      if (coords.length === 0) return;
      fitBoundsFromCoords(coords);
    }

    async function goHome() {
      selectedId.value = null;
      followLocked.value = false;
      await updateMapFeatures();
      if (trackers.value.length > 0) {
        fitMapToTracks();
      } else if (map) {
        map.easeTo({ center: [0, 0], zoom: 2, duration: MAP_SNAP_DURATION });
      }
    }

    function openCreateModal() {
      modalMode.value = 'create';
      modalTrack.value = null;
      showModal.value = true;
    }

    function openEditModal(track) {
      modalMode.value = 'edit';
      modalTrack.value = null;
      modalTrackLoading.value = true;
      showModal.value = true;
      nextTick(() => {
        api.get(`/trackers/${track.id}/`)
          .then((res) => {
            modalTrack.value = res.data;
          })
          .catch((e) => {
            const err = api.handleError?.(e);
            if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load track');
            showModal.value = false;
          })
          .finally(() => {
            modalTrackLoading.value = false;
          });
      });
    }

    function onModalSaved() {
      showModal.value = false;
      fetchTrackers();
    }

    function onTrackDeleted() {
      showModal.value = false;
      fetchTrackers();
    }

    watch(selectedId, () => {
      updateMapFeatures();
      if (selectedId.value && !followLocked.value) {
        fitMapToSelectedTrack();
      }
      // When unselecting we only unlock; do not reset zoom (no fitMapToTracks)
    });

    function applyDefaultSortFromStore() {
      const store = window.gv_core?.store;
      const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
      if (!store || !getNestedValue) return;
      const saved = getNestedValue(store.state?.userSettings, DEFAULT_SORT_KEY);
      if (saved && VALID_SORT_VALUES.has(saved)) sortBy.value = saved;
    }

    function applyDefaultMapFromStore() {
      const store = window.gv_core?.store;
      const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
      if (!store || !getNestedValue || !tileSources.value.length) return;
      const defaultMap = getNestedValue(store.state?.userSettings, DEFAULT_MAP_KEY);
      if (defaultMap && tileSources.value.some((s) => s.id === defaultMap)) {
        selectedLayer.value = defaultMap;
      }
    }

    onMounted(async () => {
      const store = window.gv_core?.store;
      const userInfo = store?.state?.userInfo;
      if (userInfo?.email) userLogin.value = userInfo.email;
      applyDefaultSortFromStore();
      await fetchTileSources();
      const ingressData = await getIngressBodyTemplate(api);
      if (ingressData?.param_labels && typeof ingressData.param_labels === 'object') {
        paramLabels.value = ingressData.param_labels;
      }
      fetchTrackers().finally(() => {
        requestAnimationFrame(() => initMap());
      });

      function scheduleCenterOnSelectedTrack() {
        if (centerDebounceId) clearTimeout(centerDebounceId);
        centerDebounceId = setTimeout(() => {
          centerDebounceId = null;
          if (followLocked.value && selectedId.value && map) centerOnSelectedTrackLastPoint();
        }, CENTER_DEBOUNCE_MS);
      }

      trackUpdatedHandler = (data) => {
        if (!data || !data.track_id || !Array.isArray(data.point)) return;
        const idx = trackers.value.findIndex((t) => t.id === data.track_id);
        if (idx < 0) return;
        const track = trackers.value[idx];
        const geom = track.geometry ? { ...track.geometry, coordinates: [...(track.geometry.coordinates || [])] } : { type: 'LineString', coordinates: [] };
        if (!geom.coordinates) geom.coordinates = [];

        const indexOutOfBounds =
          typeof data.index === 'number' &&
          Number.isInteger(data.index) &&
          (data.index < 0 || data.index > geom.coordinates.length);
        if (indexOutOfBounds) {
          api.get(`/trackers/${data.track_id}/geometry/`).then((geomRes) => {
            const normalized = normalizeTrackForMemory(geomRes.data);
            const trackIdx = trackers.value.findIndex((t) => t.id === data.track_id);
            if (trackIdx < 0) return;
            trackers.value = trackers.value.slice(0, trackIdx).concat(normalized).concat(trackers.value.slice(trackIdx + 1));
            updateMapFeatures();
            if (data.track_id === selectedId.value && followLocked.value && map) {
              scheduleCenterOnSelectedTrack();
            }
          }).catch(() => {});
          return;
        }

        if (typeof data.index === 'number' && Number.isInteger(data.index)) {
          geom.coordinates.splice(data.index, 0, data.point);
        } else {
          geom.coordinates.push(data.point);
        }
        const last = geom.coordinates[geom.coordinates.length - 1];
        const newPoint = data.point;
        const last_position = newPoint && newPoint.length >= 2 ? { lon: newPoint[0], lat: newPoint[1] } : (last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null);
        const last_timestamp_ms = newPoint && newPoint.length >= 3 ? newPoint[2] : (last && last.length >= 3 ? last[2] : null);
        const latestPointParams = data.props && typeof data.props === 'object' ? data.props : {};
        const updated = { ...track, geometry: geom, last_position, last_timestamp_ms, latestPointParams };
        trackers.value = trackers.value.slice(0, idx).concat(updated).concat(trackers.value.slice(idx + 1));
        updateMapFeatures();
        if (data.track_id === selectedId.value && followLocked.value && map) {
          scheduleCenterOnSelectedTrack();
        }
      };
      trackersLiveSocket.onReconnect = () => {
        fetchTrackers().then(() => {
          if (followLocked.value && selectedId.value && map) centerOnSelectedTrackLastPoint();
        });
      };
      trackersLiveSocket.connect();
      trackersLiveSocket.unsubscribe('track_updated', trackUpdatedHandler);
      trackersLiveSocket.subscribe('track_updated', trackUpdatedHandler);

      const mq = window.matchMedia('(max-width: 639px)');
      isMobileView.value = mq.matches;
      isSheetOpen.value = mq.matches;
      mobileQueryListener = (e) => { 
        isMobileView.value = e.matches; 
        isSheetOpen.value = e.matches;
      };
      mq.addEventListener('change', mobileQueryListener);
    });

    onActivated(() => {
      applyDefaultSortFromStore();
      applyDefaultMapFromStore();
      if (map && tileSources.value.some((s) => s.id === selectedLayer.value)) {
        switchMapLayer(selectedLayer.value);
      }
    });

    watch(
      () => window.gv_core?.store?.state?.userSettings,
      (userSettings) => {
        if (!userSettings) return;
        applyDefaultSortFromStore();
        applyDefaultMapFromStore();
        if (map && tileSources.value.length && tileSources.value.some((s) => s.id === selectedLayer.value)) {
          switchMapLayer(selectedLayer.value);
        }
      },
      { deep: true, immediate: true }
    );

    onBeforeUnmount(() => {
      if (mobileQueryListener && typeof window !== 'undefined') {
        window.matchMedia('(max-width: 639px)').removeEventListener('change', mobileQueryListener);
        mobileQueryListener = null;
      }
      if (centerDebounceId) {
        clearTimeout(centerDebounceId);
        centerDebounceId = null;
      }
      trackersLiveSocket.onReconnect = null;
      if (trackUpdatedHandler) {
        trackersLiveSocket.unsubscribe('track_updated', trackUpdatedHandler);
      }
      trackersLiveSocket.disconnect();
      if (map && mapContainer.value) {
        map.remove();
        map = null;
      }
    });

    return {
      trackers,
      sortBy,
      sortedTrackers,
      loading,
      selectedId,
      highlightedId,
      showModal,
      showLayerModal,
      paramsModalTrackId,
      paramsModalTrack,
      paramLabels,
      modalMode,
      modalTrack,
      mapContainer,
      userLogin,
      tileSources,
      selectedLayer,
      isMobileView,
      isSheetOpen,
      formatTime,
      getTrackDirectionAngle,
      goHome,
      onLayerChange,
      onTrackListClick,
      openCreateModal,
      openEditModal,
      onModalSaved,
      onTrackDeleted,
      isRecentlyUpdated
    };
  }
};
</script>

<style scoped>


.custom-scrollbar::-webkit-scrollbar {
  width: 5px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.05);
  border-radius: 10px;
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.1);
}

/* Ensure smooth transition for the bottom sheet */
aside {
  will-change: height;
}

/* Hide navigation control on mobile to avoid overlap with sheet */
@media (max-width: 639px) {
  :deep(.maplibregl-ctrl-top-right) {
    top: 70px !important;
  }
}

/* Ensure handle is visible and root is not blocking */
:deep([data-vsbs-backdrop]) {
  background: transparent !important;
  pointer-events: none !important;
}

:deep([data-vsbs-sheet]) {
  z-index: 40 !important;
  border: none !important;
  box-shadow: none !important;
  --vsbs-outer-border-color: transparent !important;
  --vsbs-border-color: transparent !important;
  --vsbs-shadow-color: transparent !important;
}

:deep([data-vsbs-shadow="true"]::before) {
  box-shadow: none !important;
}

/* Fix for handle being draggable and not maximized */
:deep([data-vsbs-header]) {
  cursor: grab !important;
  touch-action: none !important;
  border-bottom: none !important;
  box-shadow: none !important;
}
</style>

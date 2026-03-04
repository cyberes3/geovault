<template>
  <div class="h-full flex flex-col sm:flex-row bg-gray-50">
    <!-- Left: trackers list (25%) -->
    <div class="w-full sm:w-1/4 min-w-0 flex flex-col bg-white border-r border-gray-200 relative">
      <div class="p-2 border-b border-gray-200 flex items-center justify-between gap-2 flex-shrink-0">
        <h2 class="text-lg font-semibold text-gray-900 truncate">Trackers</h2>
        <div class="flex items-center gap-2 flex-shrink-0">
          <select
            v-model="sortBy"
            class="text-sm border border-gray-300 rounded-lg px-2 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            title="Sort by"
          >
            <option value="alphabetical">Alphabetical</option>
            <option value="last_updated">Last updated</option>
            <option value="num_points">Number of points</option>
            <option value="newest">Newest</option>
          </select>
          <button
            type="button"
            title="Add track"
            class="p-2 rounded-lg bg-blue-600 text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
            @click="openCreateModal"
          >
            <PlusIcon class="h-5 w-5" />
          </button>
        </div>
      </div>
      <div v-if="loading" class="flex-1 flex items-center justify-center p-4">
        <Loader size="md" message="Loading trackers..." />
      </div>
      <div v-else class="flex-1 overflow-y-auto p-2">
        <div v-if="sortedTrackers.length === 0" class="text-center py-8 text-gray-500 text-sm">
          No trackers yet. Tap + to create one.
        </div>
        <div
          v-for="track in sortedTrackers"
          :key="track.id"
          :class="[
            'flex items-center gap-2 p-3 rounded-lg cursor-pointer border transition-all',
            selectedId === track.id ? 'border-black bg-gray-100' : 'border-transparent hover:bg-gray-50'
          ]"
          @click="selectedId = track.id"
        >
          <TrackDirectionIcon
            :color="track.color || '#3388ff'"
            :angle="getTrackDirectionAngle(track)"
            :size="20"
          />
          <div class="flex-1 min-w-0">
            <div class="font-medium text-gray-900 truncate">{{ track.name }}</div>
            <div class="text-xs text-gray-500">
              {{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'No points' }}
            </div>
          </div>
          <button
            type="button"
            title="Edit"
            class="p-1.5 rounded text-gray-500 hover:bg-gray-200"
            @click.stop="openEditModal(track)"
          >
            <PencilIcon class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
    <!-- Right: map (75%) -->
    <div ref="mapContainer" class="flex-1 min-h-[300px] bg-gray-200 relative" />
    <TrackModal
      v-if="showModal"
      :mode="modalMode"
      :track="modalTrack"
      :user-login="userLogin"
      @close="showModal = false"
      @saved="onModalSaved"
      @deleted="onTrackDeleted"
    />
  </div>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, inject, watch } from 'vue';
import { PlusIcon, PencilIcon } from '@heroicons/vue/24/outline';
import TrackModal from './TrackModal.vue';
import TrackDirectionIcon from './TrackDirectionIcon.vue';

const maplibregl = window.gv_core?.maplibre || window.maplibregl;

const LINES_SOURCE_ID = 'live-track-lines';
const POINTS_SOURCE_ID = 'live-track-points';
const LINES_LAYER_ID = 'live-track-lines';
const POINTS_LAYER_ID = 'live-track-points';
const TRACK_ARROW_IMAGE_ID = 'track-direction-arrow';

// Same arrow as TrackDirectionIcon: white fill so MapLibre icon-color tints it, thin black stroke
const TRACK_ARROW_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
  '<path fill="#fff" stroke="#000" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" stroke-miterlimit="10" ' +
  'd="M29.9,28.6l-13-26c-0.3-0.7-1.4-0.7-1.8,0l-13,26c-0.2,0.4-0.1,0.8,0.2,1.1C2.5,30,3,30.1,3.4,29.9L16,25.1l12.6,4.9c0.1,0,0.2,0.1,0.4,0.1c0.3,0,0.5-0.1,0.7-0.3C30,29.4,30.1,28.9,29.9,28.6z"/>' +
  '</svg>';
function getTrackArrowDataURL() {
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(TRACK_ARROW_SVG);
}

export default {
  name: 'LiveTrackView',
  components: { TrackModal, TrackDirectionIcon, PlusIcon, PencilIcon },
  setup() {
    const api = inject('extensionApi');
    const trackers = ref([]);
    const sortBy = ref('alphabetical');
    const loading = ref(true);
    const selectedId = ref(null);

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
    const modalMode = ref('create');
    const modalTrack = ref(null);
    const mapContainer = ref(null);
    const userLogin = ref('');
    let map = null;
    let trackUpdatedHandler = null;

    function formatTime(ms) {
      if (!ms) return '';
      const d = new Date(ms);
      return d.toLocaleString();
    }

    /** Degrees from north (0 = up), clockwise. From second-to-last to last point. */
    function getTrackDirectionAngle(track) {
      const geom = track.geometry || {};
      const coords = geom.coordinates || [];
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
      const { point_params, ...rest } = track;
      const latestPointParams = (point_params && point_params.length)
        ? point_params[point_params.length - 1]
        : {};
      return {
        ...rest,
        geometry: geom,
        last_position: last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null,
        last_timestamp_ms: last && last.length >= 3 ? last[2] : null,
        latestPointParams
      };
    }

    async function fetchTrackers() {
      loading.value = true;
      try {
        const res = await api.get('/trackers/');
        const raw = Array.isArray(res.data) ? res.data : [];
        trackers.value = raw.map(normalizeTrackForMemory);
        if (trackers.value.length && !selectedId.value) selectedId.value = trackers.value[0].id;
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

    function buildLinesGeoJSON() {
      const features = [];
      for (const track of trackers.value) {
        const geom = track.geometry || {};
        const coords = (geom.coordinates || []).map((c) => [c[0], c[1]]);
        if (coords.length < 2) continue;
        features.push({
          type: 'Feature',
          properties: {
            trackId: track.id,
            color: track.color || '#3388ff',
            selected: selectedId.value === track.id
          },
          geometry: { type: 'LineString', coordinates: coords }
        });
      }
      return { type: 'FeatureCollection', features };
    }

    function buildPointsGeoJSON() {
      const features = [];
      for (const track of trackers.value) {
        const geom = track.geometry || {};
        const coords = geom.coordinates || [];
        const last = coords[coords.length - 1];
        if (!last || last.length < 2) continue;
        features.push({
          type: 'Feature',
          properties: {
            trackId: track.id,
            color: track.color || '#3388ff',
            selected: selectedId.value === track.id,
            rotation: getTrackDirectionAngle(track)
          },
          geometry: { type: 'Point', coordinates: [last[0], last[1]] }
        });
      }
      return { type: 'FeatureCollection', features };
    }

    function updateMapFeatures() {
      if (!map || !maplibregl) return;
      const lineSource = map.getSource(LINES_SOURCE_ID);
      const pointSource = map.getSource(POINTS_SOURCE_ID);
      if (lineSource) lineSource.setData(buildLinesGeoJSON());
      if (pointSource) pointSource.setData(buildPointsGeoJSON());
    }

    function initMap() {
      if (!mapContainer.value || !maplibregl) return;

      const style = {
        version: 8,
        sources: {
          'osm': {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: '© OpenStreetMap'
          },
          [LINES_SOURCE_ID]: {
            type: 'geojson',
            data: { type: 'FeatureCollection', features: [] }
          },
          [POINTS_SOURCE_ID]: {
            type: 'geojson',
            data: { type: 'FeatureCollection', features: [] }
          }
        },
        layers: [
          { id: 'osm', type: 'raster', source: 'osm' },
          {
            id: `${LINES_LAYER_ID}-outline`,
            type: 'line',
            source: LINES_SOURCE_ID,
            filter: ['==', ['get', 'selected'], true],
            paint: {
              'line-color': '#555',
              'line-width': 4,
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
              'line-width': [
                'case',
                ['get', 'selected'], 3,
                2
              ],
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
        attributionControl: false
      });

      map.addControl(new maplibregl.NavigationControl(), 'top-right');

      map.on('load', () => {
        const img = new Image();
        img.onload = () => {
          if (!map || !map.getStyle()) return;
          map.addImage(TRACK_ARROW_IMAGE_ID, img, { pixelRatio: 2 });
          map.addLayer({
            id: POINTS_LAYER_ID,
            type: 'symbol',
            source: POINTS_SOURCE_ID,
            layout: {
              'icon-image': TRACK_ARROW_IMAGE_ID,
              'icon-size': ['case', ['get', 'selected'], 0.5, 0.4],
              'icon-rotate': ['get', 'rotation'],
              'icon-anchor': 'bottom',
              'icon-allow-overlap': true,
              'icon-ignore-placement': true
            },
            paint: {
              'icon-color': ['get', 'color']
            }
          });
          updateMapFeatures();
          setTimeout(() => {
            map.resize();
            fitMapToTracks();
          }, 0);
        };
        img.onerror = () => {
          updateMapFeatures();
          setTimeout(() => {
            map.resize();
            fitMapToTracks();
          }, 0);
        };
        img.src = getTrackArrowDataURL();
      });
    }

    const LAST_POINTS_FIT = 10;

    function getLastNCoords(track, n) {
      const geom = track.geometry || {};
      const coords = geom.coordinates || [];
      const slice = coords.length ? coords.slice(-n) : [];
      return slice.map((c) => [c[0], c[1]]);
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
        padding: 40,
        maxZoom: 15,
        duration: 0
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

    function openCreateModal() {
      modalMode.value = 'create';
      modalTrack.value = null;
      showModal.value = true;
    }

    function openEditModal(track) {
      modalMode.value = 'edit';
      modalTrack.value = track;
      showModal.value = true;
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
      fitMapToSelectedTrack();
    });

    onMounted(() => {
      fetchTrackers().then(() => {
        initMap();
      });
      const store = window.gv_core?.store;
      const userInfo = store?.state?.userInfo;
      if (userInfo?.email) userLogin.value = userInfo.email;

      const socket = window.gv_core?.realtimeSocket;
      trackUpdatedHandler = (data) => {
        if (!data || !data.track_id || !Array.isArray(data.point)) return;
        const track = trackers.value.find((t) => t.id === data.track_id);
        if (!track) return;
        const geom = track.geometry || { type: 'LineString', coordinates: [] };
        if (!geom.coordinates) geom.coordinates = [];
        geom.coordinates.push(data.point);
        track.last_position = { lon: data.point[0], lat: data.point[1] };
        track.last_timestamp_ms = data.point[2];
        track.latestPointParams = data.props && typeof data.props === 'object' ? data.props : {};
        updateMapFeatures();
      };
      if (socket && socket.subscribe) {
        socket.subscribe('live_track', 'track_updated', trackUpdatedHandler);
      }
    });

    onBeforeUnmount(() => {
      const socket = window.gv_core?.realtimeSocket;
      if (socket && socket.unsubscribe && trackUpdatedHandler) {
        socket.unsubscribe('live_track', 'track_updated', trackUpdatedHandler);
      }
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
      showModal,
      modalMode,
      modalTrack,
      mapContainer,
      userLogin,
      formatTime,
      getTrackDirectionAngle,
      openCreateModal,
      openEditModal,
      onModalSaved,
      onTrackDeleted
    };
  }
};
</script>

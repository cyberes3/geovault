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
        <ShareIcon class="w-5 h-5 text-gray-500 flex-shrink-0" />
        <h1 class="text-lg font-semibold text-gray-900 truncate min-w-0">{{ displayTitle }}</h1>
      </header>
      <div class="flex-1 min-h-0 flex flex-col sm:flex-row">
        <!-- Desktop: sidebar with track list -->
        <aside
          v-if="!isMobileView"
          class="flex flex-col min-h-0 border-r border-gray-200 bg-white sm:w-80 flex-shrink-0"
        >
          <div class="flex-1 min-h-0 overflow-hidden flex flex-col">
            <MapTrackList
              :tracks="visibleTracks"
              :selected-id="selectedId"
              :get-params-allowed="getParamsAllowedForTrack"
              @track-click="onTrackListClick"
              @open-params="openParamsForTrack"
            />
          </div>
        </aside>

        <!-- Map column -->
        <div ref="mapWrapperRef" class="relative flex-1 min-h-0 w-full flex flex-col">
          <div ref="mapContainer" class="absolute inset-0 w-full h-full bg-gray-100" />

          <!-- Mobile: map actions behind hamburger (desktop uses right strip below) -->
          <div
            v-if="isMobileView && !isShareMapSidebarOpen"
            ref="mobileActionsMenuRootRef"
            class="absolute top-3 left-3 z-30"
          >
            <button
              type="button"
              class="grid h-9 w-9 place-items-center rounded-md border border-gray-200 bg-white text-gray-700 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 [-webkit-tap-highlight-color:transparent]"
              aria-haspopup="menu"
              :aria-expanded="mobileActionsMenuOpen"
              aria-label="Map actions"
              @click="mobileActionsMenuOpen = !mobileActionsMenuOpen"
            >
              <Bars3Icon class="h-5 w-5" aria-hidden="true" />
            </button>
            <div
              v-show="mobileActionsMenuOpen"
              class="absolute left-0 top-full z-30 mt-1 min-w-[13.5rem] max-h-[min(28rem,calc(100dvh-12rem))] overflow-y-auto overflow-x-hidden overscroll-y-contain rounded-lg border border-gray-200 bg-white py-1 custom-scrollbar"
              role="menu"
              aria-label="Map actions"
            >
              <button
                type="button"
                role="menuitem"
                class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
                @click="closeMobileActionsMenu(); openLayerSidebar()"
              >
                <Square3Stack3DIcon class="h-5 w-5 flex-shrink-0 text-blue-600" />
                <span>Map Settings</span>
              </button>
              <button
                type="button"
                role="menuitem"
                class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
                @click="closeMobileActionsMenu(); goHome()"
              >
                <HomeIcon class="h-5 w-5 flex-shrink-0 text-blue-600" />
                <span>Go to Home Extent</span>
              </button>
            </div>
          </div>

          <!-- Selected chip: track name, deselect X -->
          <div
            v-if="selectedItemLabel"
            :class="[
              'absolute top-3 z-20 flex max-w-[calc(100%-1.5rem)] items-center gap-2 rounded-lg border border-blue-200 bg-white px-3 py-2 sm:max-w-none',
              isMobileView ? 'left-1/2 -translate-x-1/2' : 'left-3'
            ]"
          >
            <span class="text-sm font-medium text-gray-900 truncate max-w-[12rem]" :title="selectedItemLabel">{{ selectedItemLabel }}</span>
            <button
              type="button"
              title="Deselect"
              class="flex-shrink-0 p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100"
              @click="deselectSelection"
            >
              <XMarkIcon class="h-5 w-5" />
            </button>
          </div>
        </div>

        <!-- Action strip: desktop only (mobile uses hamburger on map) -->
        <aside
          v-if="!isMobileView"
          class="flex flex-shrink-0 flex-col w-12 min-h-0 border-l border-gray-200 bg-white items-center justify-end py-2 gap-1 order-last"
          aria-label="Actions"
        >
          <button
            type="button"
            title="Map Settings"
            :class="SIDEBAR_ACTION_BUTTON_CLASS"
            @click="openLayerSidebar"
          >
            <Square3Stack3DIcon :class="SIDEBAR_ACTION_ICON_CLASS" />
          </button>
          <button
            type="button"
            title="Go to Home Extent"
            :class="SIDEBAR_ACTION_BUTTON_CLASS"
            @click="goHome"
          >
            <HomeIcon :class="SIDEBAR_ACTION_ICON_CLASS" />
          </button>
        </aside>
      </div>

      <!-- Mobile: bottom drawer with track list -->
      <Teleport v-if="isMobileView" to="#app">
        <MobileMapDrawer
          ref="mobileDrawerRef"
          :max-height="worldShareDrawerMaxHeight"
          :initial-snap-index="0"
          :hidden="isShareMapSidebarOpen"
        >
          <template #default="{ atPeek }">
            <div class="flex-1 min-h-0 flex flex-col overflow-hidden px-2 pb-2">
              <div :class="['flex-1 min-h-0 overflow-hidden', atPeek ? 'world-share-drawer-content--no-scroll' : 'overflow-y-auto custom-scrollbar']">
                <MapTrackList
                  :tracks="visibleTracks"
                  :selected-id="selectedId"
                  :get-params-allowed="getParamsAllowedForTrack"
                  action-opacity-class="opacity-60"
                  @track-click="onTrackListClick"
                  @open-params="openParamsForTrack"
                />
              </div>
            </div>
          </template>
        </MobileMapDrawer>
      </Teleport>

      <LatestParamsModal
        v-if="showParamsSidebar"
        :track="paramsTrack"
        :param-labels="paramLabels"
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
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { ShareIcon, Square3Stack3DIcon, XMarkIcon, HomeIcon, Bars3Icon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import LatestParamsModal from './LatestParamsModal.vue';
import LiveTrackSidebar from './LiveTrackSidebar.vue';
import MapLayerSidebar from './MapLayerSidebar.vue';
import MapTrackList from './MapTrackList.vue';
import MobileMapDrawer from './MobileMapDrawer.vue';
import { buildLineFeatures, buildPointFeature, fitMapToTracks, fitMapToSingleTrack, centerMapOnTrackLastPoint } from './trackGeometry.js';
import { buildAccuracyCircleLayerSpec } from './mapAccuracyCircle.js';
import { setupMapFollowListeners } from './mapFollowLock.js';
import { setupCopyMapCoordinatesOnContextMenu } from 'platform/utils/map/copyMapCoordinatesOnContextMenu.js';
import { ensureArrowImage } from './trackArrowMap.js';
import { trackToParamsModalShape } from './trackParamsShape.js';
import { getRasterSourceSpec, getRasterLayerMaxZoom, replaceRasterBaseLayer } from './mapTileUtils.js';
import { useTileSources } from './useTileSources.js';
import { SHARE_SOURCE_MODES, isShareNotAvailableStatus, shareDataUrlForInfo, shareInfoUrl } from './shareDiscoveryUrls.js';
import { useDocumentTitle } from 'platform/utils/documentTitle.js';
const LIVE_TRACK_API_BASE_URL = '/api/extensions/live-track';
const LINES_SOURCE_ID = 'world-share-lines';
const POINTS_SOURCE_ID = 'world-share-points';
const LINES_LAYER_ID = 'world-share-lines-layer';
const LINES_WHITE_OUTLINE_LAYER_ID = 'world-share-lines-layer-white-outline';
const LINES_BLACK_OUTLINE_LAYER_ID = 'world-share-lines-layer-black-outline';
const POINTS_LAYER_ID = 'world-share-points-layer';
const ACCURACY_CIRCLE_LAYER_ID = 'world-share-accuracy-circle';
const BASE_SOURCE_ID = 'world-share-base';
const BASE_LAYER_ID = 'world-share-base-layer';
const MIN_ZOOM = 0;
const MAX_ZOOM = 18;
const LAYER_MAX_ZOOM = 19;
const POLL_INTERVAL_MS = 5000;
const MAP_SNAP_DURATION = 200;
const MAP_EDGE_PADDING_PX = 40;
const SIDEBAR_ACTION_BUTTON_CLASS =
  'p-1.5 sm:p-2 rounded-lg text-blue-600 hover:bg-blue-50 active:bg-blue-100 focus:outline-none focus:ring-0 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white [-webkit-tap-highlight-color:transparent]';
const SIDEBAR_ACTION_ICON_CLASS = 'h-5 w-5 sm:h-6 sm:w-6';

function getShareIdFromUrl() {
  const hash = typeof window !== 'undefined' ? window.location.hash : '';
  const q = hash.indexOf('?');
  if (q === -1) return null;
  const params = new URLSearchParams(hash.slice(q));
  return params.get('id');
}

async function fetchShareJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    return { ok: false, status: response.status, data: null };
  }
  return { ok: true, status: response.status, data: await response.json() };
}

async function fetchParamLabels() {
  const url = `${LIVE_TRACK_API_BASE_URL}/ingress-body-template/`;
  const response = await fetch(url);
  if (!response.ok) {
    return {};
  }
  const data = await response.json();
  return data?.param_labels && typeof data.param_labels === 'object' ? data.param_labels : {};
}

async function resolveShareSource(shareId) {
  const infoResult = await fetchShareJson(shareInfoUrl(shareId));
  if (!infoResult.ok) {
    if (isShareNotAvailableStatus(infoResult.status)) return null;
    throw new Error('Failed to load share');
  }

  const dataUrl = shareDataUrlForInfo(shareId, infoResult.data);
  const dataResult = await fetchShareJson(dataUrl);
  if (!dataResult.ok) {
    if (isShareNotAvailableStatus(dataResult.status)) throw new Error('Invalid share link');
    throw new Error('Failed to load share');
  }

  return {
    sourceMode: infoResult.data?.share_access || SHARE_SOURCE_MODES.WORLD,
    dataUrl,
    info: infoResult.data,
    data: dataResult.data
  };
}

export default {
  name: 'WorldShareView',
  components: { ShareIcon, Square3Stack3DIcon, XMarkIcon, HomeIcon, Bars3Icon, Loader, LatestParamsModal, LiveTrackSidebar, MapLayerSidebar, MapTrackList, MobileMapDrawer },
  setup() {
    const loading = ref(true);
    const error = ref('');
    const trackName = ref('');
    const groupName = ref('');
    const trackData = ref(null);
    const groupTracks = ref([]);
    const displayTitle = computed(() => trackName.value || groupName.value || 'Shared');
    useDocumentTitle(displayTitle);
    const mapContainer = ref(null);
    const mapWrapperRef = ref(null);
    const mobileDrawerRef = ref(null);
    const { tileSources, selectedLayer, fetchTileSources } = useTileSources({ apiUrl: '/api/tiles/sources/' });
    const showLayerSidebar = ref(false);
    const showParamsSidebar = ref(false);
    const paramsModalTrack = ref(null);
    const selectedId = ref(null);
    const followLocked = ref(false);
    const shareIdRef = ref(null);
    const sourceMode = ref(null);
    const shareDataUrl = ref('');
    const paramLabels = ref({});
    let map = null;
    let pollTimerId = null;

    const isMobileView = ref(
      typeof window !== 'undefined' ? window.matchMedia('(max-width: 639px)').matches : false
    );
    let mobileQueryListener = null;

    const windowHeight = ref(typeof window !== 'undefined' ? window.innerHeight : 800);
    function updateWindowHeight() {
      if (typeof window === 'undefined') return;
      windowHeight.value = window.innerHeight;
    }
    const worldShareDrawerMaxHeight = computed(() => {
      // Same as main tracker: app nav = 64px, header bar = 64px, buffer = 4px.
      // Max drawer height = viewport minus those so sheet stops at bottom of header.
      const APP_NAV_PX = 64;
      const HEADER_PX = 64;
      const BUFFER_PX = 4;
      return Math.max(65, windowHeight.value - APP_NAV_PX - HEADER_PX - BUFFER_PX);
    });

    const mobileActionsMenuOpen = ref(false);
    const mobileActionsMenuRootRef = ref(null);
    let mobileActionsOutsideStop = null;

    function closeMobileActionsMenu() {
      mobileActionsMenuOpen.value = false;
    }

    const isShareMapSidebarOpen = computed(() => showLayerSidebar.value || showParamsSidebar.value);

    watch(isMobileView, (mobile) => {
      if (!mobile) closeMobileActionsMenu();
    });

    watch(mobileActionsMenuOpen, (open) => {
      if (mobileActionsOutsideStop) {
        mobileActionsOutsideStop();
        mobileActionsOutsideStop = null;
      }
      if (!open || typeof document === 'undefined') return;
      const handler = (e) => {
        const root = mobileActionsMenuRootRef.value;
        if (root && !root.contains(e.target)) {
          mobileActionsMenuOpen.value = false;
        }
      };
      document.addEventListener('pointerdown', handler, true);
      mobileActionsOutsideStop = () => {
        document.removeEventListener('pointerdown', handler, true);
        mobileActionsOutsideStop = null;
      };
    });

    watch(isShareMapSidebarOpen, (open) => {
      if (open) closeMobileActionsMenu();
    });

    const visibleTracks = computed(() => {
      if (groupTracks.value?.length) return groupTracks.value;
      if (trackData.value) return [trackData.value];
      return [];
    });

    const selectedTrack = computed(() => {
      const id = selectedId.value;
      if (id == null) return null;
      return visibleTracks.value.find((t) => String(t.id) === String(id)) ?? null;
    });

    const selectedItemLabel = computed(() => selectedTrack.value?.name ?? null);

    const paramsTrack = computed(() => trackToParamsModalShape(paramsModalTrack.value));

    function getParamsAllowedForTrack(track) {
      const allow = track?.share_params_with_world === true ||
        (track?.share_params_with_world === undefined && track?.share_params_with_recipients === true);
      if (!allow) return false;
      const hasPoints = (track?.point_params?.length || track?.geometry?.coordinates?.length || 0) > 0;
      return hasPoints;
    }

    function normalizeTrackForWorld(track) {
      const geom = track?.geometry || { type: 'LineString', coordinates: [] };
      const coords = geom.coordinates || [];
      const last = coords[coords.length - 1] ?? track?.last_point;
      const pointParams = Array.isArray(track?.point_params) ? track.point_params : [];
      const latestPointParams = pointParams.length ? pointParams[pointParams.length - 1] : {};
      return {
        ...track,
        geometry: geom,
        point_params: pointParams,
        last_position: last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null,
        last_timestamp_ms: last && last.length >= 3 ? last[2] : null,
        latestPointParams
      };
    }

    function openLayerSidebar() {
      showParamsSidebar.value = false;
      showLayerSidebar.value = true;
    }

    function onLayerSidebarChange(layerId) {
      selectedLayer.value = layerId || selectedLayer.value;
      onLayerChange();
    }

    function openParamsForTrack(track) {
      paramsModalTrack.value = track;
      showParamsSidebar.value = true;
    }

    function getDrawerPeekHeight() {
      const snap = mobileDrawerRef.value?.snapPx?.[0];
      if (Number.isFinite(snap) && snap > 0) return snap;
      return Math.round(worldShareDrawerMaxHeight.value * 0.25);
    }

    function getMapPadding() {
      const bottomInset = isMobileView.value && !showLayerSidebar.value && !showParamsSidebar.value
        ? getDrawerPeekHeight()
        : 0;
      return {
        top: MAP_EDGE_PADDING_PX,
        left: MAP_EDGE_PADDING_PX,
        right: MAP_EDGE_PADDING_PX,
        bottom: MAP_EDGE_PADDING_PX + bottomInset
      };
    }

    function centerOnSelectedTrack() {
      const track = selectedTrack.value;
      if (track && map) centerMapOnTrackLastPoint(map, track, { duration: MAP_SNAP_DURATION, padding: getMapPadding() });
    }

    function deselectSelection() {
      selectedId.value = null;
      followLocked.value = false;
      updateMapData();
    }

    async function goHome() {
      selectedId.value = null;
      followLocked.value = false;
      await updateMapData();
      if (visibleTracks.value.length > 0 && map) {
        if (groupTracks.value?.length) {
          fitMapToTracks(map, groupTracks.value, { padding: getMapPadding() });
        } else {
          fitMapToSingleTrack(map, trackData.value, { padding: getMapPadding() });
        }
      } else if (map) {
        map.easeTo({ center: [0, 0], zoom: 2, duration: MAP_SNAP_DURATION, padding: getMapPadding() });
      }
    }

    function onTrackListClick(track) {
      if (selectedId.value != null && String(selectedId.value) === String(track.id)) {
        selectedId.value = null;
        followLocked.value = false;
        updateMapData();
        return;
      }
      selectedId.value = track.id;
      followLocked.value = true;
      updateMapData();
      if (map) {
        const coords = (track.geometry?.coordinates || []).slice(-1).map((c) => [c[0], c[1]]);
        const last = coords.length ? coords[0] : null;
        if (last) {
          const zoom = Math.max(map.getZoom(), 14);
          map.easeTo({ center: last, zoom, duration: MAP_SNAP_DURATION, padding: getMapPadding() });
        }
      }
      if (isMobileView.value && mobileDrawerRef.value?.collapseToPeek) {
        mobileDrawerRef.value.collapseToPeek();
      }
    }

    async function updateMapData() {
      const tracks = visibleTracks.value;
      if (!map || !tracks.length) return;
      if (!map.getStyle()) return;
      const lineSource = map.getSource(LINES_SOURCE_ID);
      const pointSource = map.getSource(POINTS_SOURCE_ID);
      if (lineSource) {
        const lineFeatures = tracks.flatMap((t) =>
          buildLineFeatures(t, selectedId.value != null && String(t.id) === String(selectedId.value))
        );
        lineSource.setData({ type: 'FeatureCollection', features: lineFeatures });
      }
      if (pointSource) {
        const colors = [...new Set(tracks.map((t) => t.color || '#6C93DE'))];
        for (const color of colors) {
          await ensureArrowImage(map, color, false);
          await ensureArrowImage(map, color, true);
        }
        const pointFeatures = tracks
          .map((t) =>
            buildPointFeature(
              t,
              selectedId.value != null && String(t.id) === String(selectedId.value),
              { includeAccuracy: true }
            )
          )
          .filter(Boolean);
        pointSource.setData({ type: 'FeatureCollection', features: pointFeatures });
      }
    }

    const accuracyCircleLayerSpec = buildAccuracyCircleLayerSpec({
      layerId: ACCURACY_CIRCLE_LAYER_ID,
      sourceId: POINTS_SOURCE_ID
    });

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
      if (!map.getLayer(LINES_WHITE_OUTLINE_LAYER_ID)) {
        map.addLayer({
          id: LINES_WHITE_OUTLINE_LAYER_ID,
          type: 'line',
          source: LINES_SOURCE_ID,
          paint: {
            'line-color': '#fff',
            'line-width': ['case', ['get', 'selected'], 7, 5],
            'line-opacity': 1
          },
          layout: { 'line-join': 'round', 'line-cap': 'round' }
        },
        LINES_LAYER_ID
        );
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
        const tracks = visibleTracks.value;
        const colors = [...new Set(tracks.map((t) => t?.color || '#6C93DE'))];
        for (const color of colors) {
          await ensureArrowImage(map, color, false);
          await ensureArrowImage(map, color, true);
        }
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
      if (!map.getLayer(ACCURACY_CIRCLE_LAYER_ID)) {
        map.addLayer(accuracyCircleLayerSpec, LINES_WHITE_OUTLINE_LAYER_ID);
      }
      updateMapData();
    }

    function fitMapToTrack() {
      if (groupTracks.value?.length) {
        fitMapToTracks(map, groupTracks.value, { padding: getMapPadding() });
      } else {
        fitMapToSingleTrack(map, trackData.value, { padding: getMapPadding() });
      }
    }

    function setupMapFollowListenersForView() {
      if (!map) return;
      setupMapFollowListeners(map, {
        getLocked: () => followLocked.value,
        setLocked: (v) => {
          followLocked.value = v;
          if (!v) selectedId.value = null;
        },
        onUnlock: () => updateMapData().catch(() => {})
      });
      setupCopyMapCoordinatesOnContextMenu(map);
    }

    const TRACK_CLICK_HIT_RADIUS_PX = 15;
    function setupMapClickHandler() {
      if (!map) return;
      const trackLayers = [POINTS_LAYER_ID, LINES_LAYER_ID, LINES_BLACK_OUTLINE_LAYER_ID, LINES_WHITE_OUTLINE_LAYER_ID];
      const getLayers = () => trackLayers.filter((id) => map.getLayer(id));
      const isOverTrack = (point) => {
        const layers = getLayers();
        if (layers.length === 0) return false;
        const bbox = [
          [point.x - TRACK_CLICK_HIT_RADIUS_PX, point.y - TRACK_CLICK_HIT_RADIUS_PX],
          [point.x + TRACK_CLICK_HIT_RADIUS_PX, point.y + TRACK_CLICK_HIT_RADIUS_PX]
        ];
        const features = map.queryRenderedFeatures(bbox, { layers });
        return features.some((f) => f.properties?.trackId != null);
      };
      map.on('mousemove', (e) => {
        map.getCanvas().style.cursor = isOverTrack(e.point) ? 'pointer' : '';
      });
      map.on('mouseout', () => {
        map.getCanvas().style.cursor = '';
      });
      map.on('click', (e) => {
        const layers = getLayers();
        if (layers.length === 0) return;
        const bbox = [
          [e.point.x - TRACK_CLICK_HIT_RADIUS_PX, e.point.y - TRACK_CLICK_HIT_RADIUS_PX],
          [e.point.x + TRACK_CLICK_HIT_RADIUS_PX, e.point.y + TRACK_CLICK_HIT_RADIUS_PX]
        ];
        const features = map.queryRenderedFeatures(bbox, { layers });
        const feature = features.find((f) => f.properties?.trackId != null);
        if (feature) {
          const trackId = feature.properties.trackId;
          const track = visibleTracks.value.find(
            (t) => t.id != null && String(t.id) === String(trackId)
          );
          if (track) onTrackListClick(track);
        } else {
          deselectSelection();
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
      if (typeof window !== 'undefined') {
        window.addEventListener('resize', updateWindowHeight);
        updateWindowHeight();
      }
      const shareId = getShareIdFromUrl();
      if (!shareId) {
        error.value = 'Invalid share link';
        loading.value = false;
        return;
      }
      shareIdRef.value = shareId;
      try {
        const [resolved, , paramLabelData] = await Promise.all([
          resolveShareSource(shareId),
          fetchTileSources(),
          fetchParamLabels()
        ]);
        paramLabels.value = paramLabelData;
        if (!resolved) {
          error.value = 'Invalid share link';
          loading.value = false;
          return;
        }
        sourceMode.value = resolved.sourceMode;
        shareDataUrl.value = resolved.dataUrl;
        const info = resolved.info;
        const data = resolved.data;
        if (info.share_type === 'live_track_group') {
          groupName.value = info.group_name || data.group_name || 'Shared group';
          const tracks = Array.isArray(data.tracks) ? data.tracks : [];
          groupTracks.value = tracks.map((t) => normalizeTrackForWorld(t));
          trackData.value = null;
        } else {
          trackName.value = info.track_name || 'Shared tracker';
          trackData.value = normalizeTrackForWorld(data);
          groupTracks.value = [];
        }
        loading.value = false;

        const mq = typeof window !== 'undefined' ? window.matchMedia('(max-width: 639px)') : null;
        if (mq) {
          isMobileView.value = mq.matches;
          mobileQueryListener = (e) => { isMobileView.value = e.matches; };
          mq.addEventListener('change', mobileQueryListener);
        }

        await nextTick();
        await new Promise((r) => setTimeout(r, 50));
        await initMap();

        if (!error.value && shareIdRef.value) {
          pollTimerId = setInterval(async () => {
            if (!shareIdRef.value || !shareDataUrl.value) return;
            try {
              const result = await fetchShareJson(shareDataUrl.value);
              if (!result.ok) return;
              const data = result.data;
              if (data.share_type === 'live_track_group' && Array.isArray(data.tracks)) {
                groupTracks.value = data.tracks.map((t) => normalizeTrackForWorld(t));
              } else {
                trackData.value = normalizeTrackForWorld(data);
              }
              await updateMapData();
              if (followLocked.value && map && selectedTrack.value) centerOnSelectedTrack();
            } catch (_) {
              // ignore poll errors
            }
          }, POLL_INTERVAL_MS);
        }
      } catch (e) {
        error.value = e?.message === 'Invalid share link' ? 'Invalid share link' : 'Failed to load share';
        loading.value = false;
      }
    });

    function initMap() {
      const maplibregl = window.gv_core?.maplibre || window.maplibregl;
      const hasData = trackData.value || (groupTracks.value?.length > 0);
      if (!mapContainer.value || !maplibregl || !hasData) {
        if (!maplibregl) console.warn('WorldShareView: MapLibre not available');
        return Promise.resolve();
      }

      const layerValue = selectedLayer.value;
      const tileSource = tileSources.value.find((s) => s.id === layerValue) || tileSources.value[0];
      const clientConfig = tileSource?.client_config || {};
      const isStyleBased = !!(clientConfig.style_url || clientConfig.type === 'maptiler');
      const tracksForInit = groupTracks.value?.length ? groupTracks.value : (trackData.value ? [trackData.value] : []);

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
            setupMapClickHandler();
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
      const lineFeatures = tracksForInit.flatMap((t) => buildLineFeatures(t, false));
      const pointFeatures = tracksForInit
        .map((t) => {
          const isSelected = selectedId.value != null && String(t.id) === String(selectedId.value);
          return buildPointFeature(t, isSelected, { includeAccuracy: true });
        })
        .filter(Boolean);
      const lineGeoJSON = { type: 'FeatureCollection', features: lineFeatures };
      const pointGeoJSON = { type: 'FeatureCollection', features: pointFeatures };

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
            id: LINES_WHITE_OUTLINE_LAYER_ID,
            type: 'line',
            source: LINES_SOURCE_ID,
            paint: {
              'line-color': '#fff',
              'line-width': ['case', ['get', 'selected'], 7, 5],
              'line-opacity': 1
            },
            layout: { 'line-join': 'round', 'line-cap': 'round' }
          },
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
          setupMapClickHandler();
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
      if (typeof window !== 'undefined') {
        window.removeEventListener('resize', updateWindowHeight);
      }
      if (mobileActionsOutsideStop) {
        mobileActionsOutsideStop();
        mobileActionsOutsideStop = null;
      }
      if (mobileQueryListener && typeof window !== 'undefined') {
        window.matchMedia('(max-width: 639px)').removeEventListener('change', mobileQueryListener);
        mobileQueryListener = null;
      }
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
      displayTitle,
      mapContainer,
      mapWrapperRef,
      mobileDrawerRef,
      mobileActionsMenuOpen,
      mobileActionsMenuRootRef,
      closeMobileActionsMenu,
      isShareMapSidebarOpen,
      tileSources,
      selectedLayer,
      showLayerSidebar,
      showParamsSidebar,
      paramsTrack,
      paramLabels,
      selectedId,
      sourceMode,
      selectedItemLabel,
      visibleTracks,
      followLocked,
      isMobileView,
      worldShareDrawerMaxHeight,
      openLayerSidebar,
      openParamsForTrack,
      onLayerSidebarChange,
      onTrackListClick,
      deselectSelection,
      goHome,
      getParamsAllowedForTrack,
      onLayerChange,
      SIDEBAR_ACTION_BUTTON_CLASS,
      SIDEBAR_ACTION_ICON_CLASS
    };
  }
};
</script>

<style scoped>
.world-share-drawer-content--no-scroll {
  overflow: hidden;
  touch-action: none;
}

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
</style>

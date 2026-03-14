<template>
  <div ref="rootContainer" class="flex-1 min-h-0 flex flex-col bg-gray-50 overflow-hidden">
    <!-- Header bar: full width; title/sort/+ constrained to list width on desktop -->
    <header class="z-40 h-16 flex flex-shrink-0 bg-white border-b border-gray-200 sm:flex-row">
      <div class="w-full sm:w-1/4 flex-shrink-0 px-4 py-2 flex items-center justify-between gap-3 sm:border-r sm:border-gray-200">
        <h2 class="text-xl font-bold text-gray-900 truncate min-w-0 tracking-tight">Trackers</h2>
        <div class="flex items-center gap-2 flex-shrink-0">
          <select
            v-model="sortBy"
            class="select-custom text-sm px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            title="Sort By"
          >
            <option value="alphabetical">Name</option>
            <option value="last_updated">Activity</option>
            <option value="num_points">Points</option>
            <option value="newest">Created</option>
          </select>
        </div>
      </div>
      <div class="hidden sm:block flex-1 min-w-0" aria-hidden="true" />
    </header>

    <!-- Desktop: 25% sidebar + 75% map. Mobile: full-width map only. -->
    <div class="flex-1 min-h-0 flex flex-col sm:flex-row">
      <!-- Tracker List: Desktop sidebar (hidden on mobile) -->
      <aside
        v-if="!isMobileView"
        class="flex flex-col min-h-0 border-r border-gray-200 bg-white sm:w-1/4 sm:flex-shrink-0"
      >
        <TrackerListContent
          ref="listContentDesktopRef"
          v-model:list-tab="listTab"
          :list-tabs="LIST_TABS"
          :visible-trackers-tab="visibleTrackersTab"
          :visible-shared-tab="visibleSharedTab"
          :visible-groups-tab="visibleGroupsTab"
          :visible-shared-groups-tab="visibleSharedGroupsTab"
          :selected-id="selectedId"
          :active-group-id="activeGroupId"
          :highlighted-id="highlightedId"
          :hidden-track-ids="hiddenTrackIds"
          :hidden-group-ids="hiddenGroupIds"
          :loading="loading"
          :list-empty-for-tab="listEmptyForTab"
          scroll-container-class="flex-1 min-h-0 overflow-y-auto space-y-3 px-1 py-1 custom-scrollbar"
          @group-click="onGroupListClick"
          @track-click="onTrackListClick"
          @edit-track="openEditTrackSidebar"
          @open-params="(id) => openSidebar('params', id)"
          @leave-group="leaveGroup"
          @edit-group="openEditGroupModal"
          @view-group="openGroupQuickView"
          @toggle-visibility="toggleTrackVisibility"
          @toggle-group-visibility="toggleGroupVisibility"
          @clear-highlight="highlightedId = null"
        />
      </aside>

    <!-- Map: 75% on desktop, full width on mobile -->
    <main ref="mapColumnRef" class="live-track-map-column flex-1 relative min-h-0">
      <div ref="mapContainer" class="absolute inset-0 w-full h-full bg-gray-100" />

      <!-- Selected item chip: group or tracker name, deselect with X; group icon when locked to a track in a shared group -->
      <div
        v-if="selectedItemLabel"
        class="absolute top-3 left-3 z-20 flex items-center gap-2 rounded-lg border border-blue-200 bg-white/95 px-3 py-2 shadow-sm"
      >
        <button
          v-if="selectedTrackSharedGroup"
          type="button"
          :title="'Open group: ' + (selectedTrackSharedGroup.name || 'Group')"
          class="flex-shrink-0 p-1 rounded-md text-gray-500 hover:text-blue-600 hover:bg-blue-50"
          @click="openGroupQuickView(selectedTrackSharedGroup)"
        >
          <UserGroupIcon class="h-5 w-5" />
        </button>
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

      <!-- Sidebar panel: in-place inside map column so height = map height (no Teleport). pointer-events-none on wrapper so map can pan/zoom; panel has pointer-events-auto. -->
      <div
        v-if="isMapSidebarOpen"
        ref="mapSidebarRef"
        class="absolute inset-0 overflow-hidden flex justify-end z-40 pointer-events-none"
        tabindex="-1"
      >
        <MapSidebarPanel
          :title="mapSidebarTitle"
          :close-emits-overlay-first="sidebarCloseEmitsOverlayFirst"
          @close="closeMapSidebar"
          @close-overlay="onParamsClose"
        >
          <TrackSidebar
            v-if="showTrackSidebar"
            embedded
            :mode="trackSidebarMode"
            :track="trackSidebarTrack"
            :loading="trackSidebarLoading"
            :user-login="userLogin"
            @close="closeMapSidebar"
            @saved="onTrackSidebarSaved"
            @deleted="onTrackDeleted"
            @unsubscribed="onTrackSidebarUnsubscribed"
            @settings-changed="onTrackSettingsChanged"
          />
          <LatestParamsModal
            v-else-if="paramsModalTrackId != null"
            embedded
            :track="paramsModalTrack"
            :param-labels="paramLabels"
            @close="onParamsClose"
          />
          <GroupsSidebarContent
            v-else-if="showGroupsSidebar"
            :groups="sortedGroups"
            :trackers="trackers"
            :api="api"
            :initial-group-id="groupsSidebarInitialGroupId"
            :refreshing="groupsSidebarRefreshing"
            @saved="onGroupsSidebarSaved"
            @refreshed="onGroupsSidebarRefreshed"
            @leave="onGroupsSidebarLeave"
            @hidden-in-list-changed="onGroupHiddenInListChanged"
          />
          <div
            v-else-if="showGroupQuickViewSidebar && groupQuickViewGroup"
            class="flex-1 min-h-0 flex flex-col p-4 overflow-hidden"
          >
            <div class="flex-shrink-0 space-y-3">
              <BaseButton
                variant="primary"
                color="blue"
                size="sm"
                class="w-full"
                :disabled="!(groupQuickViewGroup.track_ids || []).length"
                @click="onGroupQuickViewFitMap"
              >
                Fit map to group
              </BaseButton>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto mt-3 space-y-2">
              <p class="text-sm font-medium text-gray-700">Trackers in group</p>
              <div
                v-for="track in groupQuickViewTracks"
                :key="track.id"
                :class="[
                  'group flex items-center gap-2 p-3 rounded-2xl border transition-all cursor-pointer',
                  selectedId === track.id
                    ? 'border-blue-500 bg-blue-100'
                    : 'border-blue-100 bg-white hover:bg-blue-50 hover:border-blue-300'
                ]"
                @click="zoomToTrackInGroup(track)"
              >
                <div class="flex-1 min-w-0">
                  <div class="text-sm font-medium text-gray-900 truncate">{{ track.name }}</div>
                  <div class="text-xs text-gray-500">{{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'No points' }}</div>
                </div>
                <div class="flex items-center gap-1 flex-shrink-0" @click.stop>
                  <button
                    v-if="track.is_owner"
                    type="button"
                    title="Open in List"
                    class="p-2 rounded-lg text-gray-500 hover:text-blue-600 hover:bg-blue-50"
                    @click="openTrackerInList(track)"
                  >
                    <ListBulletIcon class="h-5 w-5" />
                  </button>
                  <button
                    v-else-if="groupQuickViewGroup?.is_owner"
                    type="button"
                    title="Zoom to Tracker"
                    class="p-2 rounded-lg text-gray-500 hover:text-blue-600 hover:bg-blue-50"
                    @click="zoomToTrackInGroup(track)"
                  >
                    <EyeIcon class="h-5 w-5" />
                  </button>
                  <button
                    type="button"
                    title="Latest Params"
                    class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100"
                    @click="openParamsFromGroupQuickView(track)"
                  >
                    <TableCellsIcon class="h-5 w-5" />
                  </button>
                </div>
              </div>
              <p v-if="groupQuickViewTracks.length === 0" class="text-sm text-gray-500 py-2">No trackers in this group</p>
            </div>
          </div>
          <SharedWithMeSidebarContent
            v-else-if="showSharedWithMeSidebar"
            :trackers="trackers"
            :incoming-trackers="incomingSharedTrackers"
            :incoming-groups="incomingSharedGroups"
            :incoming-public-groups="incomingPublicGroups"
            :shared-groups-on-map="visibleSharedGroupsTab"
            :adding-incoming-id="addingIncomingId"
            :adding-incoming-group-id="addingIncomingGroupId"
            :leaving-share-id="leavingShareId"
            :hidden-track-ids="hiddenTrackIds"
            :unsubscribing-id="unsubscribingId"
            :unsubscribing-group-id="unsubscribingGroupId"
            :refreshing="sharedWithMeRefreshing"
            :api="api"
            @toggle-visibility="toggleTrackVisibility"
            @toggle-group-visibility="toggleGroupVisibility"
            @unsubscribe="onSharedUnsubscribe"
            @unsubscribe-group="onSharedUnsubscribeGroup"
            @select-track="onSharedSidebarSelectTrack"
            @select-group="onSharedSidebarSelectGroup"
            @leave-share="onLeaveShare"
            @add-incoming="onAddIncomingTracker"
            @add-incoming-group="onAddIncomingGroup"
            @leave-group="onSharedWithMeLeaveGroup"
            @open-discover="showDiscoverModal = true"
            @open-shared-list="showSharedListModal = true"
            @refresh="onSharedWithMeRefresh"
          />
          <MapLayerSidebar
            v-else-if="showLayerSidebar"
            :tile-sources="tileSources"
            :selected-layer="selectedLayer"
            @update:selected-layer="onLayerSidebarChange"
          />
          <LiveTrackSettingsSidebarContent
            v-else-if="showSettingsSidebar"
            :hidden-trackers="hiddenTrackersForSettings"
            :hidden-groups="hiddenGroupsForSettings"
            @unhide-tracker="onUnhideTracker"
            @unhide-all-trackers="onUnhideAllTrackers"
            @unhide-tracker-from-map="onUnhideTrackerFromMap"
            @unhide-group="onUnhideGroup"
            @unhide-all-groups="onUnhideAllGroups"
            @unhide-group-from-map="onUnhideGroupFromMap"
          />
        </MapSidebarPanel>
      </div>
    </main>

    <!-- Action strip: top bar on mobile (compact), right strip on desktop -->
    <aside
      class="flex flex-shrink-0 flex-row sm:flex-col w-full sm:w-12 min-h-0 border-b sm:border-b-0 sm:border-l border-gray-200 bg-white items-center justify-center sm:justify-between py-1.5 sm:py-2 gap-2 sm:gap-1 order-first sm:order-last"
      aria-label="Actions"
    >
      <div class="flex flex-row sm:flex-col items-center gap-2 sm:gap-1">
        <button
          type="button"
          title="New Tracker"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          @click="openCreateTrackSidebar()"
        >
          <PlusIcon :class="SIDEBAR_ACTION_ICON_CLASS" />
        </button>
        <button
          type="button"
          title="Groups"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          @click="openSidebar('groups')"
        >
          <UserGroupIcon :class="SIDEBAR_ACTION_ICON_CLASS" />
        </button>
        <button
          type="button"
          title="Shared With Me"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          @click="openSidebar('sharedWithMe')"
        >
          <span class="relative inline-flex">
            <ShareIcon :class="SIDEBAR_ACTION_ICON_CLASS" />
            <span
              v-if="incomingSharedTrackers.length + incomingSharedGroups.length + incomingPublicGroups.length > 0"
              class="absolute -top-1 -right-1 min-w-[0.875rem] h-4 px-0.5 flex items-center justify-center rounded-full bg-blue-500 text-white text-[9px] font-semibold leading-none"
            >
              {{ incomingSharedTrackers.length + incomingSharedGroups.length + incomingPublicGroups.length > 99 ? '99+' : incomingSharedTrackers.length + incomingSharedGroups.length + incomingPublicGroups.length }}
            </span>
          </span>
        </button>
      </div>
      <div class="flex flex-row sm:flex-col items-center gap-2 sm:gap-1">
        <button
          type="button"
          title="Settings"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          @click="openSidebar('settings')"
        >
          <Cog6ToothIcon :class="SIDEBAR_ACTION_ICON_CLASS" />
        </button>
        <button
          type="button"
          title="Refresh All"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          :disabled="actionStripRefreshing"
          @click="onFullRefresh"
        >
          <ArrowPathIcon :class="[SIDEBAR_ACTION_ICON_CLASS, actionStripRefreshing ? 'animate-spin' : '']" />
        </button>
        <button
          type="button"
          title="Map Settings"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          @click="openSidebar('layer')"
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
      </div>
    </aside>
    </div>

    <!-- Tracker List: Mobile – custom drawer (no third-party sheet). Collapse = set height to 25%, no close animation. -->
    <Teleport v-if="isMobileView" to="body">
      <div
        v-if="isMobileView && isSheetOpen && !isMapSidebarOpen"
        ref="mobileDrawerEl"
        :class="['mobile-tracker-drawer', { 'mobile-tracker-drawer--dragging': isDrawerDragging }]"
        :style="{ height: (mobileDrawerHeightPx || mobileDrawerSnapPx[0] || 200) + 'px' }"
        class="flex flex-col min-h-0"
      >
        <div
          class="mobile-drawer-handle"
          role="button"
          tabindex="0"
          aria-label="Drag to Resize"
          @touchstart.passive="onDrawerDragStart"
          @touchmove.prevent="onDrawerDragMove"
          @touchend="onDrawerDragEnd"
          @mousedown="onDrawerDragStart"
        >
          <div class="mobile-drawer-handle-bar" />
        </div>
        <div class="flex-1 min-h-0 overflow-hidden flex flex-col px-2 pb-2 relative">
          <!-- When at peek, whole content area is a drag target (no scroll, drag to expand) -->
          <div
            v-if="isDrawerAtPeek"
            class="mobile-drawer-drag-overlay"
            aria-label="Drag to Expand"
            @touchstart.passive="onDrawerDragStart"
            @touchmove.prevent="onDrawerDragMove"
            @touchend="onDrawerDragEnd"
            @mousedown="onDrawerDragStart"
          />
          <TrackerListContent
            ref="listContentMobileRef"
            v-model:list-tab="listTab"
            :list-tabs="LIST_TABS"
            :visible-trackers-tab="visibleTrackersTab"
            :visible-shared-tab="visibleSharedTab"
            :visible-groups-tab="visibleGroupsTab"
            :visible-shared-groups-tab="visibleSharedGroupsTab"
            :selected-id="selectedId"
            :active-group-id="activeGroupId"
            :highlighted-id="highlightedId"
            :hidden-track-ids="hiddenTrackIds"
            :hidden-group-ids="hiddenGroupIds"
            :loading="loading"
            :list-empty-for-tab="listEmptyForTab"
            :scroll-container-class="['flex-1 min-h-0 space-y-3 px-1 py-1', isDrawerAtPeek ? 'mobile-drawer-content--no-scroll' : 'overflow-y-auto custom-scrollbar'].join(' ')"
            action-opacity-class="opacity-60"
            @group-click="onGroupListClick"
            @track-click="onTrackListClick"
            @edit-track="openEditTrackSidebar"
            @open-params="(id) => openSidebar('params', id)"
            @leave-group="leaveGroup"
            @edit-group="openEditGroupModal"
            @view-group="openGroupQuickView"
            @toggle-visibility="toggleTrackVisibility"
            @toggle-group-visibility="toggleGroupVisibility"
            @clear-highlight="highlightedId = null"
          />
        </div>
      </div>
    </Teleport>

    <DiscoverTrackersModal
      v-if="showDiscoverModal"
      :api="api"
      @close="showDiscoverModal = false"
      @saved="onDiscoverSaved"
    />
    <SharedItemsModal
      :is-open="showSharedListModal"
      :items="sharedByYouTrackers"
      @close="showSharedListModal = false"
      @open-modify-sharing="shareSettingsModalTrack = $event"
      @open-public-popup="publicSharePopupTrack = $event"
    />
    <ShareSettingsModal
      :track="shareSettingsModalTrack"
      :api="api"
      @close="shareSettingsModalTrack = null"
      @saved="onShareSettingsSaved"
    />
    <PublicSharePopup
      :track="publicSharePopupTrack"
      :api="api"
      @close="publicSharePopupTrack = null"
      @deleted="onPublicShareDeleted"
    />
  </div>
</template>

<script>
import { ref, computed, onMounted, onActivated, onBeforeUnmount, inject, watch, nextTick } from 'vue';
import { PlusIcon, PencilIcon, HomeIcon, Square3Stack3DIcon, TableCellsIcon, XMarkIcon, UserGroupIcon, ShareIcon, CloudIcon, EyeIcon, ArrowPathIcon, Cog6ToothIcon, ListBulletIcon } from '@heroicons/vue/24/outline';
import { useWindowSize, useScrollLock } from '@vueuse/core';
import { getIngressBodyTemplate } from './ingressBodyTemplateCache.js';
import { trackersLiveSocket } from './trackersLiveSocket.js';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import TrackSidebar from './TrackSidebar.vue';
import TrackDirectionIcon from './TrackDirectionIcon.vue';
import LatestParamsModal from './LatestParamsModal.vue';
import GroupsSidebarContent from './GroupsSidebarContent.vue';
import DiscoverTrackersModal from './DiscoverTrackersModal.vue';
import SharedItemsModal from './SharedItemsModal.vue';
import ShareSettingsModal from './ShareSettingsModal.vue';
import PublicSharePopup from './PublicSharePopup.vue';
import MapLayerSidebar from './MapLayerSidebar.vue';
import MapSidebarPanel from './MapSidebarPanel.vue';
import SharedWithMeSidebarContent from './SharedWithMeSidebarContent.vue';
import LiveTrackSettingsSidebarContent from './LiveTrackSettingsSidebarContent.vue';
import TrackerListContent from './TrackerListContent.vue';
import { getCoordsSortedByTime, getTrackDirectionAngle, splitTrackIntoSegments } from './trackGeometry.js';
import { getArrowImageId, ensureArrowImage } from './trackArrowMap.js';
import { getRasterSourceSpec, getRasterLayerMaxZoom, replaceRasterBaseLayer } from './mapTileUtils.js';
import { setupMapFollowListeners } from './mapFollowLock.js';
import { useTileSources } from './useTileSources.js';
import { formatTimestampLocal } from './paramFormatters.js';

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
const LAYER_MAX_ZOOM = MAX_ZOOM + 1;
const TILE_SOURCES_API_URL = '/api/tiles/sources/';
/** Shared button class for all right-sidebar action icons. Ring only on focus-visible so tap on mobile doesn't show thick border; no tap highlight. */
const SIDEBAR_ACTION_BUTTON_CLASS =
  'p-1.5 sm:p-2 rounded-lg text-blue-600 hover:bg-blue-50 active:bg-blue-100 focus:outline-none focus:ring-0 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white [-webkit-tap-highlight-color:transparent]';
const SIDEBAR_ACTION_ICON_CLASS = 'h-5 w-5 sm:h-6 sm:w-6';
const DEFAULT_MAP_KEY = 'extensions.live_track.default_map';
const DEFAULT_SORT_KEY = 'extensions.live_track.default_sort';
const VALID_SORT_VALUES = new Set(['alphabetical', 'last_updated', 'num_points', 'newest']);
const CENTER_DEBOUNCE_MS = 220;
/** Duration (ms) for minimal map snap animations. */
const MAP_SNAP_DURATION = 200;
const LIST_TABS = [
  { id: 'trackers', label: 'Trackers' },
  { id: 'groups', label: 'Groups' },
  { id: 'shared', label: 'Shared' }
];

export default {
  name: 'LiveTrackView',
  components: { BaseButton, TrackSidebar, TrackDirectionIcon, LatestParamsModal, GroupsSidebarContent, DiscoverTrackersModal, SharedItemsModal, ShareSettingsModal, PublicSharePopup, MapLayerSidebar, MapSidebarPanel, SharedWithMeSidebarContent, LiveTrackSettingsSidebarContent, TrackerListContent, PlusIcon, PencilIcon, HomeIcon, Square3Stack3DIcon, TableCellsIcon, XMarkIcon, UserGroupIcon, ShareIcon, CloudIcon, EyeIcon, ArrowPathIcon, Cog6ToothIcon, ListBulletIcon },
  setup() {
    const api = inject('extensionApi');
    const trackers = ref([]);
    const groups = ref([]);
    const sortBy = ref('alphabetical');
    const showDiscoverModal = ref(false);
    const showSharedListModal = ref(false);
    const shareSettingsModalTrack = ref(null);
    const publicSharePopupTrack = ref(null);
    /** Track IDs hidden from the map (eye/eye-slash). Reactive: replace Set to trigger updates. */
    const hiddenTrackIds = ref(new Set());
    /** Group IDs hidden from the map (so left tab can show eye state). Synced with server. */
    const hiddenGroupIds = ref(new Set());
    const unsubscribingId = ref(null);
    const unsubscribingGroupId = ref(null);
    const loading = ref(true);
    const selectedId = ref(null);
    const activeGroupId = ref(null);
    const followLocked = ref(false);
    const isAutoMoving = ref(false);
    const isMobileView = ref(
      typeof window !== 'undefined' ? window.matchMedia('(max-width: 639px)').matches : false
    );
    const isSheetOpen = ref(false);
    let mobileQueryListener = null;

    const mobileDrawerEl = ref(null);
    const mobileDrawerHeightPx = ref(0);
    const isDrawerDragging = ref(false);
    const mobileDrawerDrag = ref({ active: false, startY: 0, startHeight: 0 });
    let drawerMouseUpListener = null;
    let drawerMouseMoveListener = null;
    let drawerRafId = null;
    let pendingDragY = null;

    const { height: windowHeight } = useWindowSize();
    const rootContainer = ref(null);
    const bodyScrollLock = useScrollLock(typeof document !== 'undefined' ? document.body : null);

    watch([isMobileView, isSheetOpen], ([mobile, open]) => {
      if (typeof document === 'undefined') return;
      bodyScrollLock.value = mobile && open;
    }, { immediate: true });

    onBeforeUnmount(() => {
      bodyScrollLock.value = false;
    });

    const trackerMaxHeight = computed(() => {
      // App nav = 64px, tracker title bar = 64px, small buffer = 4px.
      // Max sheet height = viewport minus those so sheet stops at bottom of tracker title.
      const APP_NAV_PX = 64;
      const TRACKER_HEADER_PX = 64;
      const BUFFER_PX = 4;
      return Math.max(65, windowHeight.value - APP_NAV_PX - TRACKER_HEADER_PX - BUFFER_PX);
    });

    const trackerSheetSnapPoints = computed(() => {
      return ['25%', trackerMaxHeight.value];
    });

    const mobileDrawerSnapPx = computed(() => {
      const h = windowHeight.value;
      return [
        Math.round(h * 0.25),
        trackerMaxHeight.value
      ];
    });

    const isDrawerAtPeek = computed(() => {
      const current = mobileDrawerHeightPx.value || mobileDrawerSnapPx.value[0];
      const peek = mobileDrawerSnapPx.value[0];
      return current <= peek + 2;
    });

    function isRecentlyUpdated(track) {
      if (!track.last_timestamp_ms) return false;
      const fiveMinutesAgo = Date.now() - 5 * 60 * 1000;
      return track.last_timestamp_ms > fiveMinutesAgo;
    }

    const trackerIdsOnMap = computed(() => new Set(trackers.value.map((t) => String(t.id))));

    function isAcceptedOrOwnedGroup(group) {
      if (group?.is_owner === true) return true;
      const trackIds = group?.track_ids || [];
      return trackIds.some((id) => trackerIdsOnMap.value.has(String(id)));
    }

    const sortedGroups = computed(() => {
      return [...groups.value]
        .filter((group) => isAcceptedOrOwnedGroup(group))
        .sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    });

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

    const listTab = ref('trackers');
    const visibleTrackersTab = computed(() =>
      sortedTrackers.value.filter((t) => t.is_owner === true && !(t.settings && t.settings.hidden_in_list))
    );
    const visibleSharedTab = computed(() => {
      const sharedGroups = sortedGroups.value.filter((g) => g.is_owner !== true && !hiddenGroupIds.value.has(String(g.id)));
      const trackIdsInSharedGroups = new Set(
        sharedGroups.flatMap((g) => (g.track_ids || []).map((id) => String(id)))
      );
      return sortedTrackers.value.filter(
        (t) =>
          t.is_owner !== true &&
          !trackIdsInSharedGroups.has(String(t.id)) &&
          !hiddenTrackIds.value.has(String(t.id))
      );
    });
    const visibleGroupsTab = computed(() =>
      sortedGroups.value.filter((g) => g.is_owner === true && !g.hidden_in_list)
    );
    const visibleSharedGroupsTab = computed(() =>
      sortedGroups.value.filter((g) => g.is_owner !== true && !hiddenGroupIds.value.has(String(g.id)))
    );
    const hiddenTrackersForSettings = computed(() => {
      const listHidden = trackers.value
        .filter((t) => t.is_owner === true && (t.settings && t.settings.hidden_in_list))
        .map((t) => ({ id: t.id, name: t.name, is_owner: t.is_owner, source: 'list' }));
      // Only show map-hidden trackers that are not part of a hidden group (group shows in Hidden groups instead)
      const mapHidden = trackers.value
        .filter((t) => {
          if (!hiddenTrackIds.value.has(String(t.id))) return false;
          const trackIdStr = String(t.id);
          const isInHiddenGroup = sortedGroups.value.some(
            (g) => hiddenGroupIds.value.has(String(g.id)) && (g.track_ids || []).map((id) => String(id)).includes(trackIdStr)
          );
          return !isInHiddenGroup;
        })
        .map((t) => ({ id: t.id, name: t.name, is_owner: t.is_owner, source: 'map' }));
      return [...listHidden, ...mapHidden];
    });
    const hiddenGroupsForSettings = computed(() => {
      const listHidden = sortedGroups.value
        .filter((g) => g.is_owner === true && g.hidden_in_list)
        .map((g) => ({ id: g.id, name: g.name, is_owner: g.is_owner, source: 'list' }));
      const mapHidden = sortedGroups.value
        .filter((g) => hiddenGroupIds.value.has(String(g.id)))
        .map((g) => ({ id: g.id, name: g.name, is_owner: g.is_owner, source: 'map' }));
      return [...listHidden, ...mapHidden];
    });
    const sharedByYouTrackers = computed(() =>
      trackers.value.filter(
        (t) => t.is_owner === true && (t.visibility === 'shared' || t.visibility === 'public')
      )
    );
    const activeGroup = computed(() => {
      const id = activeGroupId.value;
      if (id == null) return null;
      return sortedGroups.value.find((g) => String(g.id) === String(id)) ?? null;
    });
    const selectedItemLabel = computed(() => {
      if (activeGroup.value) return activeGroup.value.name ?? '';
      const id = selectedId.value;
      if (id == null) return null;
      const track = trackers.value.find((t) => String(t.id) === String(id));
      return track?.name ?? null;
    });
    /** When locked to a tracker that belongs to a shared group (not yours), the first such group for opening the group sidebar. */
    const selectedTrackSharedGroup = computed(() => {
      const id = selectedId.value;
      if (id == null) return null;
      const idStr = String(id);
      return sortedGroups.value.find(
        (g) => g.is_owner !== true && (g.track_ids || []).some((tid) => String(tid) === idStr)
      ) ?? null;
    });
    const listEmptyForTab = computed(() => {
      if (listTab.value === 'trackers') return visibleTrackersTab.value.length === 0;
      if (listTab.value === 'groups') return visibleGroupsTab.value.length === 0;
      if (listTab.value === 'shared') return visibleSharedTab.value.length === 0 && visibleSharedGroupsTab.value.length === 0;
      return true;
    });
    const showTrackSidebar = ref(false);
    const paramsModalTrackId = ref(null);
    const paramsModalTrack = computed(() => {
      const id = paramsModalTrackId.value;
      if (id == null) return null;
      const t = trackers.value.find((tr) => tr.id === id);
      return t ?? null;
    });
    const paramLabels = ref({});
    const trackSidebarMode = ref('create');
    const trackSidebarTrack = ref(null);
    const trackSidebarLoading = ref(false);
    const mapContainer = ref(null);
    const mapColumnRef = ref(null);
    const mapSidebarRef = ref(null);
    const listContentDesktopRef = ref(null);
    const listContentMobileRef = ref(null);
    const listScrollContainer = computed(() => {
      const c = isMobileView.value ? listContentMobileRef.value : listContentDesktopRef.value;
      return c?.scrollContainerRef?.value ?? null;
    });
    const showGroupsSidebar = ref(false);
    const groupsSidebarInitialGroupId = ref(null);
    const groupsSidebarRefreshing = ref(false);
    const showGroupQuickViewSidebar = ref(false);
    const groupQuickViewGroup = ref(null);
    /** When set, closing the params sidebar should return to this group quick view instead of closing the sidebar. */
    const groupQuickViewReturnAfterParams = ref(null);
    const showSharedWithMeSidebar = ref(false);
    const incomingSharedTrackers = ref([]);
    const incomingSharedGroups = ref([]);
    const incomingPublicGroups = ref([]);
    const addingIncomingId = ref(null);
    const addingIncomingGroupId = ref(null);
    const leavingShareId = ref(null);
    const sharedWithMeRefreshing = ref(false);
    const actionStripRefreshing = ref(false);
    const showLayerSidebar = ref(false);
    const showSettingsSidebar = ref(false);

    const isMapSidebarOpen = computed(
      () =>
        showTrackSidebar.value ||
        paramsModalTrackId.value != null ||
        showGroupsSidebar.value ||
        showGroupQuickViewSidebar.value ||
        showSharedWithMeSidebar.value ||
        showLayerSidebar.value ||
        showSettingsSidebar.value
    );

    const mapSidebarTitle = computed(() => {
      if (showTrackSidebar.value) return trackSidebarMode.value === 'create' ? 'New Tracker' : 'Edit Tracker';
      if (paramsModalTrackId.value != null) return 'Latest Parameters';
      if (showGroupsSidebar.value) return 'Groups';
      if (showGroupQuickViewSidebar.value && groupQuickViewGroup.value) return groupQuickViewGroup.value.name || 'Group';
      if (showSharedWithMeSidebar.value) return 'Shared With Me';
      if (showLayerSidebar.value) return 'Map Settings';
      if (showSettingsSidebar.value) return 'Settings';
      return '';
    });

    /** When true, panel header X emits close-overlay so we pop back to group quick view instead of closing the sidebar. */
    const sidebarCloseEmitsOverlayFirst = computed(
      () => paramsModalTrackId.value != null && groupQuickViewReturnAfterParams.value != null
    );

    const groupQuickViewTracks = computed(() => {
      const g = groupQuickViewGroup.value;
      if (!g?.track_ids?.length) return [];
      return (g.track_ids || [])
        .map((id) => trackers.value.find((t) => String(t.id) === String(id)))
        .filter(Boolean);
    });

    watch(
      () => isMapSidebarOpen.value,
      (open) => {
        if (open) nextTick(() => mapSidebarRef.value?.focus());
      }
    );

    const highlightedId = ref(null);
    const userLogin = ref('');
    const { tileSources, selectedLayer, fetchTileSources } = useTileSources({
      apiUrl: TILE_SOURCES_API_URL,
      afterFetch: (tileSourcesRef, selectedLayerRef) => applyDefaultMapFromStore(tileSourcesRef, selectedLayerRef)
    });
    let map = null;
    let trackUpdatedHandler = null;
    let centerDebounceId = null;

    const formatTime = (ms) => formatTimestampLocal(ms);

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

    async function fetchGroups() {
      try {
        const res = await api.get('/groups/');
        groups.value = Array.isArray(res.data) ? res.data : [];
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load groups');
        }
      }
    }

    async function fetchTrackers(options) {
      const skipGlobalLoading = options?.skipGlobalLoading === true;
      if (!skipGlobalLoading) loading.value = true;
      try {
        const res = await api.get('/trackers/');
        const raw = Array.isArray(res.data) ? res.data : [];
        // Fetch full geometry for each tracker (list endpoint returns metadata only)
        const withGeometry = await Promise.all(
          raw.map(async (t) => {
            try {
              const geomRes = await api.get(`/trackers/${t.id}/geometry/`);
              // Preserve list-only fields (is_owner, owner_email, visibility) — geometry endpoint doesn't return them
              return normalizeTrackForMemory({
                ...geomRes.data,
                is_owner: t.is_owner,
                owner_email: t.owner_email,
                visibility: t.visibility
              });
            } catch {
              return normalizeTrackForMemory({ ...t, geometry: { type: 'LineString', coordinates: [] } });
            }
          })
        );
        trackers.value = withGeometry;
        updateMapFeatures();
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load trackers');
        }
      } finally {
        if (!skipGlobalLoading) loading.value = false;
      }
    }

    async function fetchMapVisibility() {
      try {
        const res = await api.get('/map-visibility/');
        const data = res.data || {};
        const trackIds = Array.isArray(data.hidden_track_ids) ? data.hidden_track_ids : [];
        const groupIds = Array.isArray(data.hidden_group_ids) ? data.hidden_group_ids : [];
        hiddenTrackIds.value = new Set(trackIds.map((id) => String(id)));
        hiddenGroupIds.value = new Set(groupIds.map((id) => String(id)));
        // Expand hidden groups into track IDs so map filter (hiddenTrackIds) is correct
        for (const gid of hiddenGroupIds.value) {
          const group = groups.value.find((g) => String(g.id) === gid);
          if (group?.track_ids?.length) {
            const s = new Set(hiddenTrackIds.value);
            for (const tid of group.track_ids) s.add(String(tid));
            hiddenTrackIds.value = s;
          }
        }
        updateMapFeatures();
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load map visibility');
        }
      }
    }

    async function patchMapVisibility() {
      try {
        await api.patch('/map-visibility/', {
          hidden_track_ids: [...hiddenTrackIds.value],
          hidden_group_ids: [...hiddenGroupIds.value],
        });
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to save map visibility');
        }
      }
    }

    async function fetchIncomingShared() {
      try {
        const res = await api.get('/trackers/available-to-add/');
        const data = res.data || {};
        incomingSharedTrackers.value = Array.isArray(data.shared_with_me) ? data.shared_with_me : [];
        incomingSharedGroups.value = Array.isArray(data.shared_with_me_groups) ? data.shared_with_me_groups : [];
        incomingPublicGroups.value = Array.isArray(data.public_groups) ? data.public_groups : [];
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load incoming shares');
        }
        incomingSharedTrackers.value = [];
        incomingSharedGroups.value = [];
        incomingPublicGroups.value = [];
      }
    }

    async function onSharedWithMeRefresh() {
      sharedWithMeRefreshing.value = true;
      try {
        await fetchIncomingShared();
      } finally {
        sharedWithMeRefreshing.value = false;
      }
    }

    function onSharedWithMeLeaveGroup(group) {
      if (!group?.id) return;
      groups.value = groups.value.filter((g) => String(g.id) !== String(group.id));
    }

    function onSharedSidebarSelectTrack(track) {
      listTab.value = 'shared';
      onTrackListClick(track);
    }

    function onSharedSidebarSelectGroup(group) {
      listTab.value = 'shared';
      onGroupListClick(group);
    }

    async function onFullRefresh() {
      actionStripRefreshing.value = true;
      try {
        await fetchGroups();
        await fetchIncomingShared();
        await fetchTrackers({ skipGlobalLoading: true });
      } finally {
        actionStripRefreshing.value = false;
      }
    }

    function buildLinesGeoJSON() {
      const hidden = hiddenTrackIds.value;
      const features = [];
      for (const track of trackers.value) {
        if (hidden.has(String(track.id))) continue;
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

    function hexToRgb(hex) {
      const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex || '#6C93DE');
      return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [51, 136, 255];
    }

    function buildPointsGeoJSON() {
      const hidden = hiddenTrackIds.value;
      const features = [];
      for (const track of trackers.value) {
        if (hidden.has(String(track.id))) continue;
        const coordsSorted = getCoordsSortedByTime(track);
        const last = coordsSorted.length ? coordsSorted[coordsSorted.length - 1] : null;
        const pos = (last && last.length >= 2) ? [last[0], last[1]] : (track.last_position ? [track.last_position.lon, track.last_position.lat] : null);
        if (!pos) continue;
        const color = track.color || '#6C93DE';
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
          return ensureArrowImage(map, color, selected);
        })
      );
      pointSource.setData(pointsGeoJSON);
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
        'circle-stroke-color': '#6C93DE',
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
        await ensureArrowImage(map, '#6C93DE', false);
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
          setupMapFollowListenersForView();
          disableMapRotation();
          map.once('load', () => {
            if (!map) return;
            map.resize();
            ensureArrowImage(map, '#6C93DE', false).then(() => {
              if (!map || !map.getStyle()) return;
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
        setupMapFollowListenersForView();
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
      setupMapFollowListenersForView();
      disableMapRotation();

      map.once('load', () => {
        if (!map) return;
        map.resize();
        ensureArrowImage(map, '#6C93DE', false).then(() => {
          if (!map || !map.getStyle()) return;
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

    function setupMapFollowListenersForView() {
      if (!map) return;
      setupMapFollowListeners(map, {
        getLocked: () => followLocked.value,
        setLocked: (v) => { followLocked.value = v; if (!v) selectedId.value = null; }
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
      activeGroupId.value = null;
      if (selectedId.value === track.id) {
        selectedId.value = null;
        followLocked.value = false;
        if (isMobileView.value) collapseDrawerToPeek();
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
      if (isMobileView.value) collapseDrawerToPeek();
    }

    /** Collapse drawer to 25% – just set height; no close animation, no bounce. */
    function collapseDrawerToPeek() {
      if (!isMobileView.value) return;
      const snaps = mobileDrawerSnapPx.value;
      if (snaps[0] != null) mobileDrawerHeightPx.value = snaps[0];
    }

    function onDrawerDragStart(e) {
      const y = e.touches ? e.touches[0].clientY : e.clientY;
      mobileDrawerDrag.value = { active: true, startY: y, startHeight: mobileDrawerHeightPx.value };
      isDrawerDragging.value = true;
      if (!e.touches) {
        drawerMouseMoveListener = (e2) => onDrawerDragMove(e2);
        drawerMouseUpListener = () => onDrawerDragEnd();
        document.addEventListener('mousemove', drawerMouseMoveListener);
        document.addEventListener('mouseup', drawerMouseUpListener);
      }
    }

    function applyDrawerHeightFromDrag(y) {
      const drag = mobileDrawerDrag.value;
      if (!drag.active) return;
      const deltaY = drag.startY - y;
      const snaps = mobileDrawerSnapPx.value;
      const minH = snaps[0];
      const maxH = snaps[1];
      let h = Math.round(drag.startHeight + deltaY);
      h = Math.max(minH, Math.min(maxH, h));
      mobileDrawerHeightPx.value = h;
    }

    function onDrawerDragMove(e) {
      if (!mobileDrawerDrag.value.active) return;
      const y = e.touches ? e.touches[0].clientY : e.clientY;
      pendingDragY = y;
      if (drawerRafId == null) {
        drawerRafId = requestAnimationFrame(() => {
          drawerRafId = null;
          if (pendingDragY != null) {
            applyDrawerHeightFromDrag(pendingDragY);
            pendingDragY = null;
          }
        });
      }
    }

    function onDrawerDragEnd() {
      if (!mobileDrawerDrag.value.active) return;
      if (drawerRafId != null) {
        cancelAnimationFrame(drawerRafId);
        drawerRafId = null;
      }
      if (pendingDragY != null) applyDrawerHeightFromDrag(pendingDragY);
      pendingDragY = null;
      mobileDrawerDrag.value = { active: false, startY: 0, startHeight: 0 };
      isDrawerDragging.value = false;
      if (drawerMouseMoveListener) {
        document.removeEventListener('mousemove', drawerMouseMoveListener);
        document.removeEventListener('mouseup', drawerMouseUpListener);
        drawerMouseUpListener = null;
        drawerMouseMoveListener = null;
      }
      const snaps = mobileDrawerSnapPx.value;
      const current = mobileDrawerHeightPx.value;
      const mid = (snaps[0] + snaps[1]) / 2;
      mobileDrawerHeightPx.value = current >= mid ? snaps[1] : snaps[0];
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
      const paddingPx = 80;
      map.fitBounds([[minLon, minLat], [maxLon, maxLat]], {
        padding: { top: paddingPx, bottom: paddingPx, left: paddingPx, right: paddingPx },
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

    function openCreateTrackSidebar() {
      if (showTrackSidebar.value && trackSidebarMode.value === 'create') { closeMapSidebar(); return; }
      trackSidebarMode.value = 'create';
      trackSidebarTrack.value = null;
      openSidebar('track');
    }

    function openCreateGroupModal() {
      groupsSidebarInitialGroupId.value = null;
      openSidebar('groups');
    }

    function openEditGroupModal(group) {
      openSidebar('groups', group?.id ?? null);
    }

    function closeMapSidebar() {
      showTrackSidebar.value = false;
      paramsModalTrackId.value = null;
      groupQuickViewReturnAfterParams.value = null;
      showGroupsSidebar.value = false;
      groupsSidebarInitialGroupId.value = null;
      showGroupQuickViewSidebar.value = false;
      groupQuickViewGroup.value = null;
      showSharedWithMeSidebar.value = false;
      showLayerSidebar.value = false;
      showSettingsSidebar.value = false;
    }

    function onParamsClose() {
      if (groupQuickViewReturnAfterParams.value) {
        paramsModalTrackId.value = null;
        const group = groupQuickViewReturnAfterParams.value;
        groupQuickViewReturnAfterParams.value = null;
        showGroupQuickViewSidebar.value = true;
        groupQuickViewGroup.value = group;
      } else {
        closeMapSidebar();
      }
    }

    function openParamsFromGroupQuickView(track) {
      if (!track?.id || !groupQuickViewGroup.value) return;
      groupQuickViewReturnAfterParams.value = groupQuickViewGroup.value;
      paramsModalTrackId.value = track.id;
      showGroupQuickViewSidebar.value = false;
    }

    /** Open one sidebar and close the others. type: 'track' | 'params' | 'groups' | 'groupQuickView' | 'sharedWithMe' | 'layer'. payload: for 'params' the track id; for 'groups' optional group id; for 'groupQuickView' the group object. Clicking the same menubar icon again closes the sidebar. */
    function openSidebar(type, payload) {
      if (type === 'groups' && showGroupsSidebar.value) { closeMapSidebar(); return; }
      if (type === 'sharedWithMe' && showSharedWithMeSidebar.value) { closeMapSidebar(); return; }
      if (type === 'layer' && showLayerSidebar.value) { closeMapSidebar(); return; }
      if (type === 'settings' && showSettingsSidebar.value) { closeMapSidebar(); return; }
      showTrackSidebar.value = false;
      paramsModalTrackId.value = null;
      groupQuickViewReturnAfterParams.value = null;
      showGroupsSidebar.value = false;
      groupsSidebarInitialGroupId.value = null;
      showGroupQuickViewSidebar.value = false;
      groupQuickViewGroup.value = null;
      showSharedWithMeSidebar.value = false;
      showLayerSidebar.value = false;
      showSettingsSidebar.value = false;
      if (type === 'track') showTrackSidebar.value = true;
      else if (type === 'params') paramsModalTrackId.value = payload ?? null;
      else if (type === 'groups') {
        showGroupsSidebar.value = true;
        if (payload != null && payload !== '') groupsSidebarInitialGroupId.value = payload;
      } else if (type === 'groupQuickView' && payload) {
        showGroupQuickViewSidebar.value = true;
        groupQuickViewGroup.value = payload;
      } else if (type === 'sharedWithMe') {
        showSharedWithMeSidebar.value = true;
        fetchIncomingShared();
      } else if (type === 'layer') showLayerSidebar.value = true;
      else if (type === 'settings') showSettingsSidebar.value = true;
    }

    function onLayerSidebarChange(layerValue) {
      selectedLayer.value = layerValue;
      onLayerChange();
    }

    async function onUnhideTracker(trackerId) {
      if (!trackerId || !api) return;
      try {
        await api.post(`/trackers/${trackerId}/settings/`, { hidden_in_list: false });
        const idStr = String(trackerId);
        const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
        if (idx >= 0) {
          const t = trackers.value[idx];
          const settings = { ...(t.settings || {}), hidden_in_list: false };
          trackers.value = trackers.value.slice(0, idx).concat({ ...t, settings }).concat(trackers.value.slice(idx + 1));
        }
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker shown in list');
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to unhide');
      }
    }

    function onUnhideTrackerFromMap(trackId) {
      const idStr = String(trackId);
      const s = new Set(hiddenTrackIds.value);
      s.delete(idStr);
      hiddenTrackIds.value = s;
      updateMapFeatures();
      patchMapVisibility();
    }

    async function onUnhideAllTrackers() {
      const list = hiddenTrackersForSettings.value;
      for (const t of list) {
        if (t.source === 'list') await onUnhideTracker(t.id);
        else onUnhideTrackerFromMap(t.id);
      }
    }

    async function onUnhideGroup(groupId) {
      if (!groupId || !api) return;
      try {
        await api.patch(`/groups/${groupId}/`, { hidden_in_list: false });
        const idStr = String(groupId);
        const idx = groups.value.findIndex((g) => String(g.id) === idStr);
        if (idx >= 0) {
          const g = groups.value[idx];
          groups.value = groups.value.slice(0, idx).concat({ ...g, hidden_in_list: false }).concat(groups.value.slice(idx + 1));
        }
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group shown in list');
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to unhide');
      }
    }

    function onUnhideGroupFromMap(groupId) {
      const idStr = String(groupId);
      const group = groups.value.find((g) => String(g.id) === idStr);
      const gs = new Set(hiddenGroupIds.value);
      gs.delete(idStr);
      hiddenGroupIds.value = gs;
      const trackIds = (group?.track_ids || []).map((id) => String(id));
      const s = new Set(hiddenTrackIds.value);
      trackIds.forEach((id) => s.delete(id));
      hiddenTrackIds.value = s;
      updateMapFeatures();
      patchMapVisibility();
    }

    async function onUnhideAllGroups() {
      const list = hiddenGroupsForSettings.value;
      for (const g of list) {
        if (g.source === 'list') await onUnhideGroup(g.id);
        else onUnhideGroupFromMap(g.id);
      }
    }

    function onShareSettingsSaved(updated) {
      if (!updated?.id) return;
      const idStr = String(updated.id);
      const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
      if (idx >= 0) {
        const t = trackers.value[idx];
        trackers.value = trackers.value.slice(0, idx).concat({ ...t, ...updated }).concat(trackers.value.slice(idx + 1));
      }
      shareSettingsModalTrack.value = null;
    }

    function onPublicShareDeleted(updated) {
      if (!updated?.id) return;
      const idStr = String(updated.id);
      const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
      if (idx >= 0) {
        const t = trackers.value[idx];
        trackers.value = trackers.value.slice(0, idx).concat({ ...t, ...updated }).concat(trackers.value.slice(idx + 1));
      }
      publicSharePopupTrack.value = null;
    }

    function onGroupsSidebarSaved() {
      fetchGroups();
      fetchTrackers();
    }

    async function onGroupsSidebarRefreshed() {
      groupsSidebarRefreshing.value = true;
      try {
        await fetchGroups();
      } finally {
        groupsSidebarRefreshing.value = false;
      }
    }

    function removeGroupFromLocalState(group) {
      const groupId = group?.id;
      if (groupId == null) return;
      const trackIdsInGroup = new Set((group.track_ids || []).map((id) => String(id)));
      groups.value = groups.value.filter((g) => String(g.id) !== String(groupId));
      trackers.value = trackers.value.filter((t) => !trackIdsInGroup.has(String(t.id)));
      if (String(activeGroupId.value) === String(groupId)) activeGroupId.value = null;
      if (groupQuickViewGroup.value && String(groupQuickViewGroup.value.id) === String(groupId)) {
        showGroupQuickViewSidebar.value = false;
        groupQuickViewGroup.value = null;
      }
      if (selectedId.value != null && trackIdsInGroup.has(String(selectedId.value))) selectedId.value = null;
      updateMapFeatures();
    }

    async function onGroupsSidebarLeave(group) {
      if (!group?.id) return;
      if (!confirm('Leave this group? You will no longer see its trackers on the map or in Shared.')) return;
      try {
        await api.delete(`/groups/${group.id}/leave/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Left group');
        removeGroupFromLocalState(group);
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to leave group');
      }
    }

    function fitMapToGroupTracks(group) {
      if (!map || !group?.track_ids?.length) return;
      const trackIds = new Set(group.track_ids);
      const coords = [];
      for (const track of trackers.value) {
        if (trackIds.has(track.id)) {
          coords.push(...getLastNCoords(track, LAST_POINTS_FIT));
        }
      }
      if (coords.length === 0) return;
      fitBoundsFromCoords(coords);
    }

    function onGroupListClick(group) {
      activeGroupId.value = group?.id ?? null;
      selectedId.value = null;
      fitMapToGroupTracks(group);
    }

    function openGroupQuickView(group) {
      openSidebar('groupQuickView', group);
    }

    function onGroupQuickViewFitMap() {
      const g = groupQuickViewGroup.value;
      if (!g) return;
      fitMapToGroupTracks(g);
      activeGroupId.value = g.id ?? null;
    }

    function zoomToTrackInGroup(track) {
      highlightedId.value = null;
      activeGroupId.value = null;
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
      if (isMobileView.value) collapseDrawerToPeek();
    }

    function openTrackerInList(track) {
      if (!track?.id) return;
      highlightedId.value = null;
      activeGroupId.value = null;
      listTab.value = 'trackers';
      selectedId.value = track.id;
      followLocked.value = true;
      updateMapFeatures();
      nextTick(() => {
        const scrollEl = listScrollContainer.value;
        if (scrollEl) {
          const row = scrollEl.querySelector(`[data-track-id="${track.id}"]`);
          if (row) row.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
      });
      if (isMobileView.value) collapseDrawerToPeek();
    }

    function deselectGroup() {
      activeGroupId.value = null;
    }

    function deselectSelection() {
      activeGroupId.value = null;
      selectedId.value = null;
      if (isMobileView.value) collapseDrawerToPeek();
    }

    async function leaveGroup(group) {
      if (!group?.id) return;
      if (!confirm('Leave this group? You will no longer see its trackers on the map or in Shared.')) return;
      try {
        await api.delete(`/groups/${group.id}/leave/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Left group');
        removeGroupFromLocalState(group);
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to leave group');
      }
    }

    function openEditTrackSidebar(track) {
      trackSidebarMode.value = 'edit';
      trackSidebarTrack.value = null;
      trackSidebarLoading.value = true;
      openSidebar('track');
      nextTick(() => {
        api.get(`/trackers/${track.id}/`)
          .then((res) => {
            trackSidebarTrack.value = res.data;
          })
          .catch((e) => {
            const err = api.handleError?.(e);
            if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load tracker');
            showTrackSidebar.value = false;
          })
          .finally(() => {
            trackSidebarLoading.value = false;
          });
      });
    }

    function onTrackSidebarSaved() {
      showTrackSidebar.value = false;
      fetchTrackers();
    }

    function onTrackSettingsChanged(payload) {
      const { trackId, hidden_in_list } = payload || {};
      if (trackId == null) return;
      const idStr = String(trackId);
      const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
      if (idx < 0) return;
      const t = trackers.value[idx];
      const settings = { ...(t.settings || {}), hidden_in_list };
      trackers.value = trackers.value.slice(0, idx).concat([{ ...t, settings }]).concat(trackers.value.slice(idx + 1));
      updateMapFeatures();
    }

    function onTrackSidebarUnsubscribed(trackId) {
      showTrackSidebar.value = false;
      if (!trackId) return;
      const idStr = String(trackId);
      moveTrackToIncoming(trackId);
      trackers.value = trackers.value.filter((t) => String(t.id) !== idStr);
      if (String(selectedId.value) === idStr) selectedId.value = null;
      const s = new Set(hiddenTrackIds.value);
      s.delete(trackId);
      hiddenTrackIds.value = s;
      updateMapFeatures();
    }

    function onTrackDeleted() {
      showTrackSidebar.value = false;
      fetchTrackers();
    }

    function onCreateGroupSaved() {
      closeMapSidebar();
      fetchGroups();
    }

    function onGroupHiddenInListChanged(payload) {
      const { groupId, hiddenInList: value } = payload || {};
      if (groupId == null) return;
      const idStr = String(groupId);
      const idx = groups.value.findIndex((g) => String(g.id) === idStr);
      if (idx < 0) return;
      const g = groups.value[idx];
      groups.value = groups.value.slice(0, idx).concat([{ ...g, hidden_in_list: !!value }]).concat(groups.value.slice(idx + 1));
    }

    function toggleTrackVisibility(trackId) {
      const idStr = String(trackId);
      const s = new Set(hiddenTrackIds.value);
      if (s.has(idStr)) s.delete(idStr);
      else s.add(idStr);
      hiddenTrackIds.value = s;
      updateMapFeatures();
      patchMapVisibility();
    }

    function toggleGroupVisibility(group) {
      const trackIds = (group?.track_ids || []).map((id) => String(id));
      if (trackIds.length === 0) return;
      const groupIdStr = group?.id != null ? String(group.id) : null;
      const s = new Set(hiddenTrackIds.value);
      const gs = new Set(hiddenGroupIds.value);
      const allHidden = trackIds.every((id) => s.has(id));
      if (allHidden) {
        trackIds.forEach((id) => s.delete(id));
        if (groupIdStr) gs.delete(groupIdStr);
      } else {
        trackIds.forEach((id) => s.add(id));
        if (groupIdStr) gs.add(groupIdStr);
      }
      hiddenTrackIds.value = s;
      hiddenGroupIds.value = gs;
      updateMapFeatures();
      patchMapVisibility();
    }

    async function onSharedUnsubscribeGroup(group) {
      if (!group?.id) return;
      const trackIds = (group.track_ids || []).map((id) => String(id));
      if (trackIds.length === 0) return;
      if (!confirm('Remove all trackers in this group from your map? You can add the group again from Shared With Me.')) return;
      unsubscribingGroupId.value = group.id;
      try {
        for (const trackId of trackIds) {
          await api.delete(`/trackers/${trackId}/subscribe/`);
        }
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group removed from map');
        groups.value = groups.value.filter((g) => String(g.id) !== String(group.id));
        trackers.value = trackers.value.filter((t) => !trackIds.includes(String(t.id)));
        const s = new Set(hiddenTrackIds.value);
        trackIds.forEach((id) => s.delete(id));
        hiddenTrackIds.value = s;
        const gs = new Set(hiddenGroupIds.value);
        gs.delete(String(group.id));
        hiddenGroupIds.value = gs;
        if (selectedId.value && trackIds.includes(String(selectedId.value))) selectedId.value = null;
        if (activeGroupId.value && String(activeGroupId.value) === String(group.id)) activeGroupId.value = null;
        updateMapFeatures();
        patchMapVisibility();
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to remove group');
      } finally {
        unsubscribingGroupId.value = null;
      }
    }

    function stubForIncoming(track) {
      if (!track) return null;
      return {
        id: track.id,
        name: track.name ?? '',
        owner_email: track.owner_email ?? '',
      };
    }

    function moveTrackToIncoming(trackId) {
      const idStr = String(trackId);
      const track = trackers.value.find((t) => String(t.id) === idStr);
      const stub = stubForIncoming(track);
      if (stub) {
        incomingSharedTrackers.value = incomingSharedTrackers.value.filter((t) => String(t.id) !== idStr);
        incomingSharedTrackers.value = [...incomingSharedTrackers.value, stub];
      }
    }

    async function onLeaveShare(trackId) {
      if (!trackId) return;
      if (!confirm('Remove yourself from this share? The owner will no longer have you as a recipient, and you won\'t see this tracker in Incoming again.')) return;
      leavingShareId.value = trackId;
      try {
        await api.delete(`/trackers/${trackId}/share-with-me/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Removed from share');
        const idStr = String(trackId);
        trackers.value = trackers.value.filter((t) => String(t.id) !== idStr);
        incomingSharedTrackers.value = incomingSharedTrackers.value.filter((t) => String(t.id) !== idStr);
        if (String(selectedId.value) === idStr) selectedId.value = null;
        const s = new Set(hiddenTrackIds.value);
        s.delete(trackId);
        hiddenTrackIds.value = s;
        updateMapFeatures();
        patchMapVisibility();
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to leave share');
      } finally {
        leavingShareId.value = null;
      }
    }

    async function onSharedUnsubscribe(trackId) {
      if (!trackId) return;
      if (!confirm('Remove this tracker from your list? You can add it again from Shared With Me.')) return;
      const track = trackers.value.find((t) => String(t.id) === String(trackId));
      const isPublic = (track?.visibility || '') === 'public';
      unsubscribingId.value = trackId;
      try {
        await api.delete(`/trackers/${trackId}/subscribe/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker removed');
        const idStr = String(trackId);
        if (!isPublic) moveTrackToIncoming(trackId);
        trackers.value = trackers.value.filter((t) => String(t.id) !== idStr);
        if (String(selectedId.value) === idStr) selectedId.value = null;
        const s = new Set(hiddenTrackIds.value);
        s.delete(trackId);
        hiddenTrackIds.value = s;
        updateMapFeatures();
        patchMapVisibility();
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to remove');
      } finally {
        unsubscribingId.value = null;
      }
    }

    function onDiscoverSaved() {
      showDiscoverModal.value = false;
      fetchTrackers();
    }

    function addOptimisticTracker(incoming) {
      const idStr = String(incoming.id);
      if (trackers.value.some((t) => String(t.id) === idStr)) return;
      const stub = normalizeTrackForMemory({
        id: incoming.id,
        name: incoming.name ?? '',
        owner_email: incoming.owner_email ?? '',
        is_owner: false,
        visibility: 'shared',
        geometry: { type: 'LineString', coordinates: [] },
      });
      trackers.value = [...trackers.value, stub];
    }

    async function fetchAndMergeTracker(trackerId) {
      try {
        const [metaRes, geomRes] = await Promise.all([
          api.get(`/trackers/${trackerId}/`),
          api.get(`/trackers/${trackerId}/geometry/`),
        ]);
        const t = metaRes.data;
        const normalized = normalizeTrackForMemory({
          ...geomRes.data,
          is_owner: t.is_owner,
          owner_email: t.owner_email,
          visibility: t.visibility,
        });
        const idStr = String(trackerId);
        const idx = trackers.value.findIndex((tr) => String(tr.id) === idStr);
        if (idx >= 0) {
          trackers.value = trackers.value.slice(0, idx).concat(normalized).concat(trackers.value.slice(idx + 1));
        } else {
          trackers.value = [...trackers.value, normalized];
        }
        updateMapFeatures();
      } catch {
        // Keep optimistic stub; map may have no geometry for this track
      }
    }

    async function onAddIncomingTracker(tracker) {
      if (!tracker?.id || addingIncomingId.value != null) return;
      const preservedListTab = listTab.value;
      addingIncomingId.value = tracker.id;
      try {
        await api.post(`/trackers/${tracker.id}/subscribe/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker added');
        incomingSharedTrackers.value = incomingSharedTrackers.value.filter((t) => String(t.id) !== String(tracker.id));
        addOptimisticTracker(tracker);
        updateMapFeatures();
        fetchAndMergeTracker(tracker.id);
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to add tracker');
      } finally {
        addingIncomingId.value = null;
        listTab.value = preservedListTab;
      }
    }

    async function onAddIncomingGroup(group) {
      if (!group?.id || addingIncomingGroupId.value != null) return;
      const preservedListTab = listTab.value;
      addingIncomingGroupId.value = group.id;
      let success = true;
      try {
        for (const trackId of group.track_ids || []) {
          try {
            await api.post(`/trackers/${trackId}/subscribe/`);
          } catch (e) {
            const err = api.handleError?.(e);
            if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to add group');
            success = false;
            break;
          }
        }
        if (success && window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group added');
      } finally {
        addingIncomingGroupId.value = null;
        await fetchTrackers();
        await fetchIncomingShared();
        updateMapFeatures();
        listTab.value = preservedListTab;
      }
    }

    watch(selectedId, () => {
      updateMapFeatures();
      if (selectedId.value && !followLocked.value) {
        fitMapToSelectedTrack();
      }
      // When unselecting we only unlock; do not reset zoom (no fitMapToTracks)
    });

    watch(
      () => hiddenTrackIds.value,
      () => {
        updateMapFeatures();
      },
      { deep: false }
    );

    function applyDefaultSortFromStore() {
      const store = window.gv_core?.store;
      const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
      if (!store || !getNestedValue) return;
      const saved = getNestedValue(store.state?.userSettings, DEFAULT_SORT_KEY);
      if (saved && VALID_SORT_VALUES.has(saved)) sortBy.value = saved;
    }

    function applyDefaultMapFromStore(tileSourcesRef, selectedLayerRef) {
      const store = window.gv_core?.store;
      const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
      if (!store || !getNestedValue || !tileSourcesRef?.value?.length) return;
      const defaultMap = getNestedValue(store.state?.userSettings, DEFAULT_MAP_KEY);
      if (defaultMap && tileSourcesRef.value.some((s) => s.id === defaultMap)) {
        selectedLayerRef.value = defaultMap;
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
      fetchIncomingShared();
      await fetchGroups();
      fetchTrackers().finally(() => {
        fetchMapVisibility().then(() => {
          requestAnimationFrame(() => initMap());
        });
      });

      function scheduleCenterOnSelectedTrack() {
        if (centerDebounceId) clearTimeout(centerDebounceId);
        centerDebounceId = setTimeout(() => {
          centerDebounceId = null;
          if (followLocked.value && selectedId.value && map) centerOnSelectedTrackLastPoint();
        }, CENTER_DEBOUNCE_MS);
      }

      trackUpdatedHandler = (data) => {
        if (!data || !data.track_id) return;
        const updates = Array.isArray(data.updates) ? data.updates : (data.point != null ? [{ point: data.point, props: data.props, index: data.index }] : null);
        if (!updates?.length) return;
        const idx = trackers.value.findIndex((t) => t.id === data.track_id);
        if (idx < 0) return;
        let track = trackers.value[idx];
        let geom = track.geometry ? { ...track.geometry, coordinates: [...(track.geometry.coordinates || [])] } : { type: 'LineString', coordinates: [] };
        if (!geom.coordinates) geom.coordinates = [];
        let latestPointParams = {};
        for (const u of updates) {
          const point = u?.point;
          if (!Array.isArray(point)) continue;
          const indexOutOfBounds =
            typeof u.index === 'number' &&
            Number.isInteger(u.index) &&
            (u.index < 0 || u.index > geom.coordinates.length);
          if (indexOutOfBounds) {
            api.get(`/trackers/${data.track_id}/geometry/`).then((geomRes) => {
              const trackIdx = trackers.value.findIndex((t) => t.id === data.track_id);
              if (trackIdx < 0) return;
              const existing = trackers.value[trackIdx];
              const normalized = normalizeTrackForMemory({
                ...geomRes.data,
                is_owner: existing.is_owner,
                owner_email: existing.owner_email,
                visibility: existing.visibility
              });
              trackers.value = trackers.value.slice(0, trackIdx).concat(normalized).concat(trackers.value.slice(trackIdx + 1));
              updateMapFeatures();
              if (data.track_id === selectedId.value && followLocked.value && map) {
                scheduleCenterOnSelectedTrack();
              }
            }).catch(() => {});
            return;
          }
          if (typeof u.index === 'number' && Number.isInteger(u.index)) {
            geom.coordinates.splice(u.index, 0, point);
          } else {
            geom.coordinates.push(point);
          }
          if (u.props && typeof u.props === 'object') latestPointParams = u.props;
        }
        const last = geom.coordinates[geom.coordinates.length - 1];
        const newPoint = updates[updates.length - 1]?.point;
        const last_position = newPoint && newPoint.length >= 2 ? { lon: newPoint[0], lat: newPoint[1] } : (last && last.length >= 2 ? { lon: last[0], lat: last[1] } : null);
        const last_timestamp_ms = newPoint && newPoint.length >= 3 ? newPoint[2] : (last && last.length >= 3 ? last[2] : null);
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
      if (mq.matches) mobileDrawerHeightPx.value = mobileDrawerSnapPx.value[0];
      mobileQueryListener = (e) => { 
        isMobileView.value = e.matches; 
        isSheetOpen.value = e.matches;
        if (e.matches && mobileDrawerHeightPx.value === 0) mobileDrawerHeightPx.value = mobileDrawerSnapPx.value[0];
      };
      mq.addEventListener('change', mobileQueryListener);

    });

    onActivated(() => {
      applyDefaultSortFromStore();
      applyDefaultMapFromStore(tileSources, selectedLayer);
      if (map && tileSources.value.some((s) => s.id === selectedLayer.value)) {
        switchMapLayer(selectedLayer.value);
      }
    });

    watch(
      () => window.gv_core?.store?.state?.userSettings,
      (userSettings) => {
        if (!userSettings) return;
        applyDefaultSortFromStore();
        applyDefaultMapFromStore(tileSources, selectedLayer);
        if (map && tileSources.value.length && tileSources.value.some((s) => s.id === selectedLayer.value)) {
          switchMapLayer(selectedLayer.value);
        }
      },
      { deep: true, immediate: true }
    );

    onBeforeUnmount(() => {
      if (drawerRafId != null) cancelAnimationFrame(drawerRafId);
      if (drawerMouseMoveListener) {
        document.removeEventListener('mousemove', drawerMouseMoveListener);
        document.removeEventListener('mouseup', drawerMouseUpListener);
      }
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
      api,
      trackers,
      groups,
      sortBy,
      sortedTrackers,
      sortedGroups,
      listTab,
      LIST_TABS,
      visibleTrackersTab,
      visibleSharedTab,
      visibleGroupsTab,
      visibleSharedGroupsTab,
      activeGroup,
      selectedItemLabel,
      selectedTrackSharedGroup,
      listEmptyForTab,
      loading,
      selectedId,
      activeGroupId,
      highlightedId,
      showTrackSidebar,
      SIDEBAR_ACTION_BUTTON_CLASS,
      SIDEBAR_ACTION_ICON_CLASS,
      showDiscoverModal,
      showSharedListModal,
      sharedByYouTrackers,
      shareSettingsModalTrack,
      publicSharePopupTrack,
      onShareSettingsSaved,
      onPublicShareDeleted,
      hiddenTrackIds,
      hiddenGroupIds,
      unsubscribingId,
      unsubscribingGroupId,
      paramsModalTrackId,
      paramsModalTrack,
      paramLabels,
      trackSidebarMode,
      trackSidebarTrack,
      trackSidebarLoading,
      mapContainer,
      mapColumnRef,
      mapSidebarRef,
      mapSidebarTitle,
      sidebarCloseEmitsOverlayFirst,
      closeMapSidebar,
      openSidebar,
      showGroupsSidebar,
      groupsSidebarInitialGroupId,
      groupsSidebarRefreshing,
      showGroupQuickViewSidebar,
      groupQuickViewGroup,
      groupQuickViewTracks,
      openGroupQuickView,
      onGroupQuickViewFitMap,
      onParamsClose,
      openParamsFromGroupQuickView,
      zoomToTrackInGroup,
      openTrackerInList,
      showSharedWithMeSidebar,
      sharedWithMeRefreshing,
      onSharedWithMeRefresh,
      onSharedWithMeLeaveGroup,
      onSharedSidebarSelectTrack,
      onSharedSidebarSelectGroup,
      incomingSharedTrackers,
      incomingSharedGroups,
      incomingPublicGroups,
      addingIncomingId,
      addingIncomingGroupId,
      leavingShareId,
      onAddIncomingTracker,
      onAddIncomingGroup,
      onLeaveShare,
      showLayerSidebar,
      showSettingsSidebar,
      hiddenTrackersForSettings,
      hiddenGroupsForSettings,
      onUnhideTracker,
      onUnhideAllTrackers,
      onUnhideTrackerFromMap,
      onUnhideGroup,
      onUnhideAllGroups,
      onUnhideGroupFromMap,
      actionStripRefreshing,
      onFullRefresh,
      isMapSidebarOpen,
      userLogin,
      tileSources,
      selectedLayer,
      isMobileView,
      isSheetOpen,
      mobileDrawerEl,
      mobileDrawerHeightPx,
      mobileDrawerSnapPx,
      isDrawerAtPeek,
      isDrawerDragging,
      onDrawerDragStart,
      onDrawerDragMove,
      onDrawerDragEnd,
      formatTime,
      goHome,
      onLayerChange,
      onLayerSidebarChange,
      onTrackListClick,
      openCreateTrackSidebar,
      openEditTrackSidebar,
      openCreateGroupModal,
      openEditGroupModal,
      onGroupsSidebarSaved,
      onGroupsSidebarRefreshed,
      onGroupHiddenInListChanged,
      onGroupsSidebarLeave,
      onDiscoverSaved,
      toggleTrackVisibility,
      toggleGroupVisibility,
      onSharedUnsubscribe,
      onSharedUnsubscribeGroup,
      onGroupListClick,
      deselectGroup,
      deselectSelection,
      fitMapToGroupTracks,
      leaveGroup,
      onTrackSidebarSaved,
      onTrackSettingsChanged,
      onTrackSidebarUnsubscribed,
      onTrackDeleted,
      isRecentlyUpdated,
      rootContainer,
      trackerMaxHeight,
      trackerSheetSnapPoints
    };
  }
};
</script>

<style scoped>
/* No focus ring on map area (e.g. when ESC moves focus from sidebar) */
.live-track-map-column:focus,
.live-track-map-column:focus-visible,
.live-track-map-column:focus-within {
  outline: none !important;
  box-shadow: none !important;
}
:deep(.live-track-map-column *:focus),
:deep(.live-track-map-column *:focus-visible) {
  outline: none !important;
  box-shadow: none !important;
}

.mobile-tracker-drawer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 60;
  background: #fff;
  border: 1px solid #3b82f6;
  border-bottom: none;
  border-radius: 16px 16px 0 0;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  max-height: v-bind("trackerMaxHeight + 'px'");
  transition: none;
}
.mobile-tracker-drawer--dragging {
  transition: none;
  will-change: height;
}

.mobile-drawer-handle {
  flex-shrink: 0;
  padding: 14px 16px 10px;
  cursor: grab;
  touch-action: none;
  display: flex;
  justify-content: center;
}
.mobile-drawer-handle:active {
  cursor: grabbing;
}

.mobile-drawer-handle-bar {
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: rgba(0, 0, 0, 0.28);
}

.mobile-drawer-drag-overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  cursor: grab;
}

.mobile-drawer-drag-overlay:active {
  cursor: grabbing;
}

.mobile-drawer-content--no-scroll {
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

/* Ensure smooth transition for the bottom sheet */
aside {
  will-change: height;
}

/* Hide navigation control on mobile to avoid overlap with sheet */
@media (max-width: 639px) {
  :deep(.maplibregl-ctrl-top-right) {
    top: 10px !important;
  }
}

.mobile-header-z {
  z-index: 50 !important;
}

/* Ensure handle is visible and root is not blocking */
:deep([data-vsbs-backdrop]) {
  background: transparent !important;
  pointer-events: none !important;
}

:deep([data-vsbs-sheet]),
:deep([data-vsbs-sheet]:focus),
:deep([data-vsbs-sheet]:focus-visible),
:deep([data-vsbs-sheet]:focus-within) {
  z-index: 40 !important;
  border: 1px solid #3b82f6 !important;
  border-bottom: none !important;
  outline: none !important;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.1) !important;
  --vsbs-outer-border-color: #3b82f6 !important;
  --vsbs-border-color: transparent !important;
  --vsbs-shadow-color: transparent !important;
  max-height: v-bind("trackerMaxHeight + 'px'") !important;
  overflow: hidden !important;
}

:deep(*) {
  -webkit-tap-highlight-color: transparent !important;
}

:deep([data-vsbs-shadow="true"]::before) {
  box-shadow: none !important;
}

/* Fix for handle being draggable and not maximized */
:deep([data-vsbs-header]) {
  cursor: grab !important;
  touch-action: none !important;
  border-bottom: none !important;
  border-top: none !important;
  box-shadow: none !important;
  outline: none !important;
}

:deep([data-vsbs-scroll]), :deep([data-vsbs-content-wrapper]), :deep([data-vsbs-content]) {
  outline: none !important;
}


:deep([data-vsbs-sheet] *) {
  outline: none !important;
  box-shadow: none !important;
}
</style>

<template>
  <div ref="rootContainer" class="flex-1 min-h-0 flex flex-col bg-gray-50 overflow-hidden">
    <!-- Header bar: full width; title/sort/+ constrained to list width on desktop -->
    <header class="z-40 h-16 flex flex-shrink-0 bg-white border-b border-gray-200 sm:flex-row">
      <div class="w-full sm:w-1/4 flex-shrink-0 px-4 py-2 flex items-center justify-between gap-3 sm:border-r sm:border-gray-200">
        <h2 class="text-xl font-bold text-gray-900 truncate min-w-0 tracking-tight">Trackers</h2>
        <div class="flex items-center gap-2 flex-shrink-0">
          <select
            v-model="sortBy"
            class="select-custom text-sm px-3 py-2 border border-gray-300 rounded-md focus:outline-none"
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
          :highlight-stale-data="highlightStaleData"
          :list-tabs="LIST_TABS"
          :visible-trackers-tab="visibleTrackersTab"
          :visible-shared-tab="visibleSharedTab"
          :visible-groups-tab="visibleGroupsTab"
          :visible-shared-groups-tab="visibleSharedGroupsTab"
          :selected-id="selectedId"
          :active-group-id="activeGroupId"
          :highlighted-id="highlightedId"
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
          @clear-highlight="highlightedId = null"
        />
      </aside>

    <!-- Map: 75% on desktop, full width on mobile -->
    <main ref="mapColumnRef" class="live-track-map-column flex-1 relative min-h-0">
      <div ref="mapContainer" class="absolute inset-0 w-full h-full bg-gray-100" />

      <div
          v-if="mapInitializing"
          class="absolute inset-0 z-20 flex flex-col items-center justify-center bg-gray-500/40 pointer-events-auto cursor-wait"
          aria-busy="true"
          aria-live="polite"
      >
        <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
          <Loader size="sm" layout="inline" :show-message="true" message="Loading map..."/>
        </div>
      </div>

      <!-- Mobile: map actions behind a hamburger control (desktop uses right strip below) -->
      <div
        v-if="isMobileView && !isMapSidebarOpen"
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
            @click="closeMobileActionsMenu(); openCreateTrackSidebar()"
          >
            <PlusIcon class="h-5 w-5 flex-shrink-0 text-blue-600" />
            <span>New Tracker</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
            @click="closeMobileActionsMenu(); openSidebar('groups')"
          >
            <UserGroupIcon class="h-5 w-5 flex-shrink-0 text-blue-600" />
            <span>Groups</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
            @click="closeMobileActionsMenu(); openSidebar('sharedWithMe')"
          >
            <span class="relative inline-flex h-5 w-5 flex-shrink-0 items-center justify-center text-blue-600">
              <ShareIcon class="h-5 w-5" />
              <span
                v-if="incomingSharedTrackers.length + incomingSharedGroups.length > 0"
                class="absolute -right-1.5 -top-1 min-w-[0.875rem] h-4 px-0.5 flex items-center justify-center rounded-full bg-blue-500 text-white text-[9px] font-semibold leading-none"
              >
                {{ incomingSharedTrackers.length + incomingSharedGroups.length > 99 ? '99+' : incomingSharedTrackers.length + incomingSharedGroups.length }}
              </span>
            </span>
            <span>Shared With Me</span>
          </button>
          <div class="my-1 border-t border-gray-100" role="presentation" />
          <button
            type="button"
            role="menuitem"
            class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
            @click="closeMobileActionsMenu(); openSidebar('settings')"
          >
            <Cog6ToothIcon class="h-5 w-5 flex-shrink-0 text-blue-600" />
            <span>Settings</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
            @click="closeMobileActionsMenu(); toggleLocationTracking()"
          >
            <LocationIcon
              size="h-5 w-5"
              class="flex-shrink-0"
              :show-center-dot="trackingEnabled"
              :class="trackingEnabled ? 'text-blue-600' : 'text-gray-700'"
            />
            <span>{{ trackingEnabled ? 'Stop My Location' : 'Show My Location' }}</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 disabled:opacity-50 disabled:hover:bg-white active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
            :disabled="actionStripRefreshing"
            @click="closeMobileActionsMenu(); onFullRefresh()"
          >
            <ArrowPathIcon :class="['h-5 w-5 flex-shrink-0 text-blue-600', actionStripRefreshing ? 'animate-spin' : '']" />
            <span>Refresh All</span>
          </button>
          <button
            type="button"
            role="menuitem"
            class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-gray-900 hover:bg-gray-50 active:bg-gray-100 focus:outline-none focus-visible:bg-gray-50"
            @click="closeMobileActionsMenu(); openSidebar('layer')"
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

      <!-- Selected item chip: group or tracker name, deselect with X; group icon when locked to a track in a shared group -->
      <div
        v-if="selectedItemLabel"
        :class="[
          'absolute top-3 z-20 flex max-w-[calc(100%-1.5rem)] items-center gap-2 rounded-lg border border-blue-200 bg-white px-3 py-2 sm:max-w-none',
          isMobileView ? 'left-1/2 -translate-x-1/2' : 'left-3'
        ]"
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
        class="fixed inset-x-0 bottom-0 top-16 sm:absolute sm:inset-0 overflow-hidden flex justify-end z-50 pointer-events-none"
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
            :groups="visibleGroupsTab"
            :trackers="trackers"
            :api="api"
            :initial-group-id="groupsSidebarInitialGroupId"
            :refreshing="groupsSidebarRefreshing"
            @saved="onGroupsSidebarSaved"
            @refreshed="onGroupsSidebarRefreshed"
            @leave="onGroupsSidebarLeave"
            @group-hidden-changed="onGroupHiddenChanged"
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
                Fit Map to Group
              </BaseButton>
            </div>
            <div class="flex-1 min-h-0 overflow-y-auto mt-3 space-y-2">
              <p class="text-sm font-medium text-gray-700">Trackers in Group</p>
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
                  <div
                    :class="[
                      'text-xs',
                      highlightStaleData && isActiveButDeadTrack(track) ? 'text-red-600' : 'text-gray-500'
                    ]"
                  >{{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'No points' }}</div>
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
              <p v-if="groupQuickViewTracks.length === 0" class="text-sm text-gray-500 py-2">No Trackers in This Group</p>
            </div>
          </div>
          <SharedWithMeSidebarContent
            v-else-if="showSharedWithMeSidebar"
            :trackers="trackers"
            :incoming-trackers="incomingSharedTrackers"
            :incoming-groups="incomingSharedGroups"
            :shared-groups-on-map="visibleSharedGroupsTab"
            :adding-incoming-id="addingIncomingId"
            :adding-incoming-group-id="addingIncomingGroupId"
            :leaving-share-id="leavingShareId"
            :unsubscribing-id="unsubscribingId"
            :unsubscribing-group-id="unsubscribingGroupId"
            :refreshing="sharedWithMeRefreshing"
            :api="api"
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
            v-model:highlight-stale-data="highlightStaleData"
            :hidden-trackers="hiddenTrackersForSettings"
            :hidden-groups="hiddenGroupsForSettings"
            :is-unhide-all-trackers-loading="isUnhideAllTrackersLoading"
            :is-unhide-all-groups-loading="isUnhideAllGroupsLoading"
            @unhide-tracker="onUnhideTracker"
            @unhide-all-trackers="onUnhideAllTrackers"
            @unhide-group="onUnhideGroup"
            @unhide-all-groups="onUnhideAllGroups"
          />
        </MapSidebarPanel>
      </div>
    </main>

    <!-- Action strip: right strip on desktop only (mobile uses … menu on map) -->
    <aside
      v-if="!isMobileView"
      class="flex flex-shrink-0 flex-col w-12 min-h-0 border-l border-gray-200 bg-white items-center justify-between py-2 gap-1 order-last"
      aria-label="Actions"
    >
      <div class="flex flex-col items-center gap-1">
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
              v-if="incomingSharedTrackers.length + incomingSharedGroups.length > 0"
              class="absolute -top-1 -right-1 min-w-[0.875rem] h-4 px-0.5 flex items-center justify-center rounded-full bg-blue-500 text-white text-[9px] font-semibold leading-none"
            >
              {{ incomingSharedTrackers.length + incomingSharedGroups.length > 99 ? '99+' : incomingSharedTrackers.length + incomingSharedGroups.length }}
            </span>
          </span>
        </button>
      </div>
      <div class="flex flex-col items-center gap-1">
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
          :title="trackingEnabled ? 'Stop Location Tracking' : 'Show My Location'"
          :class="SIDEBAR_ACTION_BUTTON_CLASS"
          @click="toggleLocationTracking"
        >
          <LocationIcon
            size="h-5 w-5 sm:h-6 sm:w-6"
            :show-center-dot="trackingEnabled"
            :class="trackingEnabled ? 'text-blue-600' : 'text-gray-700'"
          />
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

    <!-- Tracker List: Mobile – shared drawer component -->
    <Teleport v-if="isMobileView" to="#app">
      <MobileMapDrawer
        v-if="isMobileView && isSheetOpen"
        ref="mobileDrawerRef"
        :max-height="trackerMaxHeight"
        :initial-snap-index="0"
        :hidden="isMapSidebarOpen"
      >
        <template #default="{ atPeek }">
          <div class="flex-1 min-h-0 overflow-hidden flex flex-col px-2 pb-2 relative">
            <TrackerListContent
              ref="listContentMobileRef"
              v-model:list-tab="listTab"
              :highlight-stale-data="highlightStaleData"
              :list-tabs="LIST_TABS"
              :visible-trackers-tab="visibleTrackersTab"
              :visible-shared-tab="visibleSharedTab"
              :visible-groups-tab="visibleGroupsTab"
              :visible-shared-groups-tab="visibleSharedGroupsTab"
              :selected-id="selectedId"
              :active-group-id="activeGroupId"
              :highlighted-id="highlightedId"
              :loading="loading"
              :list-empty-for-tab="listEmptyForTab"
              :scroll-container-class="['flex-1 min-h-0 space-y-3 px-1 py-1', atPeek ? 'mobile-drawer-content--no-scroll' : 'overflow-y-auto custom-scrollbar'].join(' ')"
              action-opacity-class="opacity-60"
              @group-click="onGroupListClick"
              @track-click="onTrackListClick"
              @edit-track="openEditTrackSidebar"
              @open-params="(id) => openSidebar('params', id)"
              @leave-group="leaveGroup"
              @edit-group="openEditGroupModal"
              @view-group="openGroupQuickView"
              @clear-highlight="highlightedId = null"
            />
          </div>
        </template>
      </MobileMapDrawer>
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
import { PlusIcon, PencilIcon, HomeIcon, Square3Stack3DIcon, TableCellsIcon, XMarkIcon, Bars3Icon, UserGroupIcon, ShareIcon, CloudIcon, EyeIcon, ArrowPathIcon, Cog6ToothIcon, ListBulletIcon } from '@heroicons/vue/24/outline';
import { getIngressBodyTemplate } from './ingressBodyTemplateCache.js';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import LocationIcon from 'platform/components/parts/LocationIcon.vue';
import Loader from 'platform/components/parts/Loader.vue';
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
import MobileMapDrawer from './MobileMapDrawer.vue';
import { shouldReloadGeometryForSettingsChange } from './settingsChangePolicy.js';
import { useTileSources } from './useTileSources.js';
import { useMobileView } from './useMobileView.js';
import { useLiveTrackMap, MAP_SNAP_DURATION } from './useLiveTrackMap.js';
import { useLiveTrackGeolocation } from './useLiveTrackGeolocation.js';
import { useLiveTrackSocket } from './useLiveTrackSocket.js';
import { normalizeTrackForMemory } from './trackNormalization.js';
import { formatTimestampLocal } from './paramFormatters.js';
import {
  buildGroupUnhidePayload,
  buildHiddenItemsClearPayload,
  buildTrackerUnhidePayload,
} from './settingsPayloadBuilders.js';
import {
  computeVisibleSharedGroups,
  computeVisibleSharedTrackers,
  countOverlappingIncomingShares,
  isAcceptedOrOwnedGroup,
  isHiddenOwnedGroup,
  isHiddenOwnedTracker,
  isSharedGroupNotOwned,
  isSharedOrPublicOwned,
  isVisibleOwnedGroup,
  isVisibleOwnedTracker
} from './sharingSelectors.js';
import { isActiveButDeadTrack } from './activeButDeadTrack.js';

/** Shared button class for all right-sidebar action icons. Ring only on focus-visible so tap on mobile doesn't show thick border; no tap highlight. */
const SIDEBAR_ACTION_BUTTON_CLASS =
  'p-1.5 sm:p-2 rounded-lg text-blue-600 hover:bg-blue-50 active:bg-blue-100 focus:outline-none focus:ring-0 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white [-webkit-tap-highlight-color:transparent]';
const SIDEBAR_ACTION_ICON_CLASS = 'h-5 w-5 sm:h-6 sm:w-6';
const DEFAULT_MAP_KEY = 'extensions.live_track.default_map';
const DEFAULT_SORT_KEY = 'extensions.live_track.default_sort';
const VALID_SORT_VALUES = new Set(['alphabetical', 'last_updated', 'num_points', 'newest']);
const MAP_EDGE_PADDING_PX = 80;
const LIST_TABS = [
  { id: 'trackers', label: 'Trackers' },
  { id: 'groups', label: 'Groups' },
  { id: 'shared', label: 'Shared' }
];

export default {
  name: 'LiveTrackView',
  components: { BaseButton, LocationIcon, Loader, TrackSidebar, TrackDirectionIcon, LatestParamsModal, GroupsSidebarContent, DiscoverTrackersModal, SharedItemsModal, ShareSettingsModal, PublicSharePopup, MapLayerSidebar, MapSidebarPanel, SharedWithMeSidebarContent, LiveTrackSettingsSidebarContent, TrackerListContent, MobileMapDrawer, PlusIcon, PencilIcon, HomeIcon, Square3Stack3DIcon, TableCellsIcon, XMarkIcon, Bars3Icon, UserGroupIcon, ShareIcon, CloudIcon, EyeIcon, ArrowPathIcon, Cog6ToothIcon, ListBulletIcon },
  setup() {
    const api = inject('extensionApi');
    /** @type {import('platform/extensions/platformState').PlatformStateBridge} */
    const platformState = inject('platformState');
    const trackers = ref([]);
    const groups = ref([]);
    const highlightStaleData = ref(false);
    const sortBy = ref('alphabetical');
    const showDiscoverModal = ref(false);
    const showSharedListModal = ref(false);
    const shareSettingsModalTrack = ref(null);
    const publicSharePopupTrack = ref(null);
    const unsubscribingId = ref(null);
    const unsubscribingGroupId = ref(null);
    const loading = ref(true);
    /** Cleared once the map's style/data first finish loading (via `trackMap.onStyleReady`), so a loading overlay can mask the gap between mount and the map settling into its correct basemap/camera. */
    const mapInitializing = ref(true);
    const selectedId = ref(null);
    const activeGroupId = ref(null);
    const followLocked = ref(false);
    const isAutoMoving = ref(false);
    const rootContainer = ref(null);

    const {
      isMobileView,
      isSheetOpen,
      trackerMaxHeight,
      mobileDrawerRef,
      mobileActionsMenuOpen,
      mobileActionsMenuRootRef,
      closeMobileActionsMenu,
      collapseDrawerToPeek,
      getDrawerPeekHeight
    } = useMobileView();

    function isRecentlyUpdated(track) {
      if (!track.last_timestamp_ms) return false;
      const fiveMinutesAgo = Date.now() - 5 * 60 * 1000;
      return track.last_timestamp_ms > fiveMinutesAgo;
    }

    const trackerIdsOnMap = computed(() => new Set(trackers.value.map((t) => String(t.id))));

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
    const visibleTrackersTab = computed(() => sortedTrackers.value.filter((t) => isVisibleOwnedTracker(t)));
    const visibleSharedTab = computed(() =>
      computeVisibleSharedTrackers(sortedTrackers.value, sortedGroups.value)
    );
    const visibleGroupsTab = computed(() => sortedGroups.value.filter((g) => isVisibleOwnedGroup(g)));
    const visibleSharedGroupsTab = computed(() => computeVisibleSharedGroups(groups.value));
    const hiddenTrackersForSettings = computed(() =>
      trackers.value
        .filter((t) => isHiddenOwnedTracker(t))
        .map((t) => ({ id: t.id, name: t.name, is_owner: t.is_owner }))
    );
    const hiddenGroupsForSettings = computed(() =>
      groups.value
        .filter((g) => isHiddenOwnedGroup(g))
        .map((g) => ({ id: g.id, name: g.name, is_owner: g.is_owner }))
    );
    const sharedByYouTrackers = computed(() => trackers.value.filter((t) => isSharedOrPublicOwned(t)));
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
        (g) => isSharedGroupNotOwned(g) && (g.track_ids || []).some((tid) => String(tid) === idStr)
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
      // Vue component refs can expose child refs either wrapped (.value) or already unwrapped.
      return c?.scrollContainerRef?.value ?? c?.scrollContainerRef ?? null;
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
    const addingIncomingId = ref(null);
    const addingIncomingGroupId = ref(null);
    const leavingShareId = ref(null);
    const sharedWithMeRefreshing = ref(false);
    const actionStripRefreshing = ref(false);
    const showLayerSidebar = ref(false);
    const showSettingsSidebar = ref(false);
    const isUnhideAllTrackersLoading = ref(false);
    const isUnhideAllGroupsLoading = ref(false);

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
        if (open) {
          closeMobileActionsMenu();
          nextTick(() => mapSidebarRef.value?.focus());
        }
      }
    );

    const highlightedId = ref(null);
    const userLogin = ref('');
    const { tileSources, selectedLayer, fetchTileSources } = useTileSources({
      afterFetch: (tileSourcesRef, selectedLayerRef) => applyDefaultMapFromStore(tileSourcesRef, selectedLayerRef)
    });
    const formatTime = (ms) => formatTimestampLocal(ms);

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
        let withGeometry = [];
        const ids = raw.map((t) => t.id).filter((id) => id != null && id !== '');

        const bulkRes = await api.post('/trackers/geometry/', {
          tracker_ids: ids
        });
        const bulkList = Array.isArray(bulkRes.data) ? bulkRes.data : [];
        const bulkById = new Map(bulkList.map((t) => [String(t.id), t]));
        withGeometry = raw.map((t) => {
          const merged = bulkById.get(String(t.id));
          if (!merged) return normalizeTrackForMemory({ ...t, geometry: { type: 'LineString', coordinates: [] } });
          return normalizeTrackForMemory({
            ...merged,
            // Preserve list-only fields (is_owner, owner_email, visibility)
            is_owner: t.is_owner,
            owner_email: t.owner_email,
            visibility: t.visibility,
            internal_share_id: t.internal_share_id,
            internal_share_url: t.internal_share_url,
            world_share_id: t.world_share_id,
            world_share_url: t.world_share_url
          });
        });

        trackers.value = withGeometry;
        trackSocket.refreshSessionCache(withGeometry);
        trackMap.updateMapFeatures();
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load trackers');
        }
      } finally {
        if (!skipGlobalLoading) loading.value = false;
      }
    }

    async function fetchIncomingShared() {
      try {
        const res = await api.get('/trackers/available-to-add/');
        const data = res.data || {};
        incomingSharedTrackers.value = Array.isArray(data.shared_with_me) ? data.shared_with_me : [];
        // Defensive: pending shared groups should not expose per-track items pre-acceptance.
        incomingSharedGroups.value = Array.isArray(data.shared_with_me_groups)
          ? data.shared_with_me_groups.map((g) => ({ ...g, track_ids: [] }))
          : [];
      } catch (e) {
        const err = api.handleError && api.handleError(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load incoming shares');
        }
        incomingSharedTrackers.value = [];
        incomingSharedGroups.value = [];
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
      removeGroupFromLocalState(group, { removeTrackers: true });
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

    function getMapPadding() {
      const bottomInset = isMobileView.value && isSheetOpen.value && !isMapSidebarOpen.value
        ? getDrawerPeekHeight()
        : 0;
      return {
        top: MAP_EDGE_PADDING_PX,
        left: MAP_EDGE_PADDING_PX,
        right: MAP_EDGE_PADDING_PX,
        bottom: MAP_EDGE_PADDING_PX + bottomInset
      };
    }

    /** A track feature on the map was clicked: switch to its tab, highlight it, and scroll it into view in the list. */
    function onMapFeatureClick(trackId) {
      const track = trackers.value.find((t) => String(t.id) === String(trackId));
      if (track) {
        if (visibleTrackersTab.value.some((t) => String(t.id) === String(trackId))) {
          listTab.value = 'trackers';
        } else if (visibleSharedTab.value.some((t) => String(t.id) === String(trackId))) {
          listTab.value = 'shared';
        }
      }
      highlightedId.value = trackId;
      if (isMobileView.value) {
        const snap = mobileDrawerRef.value?.snapPx?.value ?? mobileDrawerRef.value?.snapPx;
        const maxH = Array.isArray(snap) ? snap[1] : undefined;
        if (maxH != null) {
          const hp = mobileDrawerRef.value?.heightPx;
          if (hp && typeof hp === 'object' && 'value' in hp) hp.value = maxH;
        }
      }
      function scrollListToTrack() {
        const container = listScrollContainer.value;
        if (!container) {
          // Last-resort fallback if list container ref is not resolved yet.
          const rowOnly = document.querySelector(`[data-track-id="${trackId}"]`);
          rowOnly?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
          return;
        }
        const row = container.querySelector(`[data-track-id="${trackId}"]`);
        if (!row) return;
        const padding = 8;
        const rowTop = row.offsetTop;
        const rowHeight = row.offsetHeight;
        const containerHeight = container.clientHeight;
        const scrollTop = container.scrollTop;
        if (rowTop < scrollTop) {
          container.scrollTo({ top: Math.max(0, rowTop - padding), behavior: 'smooth' });
        } else if (rowTop + rowHeight > scrollTop + containerHeight) {
          container.scrollTo({
            top: rowTop + rowHeight - containerHeight + padding,
            behavior: 'smooth'
          });
        }
      }
      nextTick(() => {
        scrollListToTrack();
        nextTick(() => {
          if (!listScrollContainer.value?.querySelector(`[data-track-id="${trackId}"]`)) {
            setTimeout(scrollListToTrack, 80);
          }
        });
      });
    }

    function onMapBackgroundClick() {
      highlightedId.value = null;
    }

    const trackMap = useLiveTrackMap({
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
      onFeatureClick: onMapFeatureClick,
      onBackgroundClick: onMapBackgroundClick
    });

    const geo = useLiveTrackGeolocation({ getMap: trackMap.getMap });
    trackMap.onStyleReady(() => geo.syncUserLocationMarker());
    trackMap.onStyleReady(() => { mapInitializing.value = false; });
    const { trackingEnabled, toggleLocationTracking } = geo;

    function onLayerChange() {
      trackMap.switchMapLayer(selectedLayer.value);
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
      trackMap.updateMapFeatures();
      trackMap.panToTrackLastPoint(track, { minZoom: 14 });
      if (isMobileView.value) collapseDrawerToPeek();
    }

    async function goHome() {
      selectedId.value = null;
      followLocked.value = false;
      await trackMap.updateMapFeatures();
      if (trackers.value.length > 0) {
        trackMap.fitMapToTracks();
      } else {
        const map = trackMap.getMap();
        if (map) map.easeTo({ center: [0, 0], zoom: 2, duration: MAP_SNAP_DURATION, padding: getMapPadding() });
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
      const idStr = String(trackerId);
      try {
        const tracker = trackers.value.find((t) => String(t.id) === idStr);
        if (!tracker) return;
        const payload = buildTrackerUnhidePayload(tracker);
        const response = await api.post(`/trackers/${trackerId}/settings/`, payload);
        if (response?.data?.id) {
          upsertTrackerInLocalState(response.data, { updateMap: false });
        }
        const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
        if (idx >= 0) {
          const t = trackers.value[idx];
          const settings = { ...(t.settings || {}), hidden: false };
          trackers.value = trackers.value.slice(0, idx).concat({ ...t, settings }).concat(trackers.value.slice(idx + 1));
        }
        trackMap.updateMapFeatures();
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker shown on map and in list');
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to show tracker');
      }
    }

    async function onUnhideAllTrackers() {
      if (!api || isUnhideAllTrackersLoading.value) return;
      isUnhideAllTrackersLoading.value = true;
      try {
        const payload = buildHiddenItemsClearPayload(['trackers']);
        await api.post('/hidden-items/clear/', payload);
        await Promise.all([
          fetchTrackers({ skipGlobalLoading: true }),
          fetchGroups(),
        ]);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.success('All hidden trackers shown');
        }
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to show all trackers');
        }
      } finally {
        isUnhideAllTrackersLoading.value = false;
      }
    }

    async function onUnhideGroup(groupId) {
      if (!groupId || !api) return;
      const idStr = String(groupId);
      try {
        const group = groups.value.find((g) => String(g.id) === idStr);
        if (!group) return;
        const payload = buildGroupUnhidePayload(group);
        const response = await api.patch(`/groups/${groupId}/`, payload);
        if (response?.data?.id) {
          upsertGroupInLocalState(response.data, { updateMap: false });
        }
        const idx = groups.value.findIndex((g) => String(g.id) === idStr);
        if (idx >= 0) {
          const g = groups.value[idx];
          groups.value = groups.value.slice(0, idx).concat({ ...g, hidden: false }).concat(groups.value.slice(idx + 1));
        }
        trackMap.updateMapFeatures();
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group shown on map and in list');
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to show group');
      }
    }

    async function onUnhideAllGroups() {
      if (!api || isUnhideAllGroupsLoading.value) return;
      isUnhideAllGroupsLoading.value = true;
      try {
        const payload = buildHiddenItemsClearPayload(['groups']);
        await api.post('/hidden-items/clear/', payload);
        await Promise.all([
          fetchTrackers({ skipGlobalLoading: true }),
          fetchGroups(),
        ]);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.success('All hidden groups shown');
        }
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.error(err?.message || 'Failed to show all groups');
        }
      } finally {
        isUnhideAllGroupsLoading.value = false;
      }
    }

    function upsertTrackerInLocalState(tracker, options = {}) {
      if (!tracker?.id) return false;
      const idStr = String(tracker.id);
      const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
      const existing = idx >= 0 ? trackers.value[idx] : null;
      const normalized = normalizeTrackForMemory({
        ...(existing || {}),
        ...(tracker || {}),
        geometry: tracker?.geometry ?? existing?.geometry ?? { type: 'LineString', coordinates: [] },
      });
      if (idx >= 0) {
        trackers.value = trackers.value.slice(0, idx).concat(normalized).concat(trackers.value.slice(idx + 1));
      } else {
        trackers.value = [...trackers.value, normalized];
      }
      if (options.updateMap !== false) trackMap.updateMapFeatures();
      return true;
    }

    function removeTrackerFromLocalState(trackId, options = {}) {
      if (trackId == null) return;
      const idStr = String(trackId);
      if (options.moveToIncoming === true) moveTrackToIncoming(trackId);
      trackers.value = trackers.value.filter((t) => String(t.id) !== idStr);
      groups.value = groups.value.map((g) => ({
        ...g,
        track_ids: (g.track_ids || []).filter((id) => String(id) !== idStr),
      }));
      if (options.removeFromIncoming === true) {
        incomingSharedTrackers.value = incomingSharedTrackers.value.filter((t) => String(t.id) !== idStr);
      }
      if (String(selectedId.value) === idStr) selectedId.value = null;
      if (options.updateMap !== false) trackMap.updateMapFeatures();
    }

    function upsertGroupInLocalState(group, options = {}) {
      if (!group?.id) return false;
      const idStr = String(group.id);
      const idx = groups.value.findIndex((g) => String(g.id) === idStr);
      const existing = idx >= 0 ? groups.value[idx] : null;
      const merged = {
        ...(existing || {}),
        ...(group || {}),
        track_ids: Array.isArray(group.track_ids)
          ? [...group.track_ids]
          : [...(existing?.track_ids || [])],
      };
      if (idx >= 0) {
        groups.value = groups.value.slice(0, idx).concat(merged).concat(groups.value.slice(idx + 1));
      } else {
        groups.value = [...groups.value, merged];
      }
      if (options.removeIncoming !== false) {
        incomingSharedGroups.value = incomingSharedGroups.value.filter((g) => String(g.id) !== idStr);
      }
      if (options.updateMap !== false) trackMap.updateMapFeatures();
      return true;
    }

    function onShareSettingsSaved(updated) {
      if (updated?.id) upsertTrackerInLocalState(updated, { updateMap: false });
      shareSettingsModalTrack.value = null;
    }

    function onPublicShareDeleted(updated) {
      if (updated?.id) upsertTrackerInLocalState(updated, { updateMap: false });
      publicSharePopupTrack.value = null;
    }

    async function fetchAndMergeGroup(groupId) {
      if (!groupId) return;
      try {
        const res = await api.get(`/groups/${groupId}/`);
        upsertGroupInLocalState(res?.data || null, { updateMap: false });
      } catch {
        // Keep optimistic group payload when targeted fetch fails.
      }
    }

    function onGroupsSidebarSaved(payload) {
      const group = payload?.group;
      if (!group?.id) {
        fetchGroups();
        return;
      }
      upsertGroupInLocalState(group, { updateMap: false });
      const trackIds = Array.isArray(group.track_ids) ? group.track_ids : [];
      for (const trackId of trackIds) {
        const idStr = String(trackId);
        const hasTrack = trackers.value.some((t) => String(t.id) === idStr);
        if (!hasTrack) fetchAndMergeTracker(trackId);
      }
      if (trackIds.length === 0 && payload?.action === 'created') {
        fetchAndMergeGroup(group.id);
      }
      trackMap.updateMapFeatures();
    }

    async function onGroupsSidebarRefreshed() {
      groupsSidebarRefreshing.value = true;
      try {
        await fetchGroups();
      } finally {
        groupsSidebarRefreshing.value = false;
      }
    }

    function removeGroupFromLocalState(group, options = {}) {
      const groupId = group?.id;
      if (groupId == null) return;
      const existingGroup = groups.value.find((g) => String(g.id) === String(groupId));
      const sourceGroup = existingGroup || group;
      const trackIdsInGroup = new Set((sourceGroup?.track_ids || []).map((id) => String(id)));
      groups.value = groups.value.filter((g) => String(g.id) !== String(groupId));
      if (options.removeIncoming !== false) {
        incomingSharedGroups.value = incomingSharedGroups.value.filter((g) => String(g.id) !== String(groupId));
      }
      if (options.removeTrackers === true) {
        trackers.value = trackers.value.filter((t) => !trackIdsInGroup.has(String(t.id)));
      }
      if (String(activeGroupId.value) === String(groupId)) activeGroupId.value = null;
      if (groupQuickViewGroup.value && String(groupQuickViewGroup.value.id) === String(groupId)) {
        showGroupQuickViewSidebar.value = false;
        groupQuickViewGroup.value = null;
      }
      if (options.removeTrackers === true && selectedId.value != null && trackIdsInGroup.has(String(selectedId.value))) {
        selectedId.value = null;
      }
      trackMap.updateMapFeatures();
    }

    async function onGroupsSidebarLeave(group) {
      if (!group?.id) return;
      if (!confirm('Leave this shared group? You will no longer see its trackers on the map or in Shared.')) return;
      try {
        await api.delete(`/groups/${group.id}/leave/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Left shared group');
        removeGroupFromLocalState(group, { removeTrackers: true });
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to leave shared group');
      }
    }

    function onGroupListClick(group) {
      activeGroupId.value = group?.id ?? null;
      selectedId.value = null;
      trackMap.updateMapFeatures();
      trackMap.fitMapToGroupTracks(group);
      if (isMobileView.value) collapseDrawerToPeek();
    }

    function openGroupQuickView(group) {
      openSidebar('groupQuickView', group);
    }

    function onGroupQuickViewFitMap() {
      const g = groupQuickViewGroup.value;
      if (!g) return;
      activeGroupId.value = g.id ?? null;
      trackMap.updateMapFeatures();
      trackMap.fitMapToGroupTracks(g);
    }

    function zoomToTrackInGroup(track) {
      highlightedId.value = null;
      activeGroupId.value = null;
      selectedId.value = track.id;
      followLocked.value = true;
      trackMap.updateMapFeatures();
      trackMap.panToTrackLastPoint(track, { minZoom: 14 });
      if (isMobileView.value) collapseDrawerToPeek();
    }

    function openTrackerInList(track) {
      if (!track?.id) return;
      highlightedId.value = null;
      activeGroupId.value = null;
      listTab.value = 'trackers';
      selectedId.value = track.id;
      followLocked.value = true;
      trackMap.updateMapFeatures();
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
      trackMap.updateMapFeatures();
    }

    function deselectSelection() {
      activeGroupId.value = null;
      selectedId.value = null;
      trackMap.updateMapFeatures();
      if (isMobileView.value) collapseDrawerToPeek();
    }

    async function leaveGroup(group) {
      if (!group?.id) return;
      if (!confirm('Leave this shared group? You will no longer see its trackers on the map or in Shared.')) return;
      try {
        await api.delete(`/groups/${group.id}/leave/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Left shared group');
        removeGroupFromLocalState(group, { removeTrackers: true });
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to leave shared group');
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

    function onTrackSidebarSaved(payload) {
      const action = payload?.action;
      if (action === 'created') {
        if (payload?.tracker?.id) {
          upsertTrackerInLocalState(payload.tracker);
          return;
        }
        fetchTrackers();
      }
      showTrackSidebar.value = false;
      if (action === 'history-cleared' && payload?.trackId) {
        fetchAndMergeTracker(payload.trackId);
      }
    }

    function onTrackSettingsChanged(payload) {
      const { trackId, hidden, refresh_map } = payload || {};
      if (trackId == null) return;
      const idStr = String(trackId);
      const idx = trackers.value.findIndex((t) => String(t.id) === idStr);
      if (idx < 0) return;
      const t = trackers.value[idx];
      const hasHiddenUpdate = Object.prototype.hasOwnProperty.call(payload || {}, 'hidden');
      const settings = { ...(t.settings || {}) };
      if (hasHiddenUpdate) {
        settings.hidden = hidden;
      }
      trackers.value = trackers.value.slice(0, idx).concat([{ ...t, settings }]).concat(trackers.value.slice(idx + 1));
      // Avoid a transient redraw when we're only waiting for refreshed geometry.
      if (hasHiddenUpdate) {
        trackMap.updateMapFeatures();
      }
      const selectedSidebarTrackId = trackSidebarTrack.value?.id ?? null;
      if (shouldReloadGeometryForSettingsChange(refresh_map, trackId, selectedSidebarTrackId)) {
        fetchAndMergeTracker(trackId);
      }
    }

    function onTrackSidebarUnsubscribed(trackId) {
      showTrackSidebar.value = false;
      if (!trackId) return;
      removeTrackerFromLocalState(trackId, { moveToIncoming: true });
    }

    function onTrackDeleted(payload) {
      showTrackSidebar.value = false;
      const trackId = payload?.trackId ?? trackSidebarTrack.value?.id ?? null;
      if (trackId) {
        removeTrackerFromLocalState(trackId);
      } else {
        fetchTrackers();
      }
    }

    function onCreateGroupSaved(payload) {
      closeMapSidebar();
      const group = payload?.group;
      if (group?.id) {
        upsertGroupInLocalState(group, { updateMap: false });
      } else {
        fetchGroups();
      }
    }

    function onGroupHiddenChanged(payload) {
      const { groupId, hidden: value } = payload || {};
      if (groupId == null) return;
      const idStr = String(groupId);
      const idx = groups.value.findIndex((g) => String(g.id) === idStr);
      if (idx < 0) return;
      const g = groups.value[idx];
      groups.value = groups.value.slice(0, idx).concat([{ ...g, hidden: !!value }]).concat(groups.value.slice(idx + 1));
      trackMap.updateMapFeatures();
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
        removeGroupFromLocalState(group, { removeTrackers: true });
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
        removeTrackerFromLocalState(trackId, { removeFromIncoming: true });
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
        removeTrackerFromLocalState(trackId, { moveToIncoming: !isPublic });
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to remove');
      } finally {
        unsubscribingId.value = null;
      }
    }

    function onDiscoverSaved(payload) {
      const item = payload?.item;
      if (!item?.id || !payload?.action || !payload?.kind) {
        fetchTrackers();
        return;
      }
      if (payload.kind === 'tracker') {
        if (payload.action === 'added') {
          upsertTrackerInLocalState({
            ...item,
            is_owner: false,
            visibility: item.visibility || 'public',
            owner_email: item.owner_email || '',
          });
          fetchAndMergeTracker(item.id);
        } else if (payload.action === 'removed') {
          removeTrackerFromLocalState(item.id);
        }
        return;
      }
      if (payload.kind === 'group') {
        if (payload.action === 'added') {
          upsertGroupInLocalState({
            ...item,
            is_owner: false,
            is_accepted: true,
            visibility: item.visibility || 'public',
            track_ids: Array.isArray(item.track_ids) ? item.track_ids : [],
          }, { updateMap: false, removeIncoming: false });
          for (const trackId of item.track_ids || []) {
            if (!trackers.value.some((t) => String(t.id) === String(trackId))) {
              fetchAndMergeTracker(trackId);
            }
          }
          trackMap.updateMapFeatures();
        } else if (payload.action === 'removed') {
          removeGroupFromLocalState(item, { removeTrackers: true, removeIncoming: false });
        }
      }
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
        trackMap.updateMapFeatures();
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
        trackMap.updateMapFeatures();
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
      const isSharedIncoming = incomingSharedGroups.value.some((g) => String(g.id) === String(group.id));
      const subscribedTrackIds = [];
      let alsoAcceptedCount = 0;
      let success = true;
      try {
        if (isSharedIncoming) {
          const res = await api.post(`/groups/${group.id}/accept-share/`);
          const acceptedGroup = res?.data || {
            ...group,
            visibility: 'shared',
            is_owner: false,
            is_accepted: true,
          };
          upsertGroupInLocalState(acceptedGroup, { updateMap: false });
          alsoAcceptedCount = countOverlappingIncomingShares(
            incomingSharedTrackers.value,
            acceptedGroup.track_ids
          );
          for (const trackId of acceptedGroup.track_ids || []) {
            subscribedTrackIds.push(trackId);
          }
        } else {
          for (const trackId of group.track_ids || []) {
            try {
              await api.post(`/trackers/${trackId}/subscribe/`);
              subscribedTrackIds.push(trackId);
            } catch (e) {
              const err = api.handleError?.(e);
              if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to add group');
              success = false;
              break;
            }
          }
          if (success) {
            upsertGroupInLocalState({
              ...group,
              visibility: group.visibility || 'public',
              is_owner: false,
              is_accepted: true,
              track_ids: [...(group.track_ids || [])],
            }, { updateMap: false, removeIncoming: false });
          }
        }
        if (success && window.gv_core?.GeoVault?.toast) {
          window.gv_core.GeoVault.toast.success(isSharedIncoming ? 'Group accepted' : 'Group added');
          if (isSharedIncoming && alsoAcceptedCount > 0) {
            window.gv_core.GeoVault.toast.success(
              alsoAcceptedCount === 1
                ? '1 share was also accepted'
                : `${alsoAcceptedCount} shares were also accepted`
            );
          }
        }
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to add group');
      } finally {
        addingIncomingGroupId.value = null;
        if (success) {
          incomingSharedGroups.value = incomingSharedGroups.value.filter((g) => String(g.id) !== String(group.id));
          for (const trackId of subscribedTrackIds) {
            const incomingTracker = incomingSharedTrackers.value.find((t) => String(t.id) === String(trackId));
            if (incomingTracker) {
              addOptimisticTracker(incomingTracker);
              incomingSharedTrackers.value = incomingSharedTrackers.value.filter((t) => String(t.id) !== String(trackId));
            } else if (!trackers.value.some((t) => String(t.id) === String(trackId))) {
              addOptimisticTracker({ id: trackId, name: '', owner_email: '' });
            }
            fetchAndMergeTracker(trackId);
          }
          trackMap.updateMapFeatures();
        }
        listTab.value = preservedListTab;
      }
    }

    watch(selectedId, () => {
      trackMap.updateMapFeatures();
      if (selectedId.value && !followLocked.value) {
        trackMap.fitMapToSelectedTrack();
      }
      // When unselecting we only unlock; do not reset zoom (no fitMapToTracks)
    });

    function applyDefaultSortFromStore() {
      const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
      if (!getNestedValue) return;
      const saved = getNestedValue(platformState.userSettings.value, DEFAULT_SORT_KEY);
      if (saved && VALID_SORT_VALUES.has(saved)) sortBy.value = saved;
    }

    function applyDefaultMapFromStore(tileSourcesRef, selectedLayerRef) {
      const getNestedValue = window.gv_core?.GeoVault?.utils?.getNestedValue;
      if (!getNestedValue || !tileSourcesRef?.value?.length) return;
      const defaultMap = getNestedValue(platformState.userSettings.value, DEFAULT_MAP_KEY);
      if (defaultMap && tileSourcesRef.value.some((s) => s.id === defaultMap)) {
        selectedLayerRef.value = defaultMap;
      }
    }

    /**
     * Wait briefly for `App.vue`'s settings fetch (or fetch once ourselves) so `fetchTileSources()`'s
     * `applyDefaultMapFromStore` reads the real `extensions.live_track.default_map` on the very
     * first paint instead of racing it and being corrected later by the `platformState.userSettings`
     * watcher (visible as a style swap). Mirrors the Places extension's `ensureUserSettingsLoaded`.
     */
    async function ensureUserSettingsLoaded(waitMs = 3000, pollMs = 50) {
      if (platformState.userSettings.value != null) return;
      const deadline = Date.now() + waitMs;
      while (platformState.userSettings.value == null && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, pollMs));
      }
      if (platformState.userSettings.value != null) return;
      await platformState.fetchUserSettings();
    }

    watch(highlightStaleData, (v) => {
      try {
        if (typeof localStorage !== 'undefined') {
          localStorage.setItem('liveTrack.highlightStaleData', v ? '1' : '0');
        }
      } catch (_) { /* ignore */ }
    });

    const trackSocket = useLiveTrackSocket({
      api,
      trackers,
      selectedId,
      followLocked,
      updateMapFeatures: trackMap.updateMapFeatures,
      scheduleCenterOnSelectedTrack: trackMap.scheduleCenterOnSelectedTrack,
      fetchAndMergeTracker,
      onReconnect: () => {
        fetchTrackers().then(() => {
          if (followLocked.value && selectedId.value) trackMap.centerOnSelectedTrackLastPoint();
        });
      }
    });

    onMounted(async () => {
      try {
        if (typeof localStorage !== 'undefined' && localStorage.getItem('liveTrack.highlightStaleData') === '1') {
          highlightStaleData.value = true;
        }
      } catch (_) { /* ignore */ }
      const userInfo = platformState.currentUser.value;
      if (userInfo?.email) userLogin.value = userInfo.email;
      await ensureUserSettingsLoaded();
      applyDefaultSortFromStore();
      await fetchTileSources();
      const ingressData = await getIngressBodyTemplate(api);
      if (ingressData?.param_labels && typeof ingressData.param_labels === 'object') {
        paramLabels.value = ingressData.param_labels;
      }
      fetchIncomingShared();
      await fetchGroups();
      await fetchTrackers();
      requestAnimationFrame(() => { void trackMap.initMap(); });
      trackSocket.connect();
    });

    onActivated(() => {
      applyDefaultSortFromStore();
      applyDefaultMapFromStore(tileSources, selectedLayer);
      if (trackMap.getMap() && tileSources.value.some((s) => s.id === selectedLayer.value)) {
        trackMap.switchMapLayer(selectedLayer.value);
      }
    });

    watch(
      () => platformState.userSettings.value,
      (userSettings) => {
        if (!userSettings) return;
        applyDefaultSortFromStore();
        applyDefaultMapFromStore(tileSources, selectedLayer);
        if (trackMap.getMap() && tileSources.value.length && tileSources.value.some((s) => s.id === selectedLayer.value)) {
          trackMap.switchMapLayer(selectedLayer.value);
        }
      },
      { deep: true, immediate: true }
    );

    onBeforeUnmount(() => {
      trackSocket.disconnect();
      geo.stopLocationTracking();
      trackMap.destroyMap();
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
      mapInitializing,
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
      isUnhideAllTrackersLoading,
      isUnhideAllGroupsLoading,
      onUnhideTracker,
      onUnhideAllTrackers,
      onUnhideGroup,
      onUnhideAllGroups,
      actionStripRefreshing,
      trackingEnabled,
      toggleLocationTracking,
      onFullRefresh,
      isMapSidebarOpen,
      userLogin,
      tileSources,
      selectedLayer,
      isMobileView,
      isSheetOpen,
      mobileDrawerRef,
      mobileActionsMenuOpen,
      mobileActionsMenuRootRef,
      closeMobileActionsMenu,
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
      onGroupHiddenChanged,
      onGroupsSidebarLeave,
      onDiscoverSaved,
      onSharedUnsubscribe,
      onSharedUnsubscribeGroup,
      onGroupListClick,
      deselectGroup,
      deselectSelection,
      leaveGroup,
      onTrackSidebarSaved,
      onTrackSettingsChanged,
      onTrackSidebarUnsubscribed,
      onTrackDeleted,
      isRecentlyUpdated,
      highlightStaleData,
      isActiveButDeadTrack,
      rootContainer,
      trackerMaxHeight
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

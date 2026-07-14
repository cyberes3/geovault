<template>
  <div class="w-full h-full flex">
    <!-- Left Sidebar - Feature List -->
    <FeatureListSidebar
        :key="sidebarKey"
        :available-tags="availableTags"
        :class="['transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"
        :features="featuresInExtent"
        :initial-selected-tags="initialSelectedTags"
        :is-initial-load="isMapInitializing || (isDataLoading && isInitialLoad)"
        :is-mobile-open="activeMobileSidebar === 'features'"
        :can-hide-features="isMainMapRoute && !isPublicShareMode && !!store.getters['auth/userInfo']"
        :geocoding-available="!!(maptilerConfig && maptilerConfig.isAvailable() && store.getters['auth/userInfo'])"
        @close="activeMobileSidebar = null"
        @feature-click="handleFeatureListClick"
        @feature-hide="handleHideFeature"
        @feature-hover="handleFeatureListHover"
        @tag-filter-change="handleTagFilterChange"
        @tag-filter-loading-change="isDataLoading = $event"
        @tag-filter-start="handleTagFilterStart"
        @reverse_geocoding-result-click="handleReverseGeocodingResultClick"
        @reverse_geocoding-clear="clearGeocodingMarker"
    />

    <!-- Center - Map -->
    <div class="flex-1 w-full bg-gray-50 relative overflow-hidden flex flex-col min-h-0">
      <MobileControlsBar
        :is-public-share-mode="isPublicShareMode"
        :public-share-tag="publicShareTag ?? undefined"
        :public-share-collection-name="publicShareCollectionName ?? undefined"
        :collection-name="collectionName ?? undefined"
        @toggle-features="activeMobileSidebar = 'features'"
        @toggle-controls="activeMobileSidebar = 'controls'"
      />
      <div class="relative w-full flex-1 min-h-0">
        <!-- Map -->
        <div
            ref="mapContainer"
            :class="[
            'w-full h-full transition-opacity duration-300',
            (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100'
          ]"
        ></div>

        <!-- Map Initializing Overlay: shown while resolving basemap/camera before the map is constructed -->
        <div
            v-if="isMapInitializing || !map"
            class="absolute inset-0 z-20 flex flex-col items-center justify-center bg-gray-500/40 pointer-events-auto cursor-wait"
            aria-busy="true"
            aria-live="polite"
        >
          <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
            <Loader size="sm" layout="inline" :show-message="true" message="Loading map..."/>
          </div>
        </div>

        <!-- 3D Terrain Toggle Button (hidden on public mapshare) -->
        <div
            v-if="maptilerConfig && !isPublicShareMode"
            class="maplibregl-ctrl maplibregl-ctrl-group"
            style="position: absolute; top: 100px; left: 10px; z-index: 2;"
        >
          <button
              :class="[
                'maplibregl-ctrl-terrain',
                terrainEnabled ? 'maplibregl-ctrl-terrain-enabled' : ''
              ]"
              type="button"
              title="Toggle 3D Terrain"
              aria-label="Toggle 3D Terrain"
              @click="toggleTerrain"
          ></button>
          <div
              v-if="showTerrainTooltip"
              class="maplibregl-ctrl-terrain-tooltip maplibregl-ctrl-terrain-tooltip-visible"
          >
            {{ isMobile ? 'Use gestures to tilt and rotate.' : 'Use the right mouse button to tilt and rotate.' }}
          </div>
        </div>

        <!-- Error Overlay for Invalid Share -->
        <MapErrorOverlay
            :message="publicShareError ?? undefined"
            :visible="!!publicShareError"
            subtext="The share link may have been deleted or expired."
            title="Invalid Share Link"
        />

        <!-- Error Overlay for Loading Failures -->
        <MapErrorOverlay
            :message="loadError ?? undefined"
            :visible="!!loadError"
            subtext="Please try refreshing the page or check your connection."
            title="Error Loading Map"
        />

        <!-- Loading Indicator (hidden while the map-initializing overlay above is already showing) -->
        <MapLoadingIndicator
            v-if="!isMapInitializing && map"
            :is-loading="isDataLoading"
        />

        <!-- Feature Info Box or Edit Box -->
        <FeatureInfoBox
            v-if="!isEditingFeature && !isPublicShareMode && !showElevationProfile"
            :feature="selectedFeature ?? undefined"
            @close="selectedFeature = null"
            @download="handleDownloadFeatureKmz"
            @edit="handleEditFeature"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
            @share="handleShareFeature"
        />
        <FeatureInfoBox
            v-if="!isEditingFeature && isPublicShareMode && !showElevationProfile"
            :feature="selectedFeature ?? undefined"
            :share-id="shareId ?? undefined"
            :show-download-button="!!(publicShareInfo && publicShareInfo.allow_downloads)"
            :show-edit-button="false"
            :show-share-button="false"
            @close="selectedFeature = null"
            @download="handleDownloadFeatureKmz"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
        />
        <FeatureEditBox
            v-if="isEditingFeature && !isPublicShareMode"
            :available-tags="availableTags"
            :feature="selectedFeature"
            :can-hide-feature="isMainMapRoute && !!store.getters['auth/userInfo']"
            :initial-hidden="hiddenFeatureIds.includes(String(selectedFeature?.properties.database_id ?? ''))"
            @cancel="handleCancelEdit"
            @deleted="handleFeatureDeleted"
            @saved="handleFeatureSaved"
            @visibility-change="handleEditBoxVisibilityChange"
            @zoom="zoomToFeature(selectedFeature)"
        />

        <!-- Elevation Profile Dialog -->
        <ElevationProfileDialog
            v-if="showElevationProfile"
            :feature="selectedFeature"
            :share-id="isPublicShareMode && publicShareInfo && publicShareInfo.share_type === 'feature' ? shareId : null"
            :is-public-share="isPublicShareMode && publicShareInfo && publicShareInfo.share_type === 'feature'"
            @close="handleElevationProfileClose"
            @hover-point="handleHoverPoint"
            @hover-clear="handleHoverClear"
            @click-point="handleClickPoint"
        />

        <!-- Feature Share Dialog -->
        <ShareDialog
            :is-open="showFeatureShareDialog"
            share-type="feature"
            :item="featureToShare || {}"
            @close="handleCloseFeatureShareDialog"
        />

        <!-- Feature Selection Popup (for overlapping features) -->
        <FeatureSelectionPopup
            :features="overlappingFeatures"
            :position="popupPosition"
            :visible="showFeaturePopup"
            @close="showFeaturePopup = false"
            @select="handleFeatureSelect"
        />

        <!-- Quick Point Dialog -->
        <QuickPointDialog
            v-if="!isPublicShareMode"
            :is-open="showQuickPointDialog"
            :available-tags="availableTags"
            @close="showQuickPointDialog = false"
            @created="handleQuickPointCreated"
        />
</div>

      <!-- Location and Home Controls -->
      <LocationControl
          v-if="!isPublicShareMode"
          class="absolute z-10 bottom-4 left-4"
          :tracking-state="trackingState"
          @toggle-location="toggleLocationTracking"
          @go-home="centerToHomeExtent"
      />
    </div>

      <!-- Right Sidebar - Map Controls -->
      <MapControlsSidebar
        :allow-downloads="!!(publicShareInfo && publicShareInfo.allow_downloads)"
        :allowed-options="publicShareAllowedOptions"
        :class="['transition-opacity duration-300', (publicShareError || loadError) ? 'opacity-50 pointer-events-none' : 'opacity-100']"
        :feature-count="featureCount"
        :hidden-features="hiddenFeatureSummaries"
        :is-mobile-open="activeMobileSidebar === 'controls'"
        :is-public-share-mode="isPublicShareMode"
        :location-display-name="getLocationDisplayName()"
        :selected-layer="selectedLayer"
        :share-id="shareId ?? undefined"
        :tile-sources="tileSources"
        :user-location="userLocation ?? undefined"
        :view-context="viewContext ?? undefined"
        :can-manage-hidden="isMainMapRoute && !isPublicShareMode && !!store.getters['auth/userInfo']"
        :show-all-labels="showAllLabels"
        :hillshade-available="!!(maptilerConfig && maptilerConfig.isAvailable())"
        :hillshade-enabled="hillshadeEnabled"
        @close="activeMobileSidebar = null"
        @layer-change="switchMapLayer"
        @unhide-feature="handleUnhideFeature"
        @unhide-all="handleUnhideAllHidden"
        @labels-visibility-change="handleLabelsVisibilityChange"
        @hillshade-change="handleHillshadeChange"
        @quick-point="showQuickPointDialog = true"
    />
</div>
</template>

<script setup lang="ts">
/**
 * Composes the map page from seven focused composables (map instance lifecycle, layers/terrain,
 * bbox feature data, feature selection, public mapshare mode, collection/tag filters, and
 * geolocation). This component owns only: route-derived permission checks, the wrapper handlers
 * that thread one composable's reload/clear callbacks into another's event handlers, and the
 * `mounted`/`activated`/`deactivated`/`beforeUnmount` lifecycle orchestration (kept explicit here
 * rather than left to composables' own `onMounted`/`onUnmounted`, since this component is kept
 * alive via `<keep-alive>` and those hooks do not fire on `activated`/`deactivated`).
 */
import { computed, defineAsyncComponent, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch, type Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useStore } from 'vuex';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { GeoJSONSource } from 'maplibre-gl';
import type { RootState } from '@/assets/js/store';
import type { UserInfo } from '@/assets/js/types/store-types';

import { getInitialMapConfig as getWorldInitialMapConfig, getMapRecenterFromUserLocation } from '@/utils/map/mapConfigUtils';
import { resolveMapStyle, MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { useDocumentTitle } from '@/utils/documentTitle.js';

import FeatureListSidebar from './FeatureListSidebar.vue';
import MapControlsSidebar from './MapControlsSidebar.vue';
import FeatureInfoBox from './FeatureInfoBox.vue';
import MapErrorOverlay from './MapErrorOverlay.vue';
import MapLoadingIndicator from './MapLoadingIndicator.vue';
import MobileControlsBar from './MobileControlsBar.vue';
import LocationControl from './LocationControl.vue';
import Loader from '@/components/parts/Loader.vue';

// Lazy-loaded components - only loaded when needed
const FeatureEditBox = defineAsyncComponent(() => import('./FeatureEditBox.vue')) as Component;
const FeatureSelectionPopup = defineAsyncComponent(() => import('./FeatureSelectionPopup.vue')) as Component;
const ElevationProfileDialog = defineAsyncComponent(() => import('./ElevationProfileDialog.vue')) as Component;
const QuickPointDialog = defineAsyncComponent(() => import('./QuickPointDialog.vue')) as Component;
const ShareDialog = defineAsyncComponent(() => import('@/components/parts/ShareDialog.vue')) as Component;

import { useMapInitialization } from '@/composables/useMapInitialization';
import { useMapLayers } from '@/composables/useMapLayers';
import { useFeatureData } from '@/composables/useFeatureData';
import { useFeatureSelection } from '@/composables/useFeatureSelection';
import { useMapShare } from '@/composables/useMapShare';
import { useCollectionTagFilters } from '@/composables/useCollectionTagFilters';
import { useMapGeolocation, type GeocodingResult } from '@/composables/useMapGeolocation';
import type { LoadContext, LoadContextType, MapUserSettings, MapViewContext } from '@/composables/mapPageTypes';
import type { HiddenFeature } from '@/assets/js/store/modules/userSettings';
import type { GeoJsonFeature } from '@/types/geospatial';

/** Narrow view of root getters this component reads by namespaced key. */
interface RootGetters {
    'auth/userInfo': UserInfo | null;
    'userSettings/hiddenFeatures': HiddenFeature[];
    'userSettings/userSettings': Record<string, unknown> | null;
}

const route = useRoute();
const router = useRouter();
const store = useStore<RootState>();
const getters = computed(() => store.getters as RootGetters);

const sidebarKey = ref(0);
const activeMobileSidebar = ref<'features' | 'controls' | null>(null);
const showQuickPointDialog = ref(false);

/**
 * The terrain tooltip text branches on `isMobile`, but no component state actually tracks device
 * type here (the only other `isMobile` in this file is an unrelated local inside `zoomToFeature`),
 * so the tooltip always renders its "mouse button" wording. Hardcoded to `false` rather than wired
 * to a real mobile check, to avoid changing the tooltip's current behavior.
 */
const isMobile = false;

const isMainMapRoute = computed(() => {
    const hasCollection = !!route.query.collection;
    const hasTag = !!route.query.tag;
    return route.path === '/map' && !hasCollection && !hasTag;
});

const hiddenFeatureIds = computed<string[]>(() => {
    const features = getters.value['userSettings/hiddenFeatures'];
    if (!Array.isArray(features)) return [];
    return features.map((f) => String(f.id));
});

const hiddenFeatureSummaries = computed(() => {
    const features = getters.value['userSettings/hiddenFeatures'];
    if (!Array.isArray(features)) return [];
    return features.map((f) => ({
        id: String(f.id),
        name: f.name ?? null,
        geometry_type: f.geometry_type ?? null,
    }));
});

function getUserMapSettings(): MapUserSettings {
    const settings = getters.value['userSettings/userSettings'] as { map?: MapUserSettings } | null;
    return settings?.map ?? {};
}

/**
 * Wait briefly for `App.vue`'s `userSettings/fetchUserSettings` dispatch to resolve (or dispatch
 * it once ourselves if it still hasn't), so `fetchTileSources()` reads the real default basemap
 * instead of racing `App.vue` and falling back to the first tile source. Mirrors the Places
 * extension's `ensureUserSettingsLoaded` (`placesMapSettings.js`).
 */
function hasUserSettingsLoaded(): boolean {
    return getters.value['userSettings/userSettings'] != null;
}

async function ensureUserMapSettingsLoaded(waitMs = 3000, pollMs = 50): Promise<void> {
    if (hasUserSettingsLoaded()) return;
    const deadline = Date.now() + waitMs;
    while (!hasUserSettingsLoaded() && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, pollMs));
    }
    if (hasUserSettingsLoaded()) return;
    await store.dispatch('userSettings/fetchUserSettings');
}

/*
 * Composable wiring. Several composables need functions/state owned by composables created
 * later below (e.g. map event callbacks need feature-data/selection handlers; feature data
 * needs collection/tag filter state to build its load context). These are only INVOKED well
 * after setup finishes (on actual map events, API responses, etc.), so the forward-declared
 * bindings assigned further down are safe: the closures below just capture the variable
 * reference, not its value at closure-creation time.
 */
// eslint-disable-next-line prefer-const -- forward reference: assigned once, below, after the composables that capture it by closure
let featureSelection!: ReturnType<typeof useFeatureSelection>;
// eslint-disable-next-line prefer-const -- forward reference: assigned once, below, after the composables that capture it by closure
let featureData!: ReturnType<typeof useFeatureData>;
// eslint-disable-next-line prefer-const -- forward reference: assigned once, below, after the composables that capture it by closure
let collectionTagFilters!: ReturnType<typeof useCollectionTagFilters>;
// eslint-disable-next-line prefer-const -- forward reference: assigned once, below, after the composables that capture it by closure
let mapGeolocation!: ReturnType<typeof useMapGeolocation>;

const mapShare = useMapShare({
    getSelectedFeature: () => featureSelection.selectedFeature.value,
});

/** Gate for hide/unhide actions: main map route, not a public share, and the user is authenticated. */
const canManageHiddenFeatures = computed(() => isMainMapRoute.value && !mapShare.isPublicShareMode.value && !!getters.value['auth/userInfo']);

const mapInit = useMapInitialization({
    getEnableAntialias: () => !!getUserMapSettings().enable_antialias,
    callbacks: {
        onMoveOrZoomStart: () => { featureData.cancelPendingBboxQuery(); },
        onMoveEnd: () => {
            featureData.debouncedLoadData();
            featureData.debouncedUpdateFeaturesInExtent();
        },
        onZoomEnd: () => {
            featureData.debouncedLoadData();
            featureData.debouncedUpdateFeaturesInExtent();
            void featureData.reprocessFeaturesForZoom();
            featureData.debouncedUpdateSmallFeatureFlags();
        },
        onZoomFrame: () => { featureData.handleZoomUpdate(); },
        onClick: (e) => { featureSelection.onMapClick(e); },
        onMouseMove: (e) => { featureSelection.onMapMouseMove(e); },
        onMouseOut: () => { featureSelection.onMapMouseOut(); },
        isTrackingLocked: () => mapGeolocation.trackingState.value === 'locked',
        onTrackingUnlock: () => {
            mapGeolocation.trackingState.value = 'tracking';
        },
    },
});

mapGeolocation = useMapGeolocation({
    map: mapInit.map,
    isMapshareRoute: mapShare.isMapshareRoute,
    navigateAndRefresh: (fn, clear) => featureData.navigateAndRefresh(fn, clear),
});

featureSelection = useFeatureSelection({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    showAllLabels: mapInit.showAllLabels,
    navigateAndRefresh: (fn, clear) => featureData.navigateAndRefresh(fn, clear),
    updateFeatureCount: () => { featureData.updateFeatureCount(); },
    updateFeaturesInExtent: () => { featureData.updateFeaturesInExtent(); },
    getUserMapSettings,
    isPublicShareMode: mapShare.isPublicShareMode,
    shareId: mapShare.shareId,
    canManageHiddenFeatures,
});

/** Builds the current load context (default/collection/share_*) from route + share/tag state. */
function getLoadContext(): LoadContext {
    if (mapShare.isPublicShareMode.value && mapShare.shareId.value) {
        const shareInfo = mapShare.publicShareInfo.value;
        if (shareInfo?.share_id === mapShare.shareId.value) {
            return {
                type: `share_${shareInfo.share_type}` as LoadContextType,
                isPublicShare: true,
                shareId: mapShare.shareId.value,
                shareInfo,
            };
        }
        return { type: 'share_unknown', isPublicShare: true, shareId: mapShare.shareId.value, shareInfo: null };
    }

    if (collectionTagFilters.isCollectionMode.value && collectionTagFilters.collectionId.value) {
        return { type: 'collection', isPublicShare: false, collectionId: collectionTagFilters.collectionId.value };
    }

    return {
        type: 'default',
        isPublicShare: false,
        tags: collectionTagFilters.currentTags.value,
        matchMode: collectionTagFilters.currentTagMatchMode.value,
    };
}

featureData = useFeatureData({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    showAllLabels: mapInit.showAllLabels,
    isMapInitializing: mapInit.isMapInitializing,
    waitForMapEvent: mapInit.waitForMapEvent,
    getUserMapSettings,
    getLoadContext,
    ensurePublicShareInfo: mapShare.ensurePublicShareInfo,
    handlePublicShareError: mapShare.handlePublicShareError,
    onAfterFeaturesChanged: () => { featureSelection.updateFeatureHighlighting(); },
    onFeatureShareLoaded: async (feature) => {
        featureSelection.selectedFeature.value = feature;
    },
    zoomToTaggedFeatures: (features, options) => collectionTagFilters.zoomToTaggedFeatures(features, options),
    publicShareRefinedFitShareId: mapShare.publicShareRefinedFitShareId,
    isMapshareRoute: mapShare.isMapshareRoute,
});

collectionTagFilters = useCollectionTagFilters({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    isDataLoading: featureData.isDataLoading,
    loadError: featureData.loadError,
    featuresInExtent: featureData.featuresInExtent,
    featureCount: featureData.featureCount,
    cachedGeoJsonData: featureData.cachedGeoJsonData,
    selectedFeature: featureSelection.selectedFeature,
    navigateAndRefresh: featureData.navigateAndRefresh,
    addFeaturesToMap: featureData.addFeaturesToMap,
    invalidateSourceCache: featureData.invalidateSourceCache,
    clearLoadedBounds: featureData.clearLoadedBounds,
    loadDataForCurrentView: featureData.loadDataForCurrentView,
    waitForMap: mapInit.waitForMap,
    waitForMapEvent: mapInit.waitForMapEvent,
    zoomToFeature: featureSelection.zoomToFeature,
    updateFeatureCount: featureData.updateFeatureCount,
    updateFeaturesInExtent: featureData.updateFeaturesInExtent,
});

const mapLayers = useMapLayers({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    showAllLabels: mapInit.showAllLabels,
    createMapInstance: mapInit.createMapInstance,
    destroyMap: mapInit.destroyMap,
    ensureMapResize: mapInit.ensureMapResize,
    waitForMapEvent: mapInit.waitForMapEvent,
    updateLayerMaxZoom: mapInit.updateLayerMaxZoom,
    getDefaultBasemap: () => getUserMapSettings().default_basemap,
    cachedGeoJsonData: featureData.cachedGeoJsonData,
    hasLoadedBounds: featureData.hasLoadedBounds,
    loadDataForCurrentView: featureData.loadDataForCurrentView,
    onAfterFeaturesChanged: () => { featureSelection.updateFeatureHighlighting(); },
    setLoadError: (message) => {
        featureData.loadError.value = message;
    },
});

// --- Flat bindings for template + script-internal use (script setup auto-unwraps top-level refs). ---

const { mapContainer, map, showAllLabels, isMapInitializing, mapWasDestroyed, savedMapCenter, savedMapZoom, savedMapPitch, savedMapBearing, createMapInstance, performMapDestruction, ensureMapResize, waitForElement, updateLayerMaxZoom } =
    mapInit;

const {
    tileSources,
    selectedLayer,
    maptilerConfig,
    terrainEnabled,
    showTerrainTooltip,
    hillshadeEnabled,
    fetchTileSources,
    fetchMaptilerConfig,
    setupTerrain,
    addHillshadeIfNeeded,
    toggleTerrain,
    switchMapLayer,
    applyTerrainAndHillshade,
    handleHillshadeChange,
} = mapLayers;

const { isDataLoading, isInitialLoad, loadError, featuresInExtent, featureCount } = featureData;

const {
    selectedFeature,
    isEditingFeature,
    showElevationProfile,
    overlappingFeatures,
    showFeaturePopup,
    popupPosition,
    zoomToFeature,
    handleFeatureListClick,
    handleFeatureSelect,
    handleEditFeature,
    handleCancelEdit,
    handleFeatureDeleted,
    handleFeatureSaved,
    handleHideFeature,
    handleQuickPointCreated,
    handleDownloadFeatureKmz,
    handleElevationProfileClose,
    handleHoverPoint,
    handleHoverClear,
    handleClickPoint,
} = featureSelection;

const {
    isMapshareRoute,
    isPublicShareMode,
    shareId,
    publicShareError,
    publicShareInfo,
    publicShareTag,
    publicShareCollectionName,
    publicShareAllowedOptions,
    showFeatureShareDialog,
    featureToShare,
    handleShareFeature,
    handleCloseFeatureShareDialog,
} = mapShare;

const {
    collectionId,
    initialSelectedTags,
    isCollectionMode,
    collectionName,
    isTagFilterActive,
    tagFilteredFeatures,
    currentTags,
    availableTags,
    fetchAvailableTags,
    handleCollectionFilter,
    handleUrlTag,
    handleTagFilterChange,
    filterExistingFeaturesByTags,
} = collectionTagFilters;

const { userLocation, trackingState, getUserLocation, centerToHomeExtent, toggleLocationTracking, handleGeocodingResult, clearGeocodingMarker } = mapGeolocation;

/** Preserved for parity with the template's original binding name (see `getLocationDisplayName()` call in the template). */
function getLocationDisplayName(): string {
    return mapGeolocation.getLocationDisplayName();
}

const viewContext = computed((): MapViewContext | null => {
    if (isPublicShareMode.value) {
        if (publicShareTag.value) {
            return { type: 'tag', name: publicShareTag.value, isPublicShare: true };
        }
        if (publicShareCollectionName.value) {
            return { type: 'collection', name: publicShareCollectionName.value, isPublicShare: true };
        }
        if (publicShareInfo.value?.share_type === 'feature') {
            return { type: 'feature', name: publicShareInfo.value.feature_name ?? 'Shared Feature', isPublicShare: true };
        }
        return null;
    }

    if (collectionName.value) {
        return { type: 'collection', name: collectionName.value, isPublicShare: false };
    }

    const tag = route.query.tag;
    if (tag) {
        return { type: 'tag', name: (Array.isArray(tag) ? tag[0] : tag) ?? '', isPublicShare: false };
    }

    return null;
});

useDocumentTitle(() => {
    const path = route.path;
    if (path !== '/map' && path !== '/mapshare') return 'Map';
    if (!isPublicShareMode.value) return 'Map';
    if (publicShareError.value) return 'Share';
    return viewContext.value?.name ?? 'Share';
});

// --- Wrapper handlers threading one composable's reload/clear callbacks into another's event handlers. ---

function removeFeatureIdFromUrl(): void {
    const query = { ...route.query };
    delete query.featureId;
    void router.replace({ path: route.path, query });
}

async function handleUrlFeatureId(): Promise<void> {
    const featureIdParam = route.query.featureId as string | undefined;
    await collectionTagFilters.handleUrlFeatureId(featureIdParam, removeFeatureIdFromUrl);
}

async function handleUnhideFeature(featureId: string | number): Promise<void> {
    await featureSelection.handleUnhideFeature(featureId, featureData.clearLoadedBounds, featureData.loadDataForCurrentView);
}

async function handleUnhideAllHidden(): Promise<void> {
    await featureSelection.handleUnhideAllHidden(featureData.clearLoadedBounds, featureData.loadDataForCurrentView);
}

async function handleEditBoxVisibilityChange(payload: { featureId?: string | number; hidden?: boolean } | null): Promise<void> {
    await featureSelection.handleEditBoxVisibilityChange(payload, featureData.clearLoadedBounds, featureData.loadDataForCurrentView);
}

async function handleLabelsVisibilityChange(showLabels: boolean): Promise<void> {
    await mapLayers.handleLabelsVisibilityChange(showLabels, featureData.clearLoadedBounds);
}

/**
 * `FeatureListSidebar`'s `tag-filter-start` emits with no payload (it signals "the user is about
 * to change the tag filter", not "here are the new tags") - the tags to pre-filter with are our
 * own `currentTags` state, already updated via `tag-filter-change` by the time this fires.
 */
function handleTagFilterStart(): void {
    filterExistingFeaturesByTags(currentTags.value);
}

/** `FeatureListSidebar` types this emit as `unknown` since the geocoding result shape is search-provider-specific. */
function handleReverseGeocodingResultClick(result: unknown): void {
    void handleGeocodingResult(result as GeocodingResult | null);
}

/** Sidebar row hover: reuse the map's existing hover-highlight channel so a hovered row highlights its feature on the map. */
function handleFeatureListHover(feature: GeoJsonFeature | null): void {
    const id = (feature?.properties.database_id ?? null) as string | number | null;
    if (featureSelection.hoveredFeatureId.value === id) return;
    featureSelection.hoveredFeatureId.value = id;
    featureSelection.updateFeatureHighlighting();
}

// --- Boot / keep-alive lifecycle orchestration ---

async function initializeMap(mapConfig: Parameters<typeof createMapInstance>[0]): Promise<void> {
    if (!mapContainer.value || !(mapContainer.value instanceof HTMLElement)) {
        throw new Error('Map container is not available or is not an HTMLElement');
    }
    createMapInstance(mapConfig);
    ensureMapResize();
}

/**
 * Compute the initial camera to paint the map with on first construction: the URL-driven views
 * (collection/tag/featureId) fit themselves after data loads, so they start at the world view;
 * otherwise use the geolocation-based recenter if we have one, falling back to the world view
 * (in which case `syncPendingExtentFitWithoutGeolocation` fits to loaded feature data instead).
 */
function getInitialCameraConfig(skipUrlDrivenCamera: boolean) {
    if (skipUrlDrivenCamera) return getWorldInitialMapConfig();
    return getMapRecenterFromUserLocation(userLocation.value) ?? getWorldInitialMapConfig();
}

/** Re-applies the map/layer maxzoom overrides once the (freshly baked-in) style has loaded. */
function applyPostLoadMaxZoom(): void {
    if (!map.value) return;
    map.value.setMaxZoom(MAX_ZOOM_LEVEL);
    updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);
}

function logMapState(): void {
    if (!map.value) {
        console.log('Map State: Map not initialized');
        return;
    }

    const mapSettings = getUserMapSettings();

    console.log('🗺️ Map State on Load:', {
        map: {
            center: map.value.getCenter(),
            zoom: map.value.getZoom(),
            pitch: map.value.getPitch(),
            bearing: map.value.getBearing(),
            loaded: map.value.loaded(),
        },
        layer: { selected: selectedLayer.value, available: tileSources.value.length },
        features: { inExtent: featuresInExtent.value.length, loaded: featureCount.value },
        settings: {
            antialias: mapSettings.enable_antialias ?? false,
            terrain: { enabled: terrainEnabled.value, default: mapSettings.enable_3d_terrain ?? false, available: maptilerConfig.value?.isAvailable() ?? false },
            hillshade: { enabled: hillshadeEnabled.value, default: mapSettings.enable_hillshade ?? false, available: maptilerConfig.value?.isAvailable() ?? false },
            defaultBasemap: mapSettings.default_basemap ?? 'osm',
            replaceIconsLowZoom: mapSettings.replace_icons_low_zoom ?? true,
        },
        mode: {
            isPublicShare: isPublicShareMode.value,
            isCollectionMode: isCollectionMode.value,
            collectionId: collectionId.value,
            isTagFilterActive: isTagFilterActive.value,
        },
    });
}

/** Full destruction used when navigating away (keep-alive `deactivated`): saves camera state, clears feature state, cancels in-flight work. */
function cleanupOnNavigateAway(): void {
    const source: GeoJSONSource | undefined = map.value?.getSource('geojson-data');
    source?.setData({ type: 'FeatureCollection', features: [] });

    featuresInExtent.value = [];
    featureData.clearLoadedBounds();
    selectedFeature.value = null;
    isEditingFeature.value = false;
    showElevationProfile.value = false;

    featureData.cancelPendingRequests();
    handleHoverClear();

    performMapDestruction();

    featureCount.value = 0;
}

/** Re-create the map after a keep-alive `deactivated` destroyed it, restoring camera/layer/terrain/data state. */
async function restoreMap(): Promise<void> {
    if (map.value) return;

    featureData.mainMapExtentHintRequested.value = false;
    isMapInitializing.value = true;
    isDataLoading.value = true;

    await nextTick();

    try {
        await waitForElement(mapContainer);
    } catch (error) {
        console.error('Map container not available for restore:', error instanceof Error ? error.message : error);
        isMapInitializing.value = false;
        isDataLoading.value = false;
        return;
    }

    try {
        if (!isMapshareRoute.value && getters.value['auth/userInfo']) {
            await ensureUserMapSettingsLoaded();
        }

        if (getters.value['auth/userInfo']) {
            await fetchAvailableTags();
        }

        const skipUrlDrivenCamera = !!collectionId.value || !!route.query.featureId || !!route.query.tag;

        let mapConfig;
        if (savedMapCenter.value && savedMapZoom.value !== null) {
            mapConfig = {
                center: [savedMapCenter.value.lng, savedMapCenter.value.lat] as [number, number],
                zoom: savedMapZoom.value,
                pitch: savedMapPitch.value ?? 0,
                bearing: savedMapBearing.value ?? 0,
            };
        } else {
            mapConfig = { ...getInitialCameraConfig(skipUrlDrivenCamera), pitch: 0, bearing: 0 };
        }

        const initialTileSource = tileSources.value.find((s) => s.id === selectedLayer.value);
        createMapInstance({ ...mapConfig, style: resolveMapStyle(initialTileSource) });

        await new Promise<void>((resolve) => {
            if (map.value?.loaded()) {
                resolve();
            } else {
                void map.value?.once('load', () => { resolve(); });
            }
        });

        applyPostLoadMaxZoom();

        if (terrainEnabled.value && maptilerConfig.value?.isAvailable()) {
            await setupTerrain();
        }

        if (hillshadeEnabled.value && maptilerConfig.value?.isAvailable()) {
            addHillshadeIfNeeded();
        }

        if (!savedMapCenter.value) {
            featureData.syncPendingExtentFitWithoutGeolocation(skipUrlDrivenCamera);
        } else {
            featureData.pendingExtentFitWithoutGeolocation.value = false;
        }

        if (collectionId.value) {
            await handleCollectionFilter(collectionId.value);
        } else {
            await featureData.loadDataForCurrentView();
        }

        ensureMapResize();
        featureData.updateFeaturesInExtent();
    } catch (error) {
        console.error('Error restoring map:', error);
        loadError.value = error instanceof Error ? error.message : 'Failed to restore map';
    } finally {
        isMapInitializing.value = false;
    }
}

let handleKeyDown: ((event: KeyboardEvent) => void) | null = null;

onMounted(async () => {
    isMapInitializing.value = true;
    isDataLoading.value = true;

    handleKeyDown = (event: KeyboardEvent) => {
        if (event.key === 'Escape' || event.key === 'Esc') {
            if (selectedFeature.value && !isEditingFeature.value) {
                selectedFeature.value = null;
            }
        }
    };
    window.addEventListener('keydown', handleKeyDown);

    await nextTick();

    try {
        await waitForElement(mapContainer);
    } catch (error) {
        console.error('Map container not available:', error instanceof Error ? error.message : error);
        isMapInitializing.value = false;
        isDataLoading.value = false;
        loadError.value = 'Map container failed to initialize. Please refresh the page.';
        return;
    }

    if (!isMapshareRoute.value && getters.value['auth/userInfo']) {
        await ensureUserMapSettingsLoaded();
    }

    const tileSourcesPromise = fetchTileSources();
    const userLocationPromise = isMapshareRoute.value ? Promise.resolve() : getUserLocation();
    const tagsPromise = getters.value['auth/userInfo'] ? fetchAvailableTags() : Promise.resolve();

    const [fetchedTileSources] = await Promise.all([tileSourcesPromise, userLocationPromise, tagsPromise]);
    await fetchMaptilerConfig(fetchedTileSources);

    // Everything the initial camera/basemap need (settings, geolocation, tile sources) is
    // already resolved above, so bake both into map construction instead of birthing the map
    // at the blank/world-view default and correcting it after 'load'.
    const skipUrlDrivenCamera = !!collectionId.value || !!route.query.featureId || !!route.query.tag;
    const initialCamera = getInitialCameraConfig(skipUrlDrivenCamera);
    const initialTileSource = tileSources.value.find((s) => s.id === selectedLayer.value);
    const initialStyle = resolveMapStyle(initialTileSource);

    try {
        await initializeMap({ ...initialCamera, style: initialStyle });
    } catch (error) {
        console.error('Error initializing map:', error);
        loadError.value = error instanceof Error ? error.message : 'Failed to initialize map. Please refresh the page.';
        isMapInitializing.value = false;
        isDataLoading.value = false;
        return;
    }

    if (initialSelectedTags.value.length > 0) {
        currentTags.value = initialSelectedTags.value;
        isTagFilterActive.value = true;
    }

    await new Promise<void>((resolve) => {
        if (map.value?.loaded()) {
            resolve();
        } else {
            void map.value?.once('load', () => { resolve(); });
        }
    });

    const userSettings = getUserMapSettings();
    const defaultTerrainOn = !!userSettings.enable_3d_terrain;
    const defaultHillshadeOn = !!userSettings.enable_hillshade;

    terrainEnabled.value = defaultTerrainOn && !!maptilerConfig.value?.isAvailable();
    hillshadeEnabled.value = defaultHillshadeOn && !!maptilerConfig.value?.isAvailable();

    applyPostLoadMaxZoom();

    if (selectedLayer.value) {
        await applyTerrainAndHillshade(selectedLayer.value);
    }

    if (terrainEnabled.value && map.value) {
        map.value.setPitch(50);
    }

    featureData.syncPendingExtentFitWithoutGeolocation(skipUrlDrivenCamera);

    if (collectionId.value) {
        await handleCollectionFilter(collectionId.value);
    } else if (route.query.featureId) {
        await handleUrlFeatureId();
    } else if (route.query.tag) {
        isTagFilterActive.value = true;
        await handleUrlTag();
    } else {
        await featureData.loadDataForCurrentView();
    }

    isMapInitializing.value = false;
    ensureMapResize();
    featureData.updateFeaturesInExtent();
    logMapState();
});

onActivated(() => {
    isDataLoading.value = true;

    const activateSource: GeoJSONSource | undefined = map.value?.getSource('geojson-data');
    activateSource?.setData({ type: 'FeatureCollection', features: [] });
    featuresInExtent.value = [];
    featureData.clearLoadedBounds();
    selectedFeature.value = null;
    isEditingFeature.value = false;
    showElevationProfile.value = false;

    if (!route.query.tag) {
        isTagFilterActive.value = false;
        tagFilteredFeatures.value = [];
    }

    isInitialLoad.value = true;
    featureData.mainMapExtentHintRequested.value = false;

    sidebarKey.value += 1;

    if (mapWasDestroyed.value) {
        void restoreMap();
        mapWasDestroyed.value = false;
        return;
    }

    const hasTagQuery = !!route.query.tag;
    const hasCollectionQuery = !!route.query.collection;
    const hasFeatureId = !!route.query.featureId;

    if (!map.value) return;

    if (hasCollectionQuery) {
        void handleCollectionFilter(collectionId.value);
    } else if (hasFeatureId) {
        void handleUrlFeatureId();
    } else if (hasTagQuery) {
        isTagFilterActive.value = true;
        void handleUrlTag();
    } else {
        const skipUrlDrivenCamera = !!collectionId.value || !!route.query.featureId || !!route.query.tag;
        featureData.syncPendingExtentFitWithoutGeolocation(skipUrlDrivenCamera);
        void featureData.loadDataForCurrentView().then(() => {
            featureData.updateFeaturesInExtent();
            const resizeWhenIdle = (): void => {
                void map.value?.once('idle', () => map.value?.resize());
            };
            if (map.value?.loaded()) {
                resizeWhenIdle();
            } else {
                void map.value?.once('load', resizeWhenIdle);
            }
        });
    }
});

onDeactivated(() => {
    cleanupOnNavigateAway();
});

onBeforeUnmount(() => {
    if (handleKeyDown) {
        window.removeEventListener('keydown', handleKeyDown);
    }
    mapGeolocation.cleanup();
    performMapDestruction();
});

// --- Watchers ---

watch(selectedFeature, () => {
    void nextTick(() => {
        featureSelection.updateFeatureHighlighting();
    });
});

watch(isEditingFeature, () => {
    void nextTick(() => {
        featureSelection.updateFeatureHighlighting();
    });
});

// Handle featureId query parameter changes for subsequent navigations (component already mounted).
watch(
    () => route.query.featureId,
    (newFeatureId, oldFeatureId) => {
        if (newFeatureId && newFeatureId !== oldFeatureId) {
            void handleUrlFeatureId();
        }
    },
);

// Handle tag query parameter changes for subsequent navigations (component already mounted).
watch(
    () => route.query.tag,
    (newTag, oldTag) => {
        const newTagValue = Array.isArray(newTag) ? newTag[0] : newTag;
        const oldTagValue = Array.isArray(oldTag) ? oldTag[0] : oldTag;

        if (newTagValue && newTagValue !== oldTagValue) {
            featureData.cancelPendingRequests();

            isCollectionMode.value = false;
            collectionName.value = null;

            currentTags.value = [newTagValue];
            isTagFilterActive.value = true;

            sidebarKey.value += 1;

            void nextTick(async () => {
                await handleUrlTag();
            });
        } else if (!newTagValue && oldTagValue) {
            isTagFilterActive.value = false;
            currentTags.value = null;
            featureData.clearLoadedBounds();
            sidebarKey.value += 1;
            void featureData.loadDataForCurrentView();
        }
    },
);

// Handle collection query parameter changes for subsequent navigations (component already mounted).
watch(
    () => route.query.collection,
    (newCollectionId, oldCollectionId) => {
        if (newCollectionId && newCollectionId !== oldCollectionId) {
            featureData.cancelPendingRequests();

            isTagFilterActive.value = false;
            currentTags.value = null;

            sidebarKey.value += 1;

            void handleCollectionFilter(newCollectionId as string);
        } else if (!newCollectionId && oldCollectionId) {
            isCollectionMode.value = false;
            collectionName.value = null;
            featureData.clearLoadedBounds();
            sidebarKey.value += 1;
            void featureData.loadDataForCurrentView();
        }
    },
);
</script>

<style>
@import 'maplibre-gl/dist/maplibre-gl.css';

/* 3D Terrain toggle button styling */
.maplibregl-ctrl-terrain {
  background-color: #fff;
  background-repeat: no-repeat;
  background-position: center;
  width: 29px;
  height: 29px;
  /* Default state (OFF) - dark gray */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%23333' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain:hover {
  /* Hover state when OFF - slightly lighter gray */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%23555' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain.maplibregl-ctrl-terrain-enabled {
  /* Enabled state (ON) - light blue */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%2333b5e5' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain.maplibregl-ctrl-terrain-enabled:hover {
  /* Hover state when ON - brighter light blue */
  background-image: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='22' height='22' fill='%2350c8ff' viewBox='0 0 22 22'%3E%3Cpath d='m1.754 13.406 4.453-4.851 3.09 3.09 3.281 3.277.969-.969-3.309-3.312 3.844-4.121 6.148 6.886h1.082v-.855l-7.207-8.07-4.84 5.187L6.169 6.57l-5.48 5.965v.871ZM.688 16.844h20.625v1.375H.688Zm0 0'/%3E%3C/svg%3E");
}

.maplibregl-ctrl-terrain:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 3D Terrain tooltip styling */
.maplibregl-ctrl-terrain-tooltip {
  position: absolute;
  left: 38px;
  top: 0;
  background-color: #fff;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 12px;
  color: #333;
  white-space: nowrap;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
  pointer-events: none;
  z-index: 1;
  opacity: 0;
  transition: opacity 0.3s ease-in-out;
}

.maplibregl-ctrl-terrain-tooltip-visible {
  opacity: 1;
}
</style>

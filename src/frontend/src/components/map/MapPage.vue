<template>
  <div class="w-full h-full flex">
    <!-- Left Sidebar - Feature List -->
    <FeatureListSidebar
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
            v-if="selectedFeature && !isEditingFeature && !showElevationProfile"
            :feature="selectedFeature ?? undefined"
            :show-download-button="!isPublicShareMode || !!(publicShareInfo && publicShareInfo.allow_downloads)"
            :show-edit-button="!isPublicShareMode"
            :show-share-button="!isPublicShareMode"
            @close="selectedFeature = null"
            @download="handleDownloadFeatureKmz"
            @edit="handleEditFeature"
            @zoom="zoomToFeature(selectedFeature)"
            @show-profile="showElevationProfile = true"
            @share="handleShareFeature"
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
            :share-id="isPublicShareMode ? shareId : null"
            :is-public-share="isPublicShareMode"
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
defineOptions({ name: 'Map' });
import { useRoute, useRouter } from 'vue-router';
import { useStore } from 'vuex';
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
import type { MapUserSettings, MapViewContext } from '@/composables/mapPageTypes';
import type { HiddenFeature } from '@/assets/js/store/modules/userSettings';
import type { GeoJsonFeature } from '@/types/geospatial';
import { MapSession } from '@/utils/map/session/MapSession';
import type { MapRouteLocation } from '@/utils/map/session/types';
import { settingsReady } from '@/utils/settings/SettingsReady';

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

const activeMobileSidebar = ref<'features' | 'controls' | null>(null);
const showQuickPointDialog = ref(false);
const isMobile = ref(typeof window !== 'undefined' && window.innerWidth < 768);

function syncIsMobile(): void {
    isMobile.value = window.innerWidth < 768;
}

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

async function ensureUserMapSettingsLoaded(): Promise<void> {
    await settingsReady.awaitReady(() => store.dispatch('userSettings/fetchUserSettings'));
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

function routeLocation(): MapRouteLocation {
    return { path: route.path, query: route.query as MapRouteLocation['query'] };
}

const mapSession = new MapSession({
    getContainer: () => (mapContainer.value instanceof HTMLElement ? mapContainer.value : null),
    getAntialias: () => !!getUserMapSettings().enable_antialias,
    getDefaultBasemap: () => getUserMapSettings().default_basemap,
    getHiddenIds: () => hiddenFeatureIds.value,
    canWrite: () => canManageHiddenFeatures.value,
    showLabels: () => showAllLabels.value,
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
    onClick: (event) => { featureSelection.onMapClick(event as never); },
    onMouseMove: (event) => { featureSelection.onMapMouseMove(event as never); },
    onMouseOut: () => { featureSelection.onMapMouseOut(); },
    onWebGlLost: () => { loadError.value = 'The map graphics context was lost. Refresh the page.'; },
});

const mapShare = useMapShare({
    getSelectedFeature: () => featureSelection.selectedFeature.value,
});

/** Gate for hide/unhide actions: main map route, not a public share, and the user is authenticated. */
const canManageHiddenFeatures = computed(() => isMainMapRoute.value && !mapShare.isPublicShareMode.value && !!getters.value['auth/userInfo']);

const mapInit = useMapInitialization({
    runtime: mapSession.runtime,
    getEnableAntialias: () => !!getUserMapSettings().enable_antialias,
});

mapGeolocation = useMapGeolocation({
    map: mapInit.map,
    isMapshareRoute: mapShare.isMapshareRoute,
    location: mapSession.location,
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
    mutations: mapSession.mutations,
    featureSource: mapSession.features,
});

function getSessionLoadContext() {
    mapSession.filters.applyRoute(routeLocation());
    const tags = collectionTagFilters.currentTags.value;
    if (tags?.length) {
        mapSession.filters.setSidebarTags(tags, collectionTagFilters.currentTagMatchMode.value);
    }
    return mapSession.filters.getLoadContext(mapShare.publicShareInfo.value);
}

featureData = useFeatureData({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    showAllLabels: mapInit.showAllLabels,
    isMapInitializing: mapInit.isMapInitializing,
    waitForMapEvent: mapInit.waitForMapEvent,
    getUserMapSettings,
    getSessionLoadContext,
    ensurePublicShareInfo: mapShare.ensurePublicShareInfo,
    handlePublicShareError: mapShare.handlePublicShareError,
    onAfterFeaturesChanged: () => { featureSelection.updateFeatureHighlighting(); },
    onFeatureShareLoaded: async (feature) => {
        featureSelection.selectedFeature.value = feature;
    },
    zoomToTaggedFeatures: (features, options) => collectionTagFilters.zoomToTaggedFeatures(features, options),
    publicShareRefinedFitShareId: mapShare.publicShareRefinedFitShareId,
    isMapshareRoute: mapShare.isMapshareRoute,
    featureSource: mapSession.features,
    loadPipeline: mapSession.pipeline,
    hiddenFeatures: mapSession.hidden,
    elevations: mapSession.elevations,
});

collectionTagFilters = useCollectionTagFilters({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    isDataLoading: featureData.isDataLoading,
    loadError: featureData.loadError,
    selectedFeature: featureSelection.selectedFeature,
    navigateAndRefresh: featureData.navigateAndRefresh,
    addFeaturesToMap: featureData.addFeaturesToMap,
    resetFeatureState: featureData.resetFeatureState,
    loadDataForCurrentView: featureData.loadDataForCurrentView,
    waitForMap: mapInit.waitForMap,
    zoomToFeature: featureSelection.zoomToFeature,
});

const mapLayers = useMapLayers({
    map: mapInit.map,
    labelMarkerManager: mapInit.labelMarkerManager,
    showAllLabels: mapInit.showAllLabels,
    ensureMapResize: mapInit.ensureMapResize,
    updateLayerMaxZoom: mapInit.updateLayerMaxZoom,
    getDefaultBasemap: () => getUserMapSettings().default_basemap,
    hasLoadedBounds: featureData.hasLoadedBounds,
    loadDataForCurrentView: featureData.loadDataForCurrentView,
    onAfterFeaturesChanged: () => { featureSelection.updateFeatureHighlighting(); },
    setLoadError: (message) => {
        featureData.loadError.value = message;
    },
    tiles: mapSession.tiles,
    runtime: mapSession.runtime,
    featureSource: mapSession.features,
    hiddenFeatures: mapSession.hidden,
});

// --- Flat bindings for template + script-internal use (script setup auto-unwraps top-level refs). ---

const { mapContainer, map, showAllLabels, isMapInitializing, savedMapCenter, savedMapZoom, savedMapPitch, savedMapBearing, createMapInstance, performMapDestruction, ensureMapResize, waitForElement, updateLayerMaxZoom } =
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
    applyUserTerrainDefaults,
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
    resetForRoute: resetShareForRoute,
} = mapShare;

const {
    collectionId,
    initialSelectedTags,
    isCollectionMode,
    collectionName,
    isTagFilterActive,
    currentTags,
    availableTags,
    fetchAvailableTags,
    handleCollectionFilter,
    handleUrlTag,
    handleTagFilterChange,
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
            return { type: 'feature', name: publicShareInfo.value.feature_name || 'Shared Feature', isPublicShare: true };
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
    return viewContext.value?.name || 'Share';
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
    await createMapInstance(mapConfig);
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

/** Keep-alive leave: stop GPS and in-flight loads, save camera, keep FeatureSource + map. */
function cleanupOnNavigateAway(): void {
    mapSession.deactivate();
    mapGeolocation.cleanup();
    featureData.cancelPendingRequests();
    handleHoverClear();
    if (map.value) {
        mapInit.savedMapCenter.value = map.value.getCenter();
        mapInit.savedMapZoom.value = map.value.getZoom();
        mapInit.savedMapPitch.value = map.value.getPitch();
        mapInit.savedMapBearing.value = map.value.getBearing();
    }
}

function resetUiForRoute(): void {
    selectedFeature.value = null;
    isEditingFeature.value = false;
    showElevationProfile.value = false;
    showFeaturePopup.value = false;
    overlappingFeatures.value = [];
    showQuickPointDialog.value = false;
    activeMobileSidebar.value = null;
}

async function loadRouteData(): Promise<void> {
    if (route.path === '/mapshare' && !route.query.id) {
        mapShare.handlePublicShareError('This share link is missing an id.');
        return;
    }
    if (collectionId.value) {
        await handleCollectionFilter(collectionId.value);
        return;
    }
    if (route.query.featureId) {
        await handleUrlFeatureId();
        return;
    }
    if (route.query.tag) {
        isTagFilterActive.value = true;
        await handleUrlTag();
        return;
    }
    await featureData.loadDataForCurrentView();
}

async function recreateMapIfMissing(): Promise<void> {
    if (map.value) return;
    isMapInitializing.value = true;
    await nextTick();
    await waitForElement(mapContainer);
    const skipUrlDrivenCamera = mapSession.filters.isUrlDrivenCamera;
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
    await createMapInstance({ ...mapConfig, style: resolveMapStyle(initialTileSource) });
    applyPostLoadMaxZoom();
    if (terrainEnabled.value && maptilerConfig.value?.isAvailable()) {
        await setupTerrain();
    }
    if (hillshadeEnabled.value && maptilerConfig.value?.isAvailable()) {
        addHillshadeIfNeeded();
    }
    isMapInitializing.value = false;
}

async function handleKeepAliveActivate(): Promise<void> {
    const action = await mapSession.activate(routeLocation());
    if (action === 'restore') {
        ensureMapResize();
        mapSession.features.commit(mapSession.hidden);
        featureData.updateFeaturesInExtent();
        return;
    }
    if (action === 'reload') {
        ensureMapResize();
        featureData.clearLoadedBounds();
        mapSession.cache.clear();
        await featureData.loadDataForCurrentView({ force: true });
        return;
    }
    resetUiForRoute();
    featureData.resetFeatureState();
    resetShareForRoute();
    await recreateMapIfMissing();
    await loadRouteData();
    ensureMapResize();
    featureData.updateFeaturesInExtent();
}

let handleKeyDown: ((event: KeyboardEvent) => void) | null = null;

onMounted(async () => {
    isMapInitializing.value = true;
    isDataLoading.value = true;
    window.addEventListener('resize', syncIsMobile);

    handleKeyDown = (event: KeyboardEvent) => {
        if (event.key !== 'Escape' && event.key !== 'Esc') return;
        if (isEditingFeature.value) {
            handleCancelEdit();
            return;
        }
        if (showElevationProfile.value) {
            showElevationProfile.value = false;
            return;
        }
        if (showFeaturePopup.value) {
            showFeaturePopup.value = false;
            return;
        }
        if (showQuickPointDialog.value) {
            showQuickPointDialog.value = false;
            return;
        }
        if (activeMobileSidebar.value) {
            activeMobileSidebar.value = null;
            return;
        }
        if (selectedFeature.value) {
            selectedFeature.value = null;
        }
    };
    window.addEventListener('keydown', handleKeyDown);

    await mapSession.activate(routeLocation());
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

    await Promise.all([tileSourcesPromise, userLocationPromise, tagsPromise]);
    await fetchMaptilerConfig();

    const skipUrlDrivenCamera = mapSession.filters.isUrlDrivenCamera;
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
    applyUserTerrainDefaults(
        !!userSettings.enable_3d_terrain && !!maptilerConfig.value?.isAvailable(),
        !!userSettings.enable_hillshade && !!maptilerConfig.value?.isAvailable(),
    );

    applyPostLoadMaxZoom();

    if (selectedLayer.value) {
        await applyTerrainAndHillshade(selectedLayer.value);
    }

    if (terrainEnabled.value && map.value) {
        map.value.setPitch(50);
    }

    const hasIpHint = !!mapGeolocation.userLocation.value;
    featureData.syncPendingExtentFitWithoutGeolocation(!hasIpHint && !skipUrlDrivenCamera && !isMapshareRoute.value);

    await loadRouteData();

    mapSession.markBooted();
    isMapInitializing.value = false;
    ensureMapResize();
    featureData.updateFeaturesInExtent();
    logMapState();
});

onActivated(() => {
    if (!mapSession.booted) return;
    void handleKeepAliveActivate();
});

onDeactivated(() => {
    cleanupOnNavigateAway();
});

onBeforeUnmount(() => {
    window.removeEventListener('resize', syncIsMobile);
    if (handleKeyDown) {
        window.removeEventListener('keydown', handleKeyDown);
    }
    mapGeolocation.cleanup();
    performMapDestruction();
    mapSession.dispose();
});

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

watch(
    () => [route.path, route.query.id, route.query.collection, route.query.tag, route.query.featureId, route.query.match_mode],
    async () => {
        if (!mapSession.booted || !mapSession.active) return;
        const changed = await mapSession.onRouteChange(routeLocation());
        if (!changed) return;
        resetUiForRoute();
        featureData.resetFeatureState();
        resetShareForRoute();
        await loadRouteData();
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

/**
 * Bbox-based viewport data loading: builds the unified "load context" (default view, collection,
 * or one of the public-share modes), fetches features for it through the API service layer,
 * merges them into the MapLibre source (with the `addFeaturesToMap` change-detection perf fix),
 * and maintains the feature cache used by the sidebar feature list, feature count, and cleanup
 * of features that have scrolled far outside the viewport.
 */
import { markRaw, ref, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { getCoordinatesFromGeometry, filterFeaturesByBounds, cleanupDistantFeatures as cleanupDistantFeaturesUtil } from '@/utils/map/featureExtent.js';
import { convertMapLibreFeature, type ConvertibleMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import { canonicalFeatureId, isSyntheticFeature } from '@/utils/map/common/featureIdentity';
import type { MapSession } from '@/utils/map/session/MapSession';
import { getExtentHint } from '@/api/services/featuresApi';
import { ApiError, isAbortError } from '@/utils/apiError';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import type { MapPageFeature, MapUserSettings } from './mapPageTypes';
import type { FeatureSource } from '@/utils/map/common/FeatureSource';
import type { LoadPipeline } from '@/utils/map/session/LoadPipeline';
import type { HiddenFeatureSet } from '@/utils/map/session/HiddenFeatureSet';
import type { ElevationStore } from '@/utils/map/session/ElevationStore';
import type { LoadContext as SessionLoadContext } from '@/utils/map/session/types';
import type { VaultFeature } from '@/contracts/feature';

export interface UseFeatureDataDeps {
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    showAllLabels: Ref<boolean>;
    isMapInitializing: Ref<boolean>;
    waitForMapEvent: (eventName: string, timeout?: number) => Promise<void>;
    getUserMapSettings: () => MapUserSettings;
    getSessionLoadContext: () => SessionLoadContext;
    ensurePublicShareInfo: (signal?: AbortSignal) => Promise<boolean>;
    handlePublicShareError: (message: string) => void;
    /** Re-applies hover/selection highlight paint properties after the source data changes. */
    onAfterFeaturesChanged: () => void;
    /** For `share_feature` loads: zoom to and select the single shared feature. */
    onFeatureShareLoaded: (feature: MapPageFeature) => Promise<void>;
    zoomToTaggedFeatures: (features: MapPageFeature[], options?: { padding?: number; duration?: number }) => Promise<void> | void;
    publicShareRefinedFitShareId: Ref<string | null>;
    /** Any public mapshare URL - the extent hint (aggregate main-map-only) does not apply there. */
    isMapshareRoute: ComputedRef<boolean>;
    featureSource: FeatureSource;
    loadPipeline: LoadPipeline;
    hiddenFeatures: HiddenFeatureSet;
    elevations: ElevationStore;
    session: MapSession;
}

export function useFeatureData(deps: UseFeatureDataDeps) {
    const {
        map,
        labelMarkerManager,
        showAllLabels,
        isMapInitializing,
        waitForMapEvent,
        getSessionLoadContext,
        ensurePublicShareInfo,
        handlePublicShareError,
        onAfterFeaturesChanged,
        onFeatureShareLoaded,
        zoomToTaggedFeatures,
        publicShareRefinedFitShareId,
        isMapshareRoute,
        featureSource,
        loadPipeline,
        hiddenFeatures,
        elevations,
        session,
    } = deps;

    const isDataLoading = ref(false);
    const isInitialLoad = ref(true);
    const loadError: Ref<string | null> = ref(null);
    const featuresInExtent: Ref<MapPageFeature[]> = ref([]);
    const featureCount = ref(0);
    const loadedBounds = new Set<string>();

    let currentAbortController: AbortController | null = null;
    let loadTimeout: ReturnType<typeof setTimeout> | null = null;
    let featureListUpdateTimeout: ReturnType<typeof setTimeout> | null = null;
    let featureCleanupTimeout: ReturnType<typeof setTimeout> | null = null;
    let featureCountUpdatePending = false;

    /** One-shot: main map, no URL-driven camera, geolocation unavailable - fit to first default bbox features. */
    const pendingExtentFitWithoutGeolocation = ref(false);
    /** One-shot: after empty first bbox, fetch server extent hint and recenter (main map only). */
    const mainMapExtentHintRequested = ref(false);

    function hasLoadedBounds(): boolean {
        return loadedBounds.size > 0;
    }

    function cancelPendingBboxQuery(): void {
        if (loadTimeout) {
            clearTimeout(loadTimeout);
            loadTimeout = null;
        }
    }

    function getCachedSourceData(): { data?: GeoJsonFeatureCollection } | null {
        if (!map.value?.getSource('geojson-data')) {
            return null;
        }
        return { data: featureSource.buildRenderCollection(hiddenFeatures) };
    }

    function invalidateSourceCache(): void {}

    function debouncedLoadData(): void {
        if (isMapInitializing.value) return;

        if (loadTimeout) clearTimeout(loadTimeout);
        loadTimeout = setTimeout(() => {
            void loadDataForCurrentView();
        }, 500);
    }

    function updateFeatureCount(): void {
        if (featureCountUpdatePending) return;
        featureCountUpdatePending = true;

        void Promise.resolve().then(() => {
            featureCount.value = featureSource.size();
            featureCountUpdatePending = false;
        });
    }

    function updateFeaturesInExtent(): void {
        if (!map.value?.getSource('geojson-data')) {
            featuresInExtent.value = [];
            return;
        }

        const bounds = map.value.getBounds();
        const serialized = getCachedSourceData();
        if (!serialized) {
            featuresInExtent.value = [];
            return;
        }

        const data = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
        const features = data.features;

        const featuresInBounds = filterFeaturesByBounds(features, bounds, true, true) as MapPageFeature[];
        featuresInExtent.value = featuresInBounds.map((f) => markRaw(convertMapLibreFeature(f)) as MapPageFeature);

        debouncedCleanupDistantFeatures();
    }

    function debouncedUpdateFeaturesInExtent(): void {
        if (featureListUpdateTimeout) clearTimeout(featureListUpdateTimeout);

        const run = () => {
            if (typeof window.requestIdleCallback === 'function') {
                window.requestIdleCallback(() => { updateFeaturesInExtent(); }, { timeout: 1000 });
            } else {
                updateFeaturesInExtent();
            }
        };

        featureListUpdateTimeout = setTimeout(run, 800);
    }

    function cleanupDistantFeatures(): void {
        if (!map.value?.getSource('geojson-data')) return;

        const bounds = map.value.getBounds();
        const serialized = getCachedSourceData();
        if (!serialized) return;

        const data = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
        const features = data.features;

        const { filteredFeatures: featuresWithinBuffer, removedCount } = cleanupDistantFeaturesUtil(features, bounds, getCoordinatesFromGeometry, 3000) as {
            filteredFeatures: MapPageFeature[];
            removedCount: number;
        };

        if (removedCount > 0) {
            const keepIds = new Set(
                featuresWithinBuffer
                    .map((feature) => canonicalFeatureId(feature as VaultFeature))
                    .filter((id): id is string => !!id),
            );
            for (const id of featureSource.ids()) {
                if (!keepIds.has(id)) {
                    featureSource.remove(id);
                }
            }
            featureSource.commit(hiddenFeatures);

            invalidateSourceCache();
            updateFeatureCount();

            if (showAllLabels.value && labelMarkerManager.value) {
                labelMarkerManager.value.updateMarkers(featuresWithinBuffer);
            }
        }
    }

    function debouncedCleanupDistantFeatures(): void {
        if (featureCleanupTimeout) clearTimeout(featureCleanupTimeout);
        featureCleanupTimeout = setTimeout(() => { cleanupDistantFeatures(); }, 2000);
    }

    function debouncedUpdateSmallFeatureFlags(): void {
        session.refreshZoomDependent();
    }

    async function reprocessFeaturesForZoom(): Promise<void> {
        session.refreshZoomDependent();
    }

    function handleZoomUpdate(): void {
        session.labels?.sync(true);
    }

    async function addFeaturesToMap(geojsonData: GeoJsonFeatureCollection): Promise<void> {
        if (!map.value?.getSource('geojson-data')) return;
        const incoming = (geojsonData.features ?? []) as VaultFeature[];
        for (const feature of incoming) {
            elevations.capture(feature);
        }
        session.ingestIncoming(incoming);
        invalidateSourceCache();
        onAfterFeaturesChanged();
    }

    async function applyMainMapExtentHintFromServer(navigateAndRefresh: (fn: () => void) => Promise<void>): Promise<void> {
        if (!map.value || isMapshareRoute.value) {
            pendingExtentFitWithoutGeolocation.value = false;
            return;
        }
        try {
            const payload = await getExtentHint();
            const bbox = payload.bbox;
            if (!Array.isArray(bbox)) {
                pendingExtentFitWithoutGeolocation.value = false;
                return;
            }
            const [w, s, e, n] = bbox.map(Number);
            if (![w, s, e, n].every((v) => Number.isFinite(v))) {
                pendingExtentFitWithoutGeolocation.value = false;
                return;
            }
            const maplibregl = getLoadedMaplibreGl();
            await navigateAndRefresh(() => {
                const bounds = new maplibregl.LngLatBounds([w, s], [e, n]);
                map.value?.fitBounds(bounds, { padding: 40, duration: 0, maxZoom: 2 });
            });
        } catch (error) {
            console.error('applyMainMapExtentHintFromServer:', error);
            pendingExtentFitWithoutGeolocation.value = false;
        }
    }

    function syncPendingExtentFitWithoutGeolocation(shouldFit: boolean): void {
        pendingExtentFitWithoutGeolocation.value = shouldFit;
    }

    function handleLoadError(message: string, context: SessionLoadContext): void {
        if (context.isPublicShare) {
            handlePublicShareError(message);
        } else {
            loadError.value = message;
        }
    }

    async function handleLoadSuccess(features: VaultFeature[], context: SessionLoadContext, firstReplaceLoad: boolean): Promise<void> {
        updateFeatureCount();

        const rawData = markRaw({ type: 'FeatureCollection', features }) as GeoJsonFeatureCollection;
        await addFeaturesToMap(rawData);

        if (context.kind === 'share' && context.shareType === 'feature' && features.length > 0) {
            const feature = markRaw(convertMapLibreFeature(features[0] as unknown as ConvertibleMapLibreFeature)) as MapPageFeature;
            await onFeatureShareLoaded(feature);
        }

        const shouldFitShare =
            context.kind === 'share' &&
            (context.shareType === 'tag' || context.shareType === 'collection') &&
            publicShareRefinedFitShareId.value !== context.shareId;
        const shouldFitScoped = firstReplaceLoad && (context.kind === 'tag' || context.kind === 'collection');

        if (shouldFitShare || shouldFitScoped) {
            const usable = features.filter((f) => !isSyntheticFeature(f)) as MapPageFeature[];
            if (shouldFitShare) {
                publicShareRefinedFitShareId.value = context.shareId;
            }
            if (usable.length > 0) {
                await waitForMapEvent('idle');
                await zoomToTaggedFeatures(usable, { padding: shouldFitShare ? 28 : 50, duration: 0 });
            }
        }

        if (pendingExtentFitWithoutGeolocation.value && context.kind === 'main') {
            const usable = features.filter((f) => !isSyntheticFeature(f)) as MapPageFeature[];
            if (usable.length > 0) {
                pendingExtentFitWithoutGeolocation.value = false;
                await waitForMapEvent('idle');
                await zoomToTaggedFeatures(usable, { padding: 50, duration: 0 });
            } else if (!mainMapExtentHintRequested.value) {
                mainMapExtentHintRequested.value = true;
                await applyMainMapExtentHintFromServer(navigateAndRefresh);
            } else {
                pendingExtentFitWithoutGeolocation.value = false;
            }
        }

        debouncedUpdateFeaturesInExtent();
    }

    async function loadDataForCurrentView(options: { force?: boolean } = {}): Promise<void> {
        if (!map.value) return;
        const startedGeneration = loadPipeline.generation;

        try {
            let bounds;
            try {
                bounds = map.value.getBounds();
            } catch {
                return;
            }

            loadError.value = null;

            try {
                if (isMapshareRoute.value) {
                    isDataLoading.value = true;
                    const shareInfoLoaded = await ensurePublicShareInfo();
                    if (!shareInfoLoaded) return;
                }

                const context = getSessionLoadContext();
                if (!map.value) return;
                bounds = map.value.getBounds();
                const zoom = map.value.getZoom();
                const viewportBbox: [number, number, number, number] = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()];
                const firstReplaceLoad = context.replaceSource && loadedBounds.size === 0;
                const bboxForApi: [number, number, number, number] =
                    context.spatial === 'global' || firstReplaceLoad
                        ? [-180, -85.05112878, 180, 85.05112878]
                        : viewportBbox;
                const bboxKey = bboxForApi.map((value) => value.toFixed(4)).join(',');

                isDataLoading.value = true;
                const result = await loadPipeline.load({
                    context,
                    bbox: bboxForApi,
                    zoom,
                    replaceSource: context.replaceSource,
                    force: options.force,
                });
                if (!result) return;

                if (context.kind !== 'featureFocus' && !(context.kind === 'share' && context.shareType === 'feature')) {
                    loadedBounds.add(bboxKey);
                }

                await handleLoadSuccess(result.features, context, firstReplaceLoad);
            } catch (error) {
                if (isAbortError(error)) return;
                console.error('Error loading data:', error);
                let context: SessionLoadContext;
                try {
                    context = getSessionLoadContext();
                } catch {
                    loadError.value = error instanceof ApiError ? error.message : error instanceof Error ? error.message : 'Failed to load map data.';
                    return;
                }
                const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : 'Failed to load map data.';
                handleLoadError(message, context);
            }
        } finally {
            if (loadPipeline.generation === startedGeneration || loadPipeline.generation === startedGeneration + 1) {
                isDataLoading.value = false;
                if (isInitialLoad.value) {
                    isInitialLoad.value = false;
                }
            }
        }
    }

    /**
     * Perform a camera move then re-run `loadDataForCurrentView` once movement settles (or after a
     * 300ms fallback, since instant moves like `fitBounds({ duration: 0 })` often omit `moveend`).
     */
    async function navigateAndRefresh(navigationFn: () => void, clearAllBounds = true): Promise<void> {
        if (!map.value) return;

        if (clearAllBounds) {
            loadedBounds.clear();
        }

        navigationFn();

        return new Promise((resolve) => {
            let settled = false;
            let fallbackId: number | null = null;

            const finishAfterMove = () => {
                if (settled || !map.value) return;
                settled = true;
                map.value.off('moveend', onMoveEnd);
                if (fallbackId != null) {
                    window.clearTimeout(fallbackId);
                    fallbackId = null;
                }

                const currentZoom = map.value.getZoom();
                if (currentZoom > 18) {
                    map.value.setZoom(18);
                }

                void loadDataForCurrentView();
                resolve();
            };

            const onMoveEnd = () => { finishAfterMove(); };

            map.value?.on('moveend', onMoveEnd);

            fallbackId = window.setTimeout(() => {
                finishAfterMove();
            }, 300);
        });
    }

    /** Cancel in-flight requests and pending timers; used before destroying the map on navigate-away. */
    function cancelPendingRequests(): void {
        loadPipeline.cancel();
        if (currentAbortController) {
            currentAbortController.abort();
            currentAbortController = null;
        }
        if (loadTimeout) {
            clearTimeout(loadTimeout);
            loadTimeout = null;
        }
        if (featureListUpdateTimeout) {
            clearTimeout(featureListUpdateTimeout);
            featureListUpdateTimeout = null;
        }
        if (featureCleanupTimeout) {
            clearTimeout(featureCleanupTimeout);
            featureCleanupTimeout = null;
        }
    }

    /** Reset feature-list/cache state; used on navigate-away and before loading a new collection/tag scope. */
    function resetFeatureState(): void {
        loadedBounds.clear();
        featureSource.clear();
        featureSource.commit(hiddenFeatures);
        featuresInExtent.value = [];
        featureCount.value = 0;
        featureCountUpdatePending = false;
        invalidateSourceCache();
    }

    return {
        isDataLoading,
        isInitialLoad,
        loadError,
        featuresInExtent,
        featureCount,
        pendingExtentFitWithoutGeolocation,
        mainMapExtentHintRequested,
        hasLoadedBounds,
        clearLoadedBounds: () => { loadedBounds.clear(); },
        cancelPendingBboxQuery,
        getCachedSourceData,
        invalidateSourceCache,
        updateFeatureCount,
        updateFeaturesInExtent,
        debouncedUpdateFeaturesInExtent,
        debouncedLoadData,
        debouncedUpdateSmallFeatureFlags,
        cleanupDistantFeatures,
        debouncedCleanupDistantFeatures,
        reprocessFeaturesForZoom,
        handleZoomUpdate,
        addFeaturesToMap,
        loadDataForCurrentView,
        navigateAndRefresh,
        syncPendingExtentFitWithoutGeolocation,
        applyMainMapExtentHintFromServer: () => applyMainMapExtentHintFromServer(navigateAndRefresh),
        cancelPendingRequests,
        resetFeatureState,
    };
}

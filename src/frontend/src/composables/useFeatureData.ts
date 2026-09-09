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
import { addFeaturesToMap as addFeaturesToMapUtil, updateSmallFeatureFlags } from '@/utils/map/maplibre';
import { getCoordinatesFromGeometry, filterFeaturesByBounds, cleanupDistantFeatures as cleanupDistantFeaturesUtil } from '@/utils/map/featureExtent.js';
import { convertMapLibreFeature, type ConvertibleMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import { canonicalFeatureId, isSyntheticFeature } from '@/utils/map/common/featureIdentity';
import { getFeatureIconUrl, getIconSourceUrl, loadIconImage } from '@/utils/map/maplibre/featureStyling.js';
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
import type { MapFeature } from '@/utils/map/maplibre/mapFeatureTypes';

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
}

export function useFeatureData(deps: UseFeatureDataDeps) {
    const {
        map,
        labelMarkerManager,
        showAllLabels,
        isMapInitializing,
        waitForMapEvent,
        getUserMapSettings,
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
    } = deps;

    const isDataLoading = ref(false);
    const isInitialLoad = ref(true);
    const loadError: Ref<string | null> = ref(null);
    const featuresInExtent: Ref<MapPageFeature[]> = ref([]);
    const featureCount = ref(0);
    const loadedBounds = new Set<string>();

    let lastProcessedZoom: number | null = null;
    let lastLabelUpdateZoom = 0;
    let lastIconVisibilityZoom: number | null = null;

    let currentAbortController: AbortController | null = null;
    let loadTimeout: ReturnType<typeof setTimeout> | null = null;
    let featureListUpdateTimeout: ReturnType<typeof setTimeout> | null = null;
    let featureCleanupTimeout: ReturnType<typeof setTimeout> | null = null;
    let smallFeatureFlagsUpdateTimeout: ReturnType<typeof setTimeout> | null = null;
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

    function invalidateSourceCache(): void {
        lastProcessedZoom = null;
    }

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
        if (smallFeatureFlagsUpdateTimeout) clearTimeout(smallFeatureFlagsUpdateTimeout);
        if (map.value === null) return;

        smallFeatureFlagsUpdateTimeout = setTimeout(() => {
            const mapInstance = map.value;
            if (!mapInstance?.getSource('geojson-data')) return;

            const zoom = mapInstance.getZoom();
            const run = () => {
                updateSmallFeatureFlags(mapInstance, zoom, featureSource, hiddenFeatures);
                invalidateSourceCache();
            };

            if (typeof window.requestIdleCallback === 'function') {
                window.requestIdleCallback(run, { timeout: 2000 });
            } else {
                run();
            }
        }, 1000);
    }

    function updateIconVisibilityDuringZoom(currentZoom: number): void {
        const ICON_THRESHOLD = 8;
        const userSettings = getUserMapSettings();
        const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;

        if (!replaceIconsLowZoom) {
            lastIconVisibilityZoom = currentZoom;
            return;
        }

        const shouldHideIcons = currentZoom <= ICON_THRESHOLD;
        const wasAboveThreshold = lastIconVisibilityZoom === null || lastIconVisibilityZoom > ICON_THRESHOLD;

        if (map.value?.getLayer('point-icons')) {
            const currentVisibility = map.value.getLayoutProperty('point-icons', 'visibility') as string | undefined;
            const targetVisibility = shouldHideIcons ? 'none' : 'visible';
            if (currentVisibility !== targetVisibility) {
                map.value.setLayoutProperty('point-icons', 'visibility', targetVisibility);
            }
        }

        if (shouldHideIcons && wasAboveThreshold) {
            const serialized = getCachedSourceData();
            if (serialized?.data?.features) {
                const features = serialized.data.features as MapPageFeature[];
                let needsUpdate = false;

                for (const feature of features) {
                    if (feature.properties._isLabelPoint || feature.properties._isSmallFeatureReplacement) continue;
                    if (feature.geometry.type === 'Point' && feature.properties['_icon-id']) {
                        delete feature.properties['_icon-id'];
                        needsUpdate = true;
                    }
                }

                if (needsUpdate && map.value) {
                    for (const feature of features) {
                        const id = canonicalFeatureId(feature);
                        if (!id) continue;
                        featureSource.setRuntime(id, { iconId: typeof feature.properties['_icon-id'] === 'string' ? feature.properties['_icon-id'] : undefined });
                    }
                    featureSource.commit(hiddenFeatures);
                    invalidateSourceCache();
                }
            }
        }

        lastIconVisibilityZoom = currentZoom;
    }

    async function reprocessFeaturesForZoom(): Promise<void> {
        if (!map.value?.getSource('geojson-data')) return;

        const zoom = map.value.getZoom();

        if (lastProcessedZoom !== null && Math.abs(zoom - lastProcessedZoom) < 0.5) {
            return;
        }

        const serialized = getCachedSourceData();
        if (!serialized) return;

        const currentData = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
        const features = currentData.features as MapPageFeature[];
        if (features.length === 0) return;

        lastProcessedZoom = zoom;
        const userSettings = getUserMapSettings();
        const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;

        let needsUpdate = false;

        for (const feature of features) {
            if (feature.properties._isLabelPoint || feature.properties._isSmallFeatureReplacement) continue;
            if (feature.geometry.type !== 'Point') continue;

            const iconUrl = getFeatureIconUrl(feature.properties);
            const hasIcon = !!iconUrl && iconUrl.trim() !== '';
            const shouldShowIcon = hasIcon && (!replaceIconsLowZoom || zoom > 8);

            if (shouldShowIcon) {
                if (!feature.properties['_icon-id']) {
                    const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties);
                    const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`;
                    feature.properties['_icon-id'] = iconId;
                    needsUpdate = true;

                    if (!map.value.hasImage(iconId)) {
                        loadIconImage(map.value, iconId, resolvedUrl).catch((err: unknown) => {
                            console.warn(`Failed to load icon ${iconId}:`, err);
                        });
                    }
                }
            } else if (feature.properties['_icon-id']) {
                delete feature.properties['_icon-id'];
                needsUpdate = true;
            }
        }

        if (needsUpdate) {
            for (const feature of features) {
                const id = canonicalFeatureId(feature);
                if (!id) continue;
                featureSource.setRuntime(id, { iconId: typeof feature.properties['_icon-id'] === 'string' ? feature.properties['_icon-id'] : undefined });
            }
            featureSource.commit(hiddenFeatures);
            invalidateSourceCache();
        }

        if (map.value.getLayer('point-icons')) {
            const shouldShowIcons = !replaceIconsLowZoom || zoom > 8;
            const currentVisibility = map.value.getLayoutProperty('point-icons', 'visibility') as string | undefined;
            const targetVisibility = shouldShowIcons ? 'visible' : 'none';
            if (currentVisibility !== targetVisibility) {
                map.value.setLayoutProperty('point-icons', 'visibility', targetVisibility);
            }
        }
    }

    /** RAF-batched zoom handler: lightweight label/icon-visibility upkeep only (heavy work is debounced on zoomend). */
    function handleZoomUpdate(): void {
        if (!map.value) return;

        const currentZoom = map.value.getZoom();

        if (showAllLabels.value && labelMarkerManager.value) {
            const currentZoomInt = Math.floor(currentZoom);
            const lastZoomInt = Math.floor(lastLabelUpdateZoom);

            if (currentZoomInt !== lastZoomInt) {
                const serialized = getCachedSourceData();
                if (serialized?.data?.features) {
                    labelMarkerManager.value.updateMarkers(serialized.data.features, true);
                    lastLabelUpdateZoom = currentZoom;
                }
            }
        }

        updateIconVisibilityDuringZoom(currentZoom);
    }

    /**
     * Merge features into the map source (via `addFeaturesToMap`'s change-detection perf path),
     * refresh the persistent cache + label markers, and re-apply highlight paint properties.
     *
     * Perf: `addFeaturesToMapUtil` already returns the in-memory merged `FeatureCollection` it
     * just built (or skipped rebuilding, if nothing changed) - reuse that directly instead of an
     * extra FeatureSource rebuild to re-derive the same data.
     */
    async function addFeaturesToMap(geojsonData: GeoJsonFeatureCollection): Promise<void> {
        if (!map.value?.getSource('geojson-data')) return;

        const zoom = map.value.getZoom();
        const userSettings = getUserMapSettings();
        const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;
        const incoming = (geojsonData.features ?? []) as VaultFeature[];
        for (const feature of incoming) {
            elevations.capture(feature);
        }
        featureSource.upsert(incoming, hiddenFeatures);
        const existing = featureSource.buildRenderCollection(hiddenFeatures).features as MapFeature[];

        const mergedCollection = await addFeaturesToMapUtil(map.value, geojsonData, showAllLabels.value, zoom, replaceIconsLowZoom, {
            existingFeatures: existing,
            hiddenIds: new Set(hiddenFeatures.values()),
            featureSource,
            hidden: hiddenFeatures,
        });

        invalidateSourceCache();
        onAfterFeaturesChanged();

        if (mergedCollection?.features) {
            if (showAllLabels.value && labelMarkerManager.value) {
                labelMarkerManager.value.updateMarkers(mergedCollection.features);
            }
        }
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

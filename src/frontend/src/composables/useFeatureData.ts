/**
 * Bbox-based viewport data loading: builds the unified "load context" (default view, collection,
 * or one of the public-share modes), fetches features for it through the API service layer,
 * merges them into the MapLibre source (with the `addFeaturesToMap` change-detection perf fix),
 * and maintains the feature cache used by the sidebar feature list, feature count, and cleanup
 * of features that have scrolled far outside the viewport.
 */
import { markRaw, ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import type { Map as MapLibreMap, GeoJSONSource } from 'maplibre-gl';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { addFeaturesToMap as addFeaturesToMapUtil, updateSmallFeatureFlags, getBoundingBoxKey, getBoundingBoxString } from '@/utils/map/maplibre';
import { getCoordinatesFromGeometry, filterFeaturesByBounds, cleanupDistantFeatures as cleanupDistantFeaturesUtil } from '@/utils/map/featureExtent.js';
import { convertMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import { getFeatureIconUrl, getIconSourceUrl, loadIconImage } from '@/utils/map/maplibre/featureStyling.js';
import { getFeaturesInBbox, getExtentHint } from '@/api/services/featuresApi';
import { getPublicShareTagFeatures, getPublicShareCollectionFeatures, getPublicShareFeature } from '@/api/services/sharingApi';
import { ApiError, isAbortError } from '@/utils/apiError';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import type { LoadContext, MapPageFeature, MapUserSettings } from './mapPageTypes';

export interface UseFeatureDataDeps {
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    showAllLabels: Ref<boolean>;
    isMapInitializing: Ref<boolean>;
    waitForMapEvent: (eventName: string, timeout?: number) => Promise<void>;
    getUserMapSettings: () => MapUserSettings;
    /** Builds the current load context (default/collection/share_*) from route + share/tag state. */
    getLoadContext: () => LoadContext;
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
}

export function useFeatureData(deps: UseFeatureDataDeps) {
    const {
        map,
        labelMarkerManager,
        showAllLabels,
        isMapInitializing,
        waitForMapEvent,
        getUserMapSettings,
        getLoadContext,
        ensurePublicShareInfo,
        handlePublicShareError,
        onAfterFeaturesChanged,
        onFeatureShareLoaded,
        zoomToTaggedFeatures,
        publicShareRefinedFitShareId,
        isMapshareRoute,
    } = deps;

    const isDataLoading = ref(false);
    const isInitialLoad = ref(true);
    const loadError: Ref<string | null> = ref(null);
    const featuresInExtent: Ref<MapPageFeature[]> = ref([]);
    const featureCount = ref(0);
    const loadedBounds = new Set<string>();

    /** Persistent cache of GeoJSON features that survives `setStyle()` calls. */
    const cachedGeoJsonData: ShallowRef<GeoJsonFeatureCollection | null> = shallowRef(null);
    let cachedSerializedData: { data?: GeoJsonFeatureCollection } | null = null;
    let lastSerializedZoom: number | null = null;
    let localFeaturesCache: MapPageFeature[] | null = null;
    let lastCacheZoom: number | null = null;
    let lastCacheUpdateTime: number | null = null;
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

        const source = map.value.getSource('geojson-data');
        const currentZoom = map.value.getZoom();
        const now = Date.now();

        if (
            localFeaturesCache &&
            lastCacheZoom !== null &&
            Math.abs(currentZoom - lastCacheZoom) < 0.2 &&
            lastCacheUpdateTime !== null &&
            now - lastCacheUpdateTime < 5000
        ) {
            return { data: { type: 'FeatureCollection', features: localFeaturesCache } };
        }

        if (cachedSerializedData && lastSerializedZoom !== null && Math.abs(currentZoom - lastSerializedZoom) < 0.1) {
            if (cachedSerializedData.data?.features) {
                localFeaturesCache = cachedSerializedData.data.features as MapPageFeature[];
                lastCacheZoom = currentZoom;
                lastCacheUpdateTime = now;
            }
            return cachedSerializedData;
        }

        const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
        cachedSerializedData = serialized;
        lastSerializedZoom = currentZoom;

        if (serialized.data?.features) {
            localFeaturesCache = serialized.data.features as MapPageFeature[];
            lastCacheZoom = currentZoom;
            lastCacheUpdateTime = now;
        }

        return serialized;
    }

    function invalidateSourceCache(): void {
        cachedSerializedData = null;
        lastSerializedZoom = null;
        localFeaturesCache = null;
        lastCacheZoom = null;
        lastCacheUpdateTime = null;
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
            if (map.value?.getSource('geojson-data')) {
                const source = map.value.getSource('geojson-data');
                const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
                const data = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
                const realFeatures = data.features.filter((f) => !f.properties._isLabelPoint);
                featureCount.value = realFeatures.length;
            }
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

        if (features.length > 0) {
            try {
                cachedGeoJsonData.value = markRaw({
                    type: 'FeatureCollection',
                    features: features.map((f) => markRaw(f)),
                }) as GeoJsonFeatureCollection;
            } catch (error) {
                console.warn('Failed to update cached GeoJSON data:', error);
            }
        }

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
            console.log(`Cleaned up ${removedCount} features more than 500 miles outside viewport`);

            const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
            const filteredFeatures = featuresWithinBuffer.map((f) => markRaw(f));
            const filteredCollection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: filteredFeatures };
            source?.setData(markRaw(filteredCollection));

            localFeaturesCache = filteredFeatures;
            lastCacheZoom = map.value.getZoom();
            lastCacheUpdateTime = Date.now();
            cachedSerializedData = null;
            lastSerializedZoom = null;

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
                updateSmallFeatureFlags(mapInstance, zoom);
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
                    const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
                    const updatedFeatures = features.map((f) => markRaw(f));
                    const updatedCollection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: updatedFeatures };
                    source?.setData(markRaw(updatedCollection));
                    localFeaturesCache = updatedFeatures;
                    lastCacheZoom = currentZoom;
                    lastCacheUpdateTime = Date.now();
                    cachedSerializedData = null;
                    lastSerializedZoom = null;
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
            const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
            const updatedFeatures = features.map((f) => markRaw(f));
            const updatedCollection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: updatedFeatures };
            source?.setData(markRaw(updatedCollection));
            const currentZoom = map.value.getZoom();
            localFeaturesCache = updatedFeatures;
            lastCacheZoom = currentZoom;
            lastCacheUpdateTime = Date.now();
            cachedSerializedData = null;
            lastSerializedZoom = null;
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
     * extra `source.serialize()` round trip to re-derive the same data.
     */
    async function addFeaturesToMap(geojsonData: GeoJsonFeatureCollection): Promise<void> {
        if (!map.value?.getSource('geojson-data')) return;

        const zoom = map.value.getZoom();
        const userSettings = getUserMapSettings();
        const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;

        const mergedCollection = await addFeaturesToMapUtil(map.value, geojsonData, showAllLabels.value, zoom, replaceIconsLowZoom);

        invalidateSourceCache();
        onAfterFeaturesChanged();

        if (mergedCollection?.features) {
            try {
                cachedGeoJsonData.value = markRaw({
                    type: 'FeatureCollection',
                    features: mergedCollection.features.map((f) => markRaw(f)),
                }) as GeoJsonFeatureCollection;
            } catch (error) {
                console.warn('Failed to update cached GeoJSON data:', error);
            }

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

    async function fetchLoadData(context: LoadContext, bboxString: string, zoom: number, signal: AbortSignal): Promise<{ data: GeoJsonFeatureCollection } | null> {
        switch (context.type) {
            case 'share_tag':
                return context.shareId ? await getPublicShareTagFeatures(context.shareId, bboxString, zoom, signal) : null;
            case 'share_collection':
                return context.shareId ? await getPublicShareCollectionFeatures(context.shareId, bboxString, zoom, signal) : null;
            case 'share_feature': {
                if (!isInitialLoad.value || !context.shareId) return null;
                const result = await getPublicShareFeature(context.shareId, signal);
                return { data: { type: 'FeatureCollection', features: result.features } };
            }
            case 'share_unknown':
                console.warn('Attempting to build URL for unknown share type. Share info should be loaded first.');
                return null;
            case 'collection':
                return await getFeaturesInBbox({
                    bbox: bboxString,
                    zoom,
                    collection: context.collectionId,
                    tags: context.tags,
                    matchMode: context.matchMode,
                    signal,
                });
            case 'default':
                return await getFeaturesInBbox({
                    bbox: bboxString,
                    zoom,
                    tags: context.tags,
                    matchMode: context.matchMode,
                    signal,
                });
            default:
                console.error('Unknown load context type:', context.type);
                return null;
        }
    }

    function handleLoadError(message: string, context: LoadContext): void {
        if (context.isPublicShare) {
            handlePublicShareError(message);
        } else {
            loadError.value = message;
        }
    }

    async function handleLoadSuccess(data: { data: GeoJsonFeatureCollection }, context: LoadContext, bboxKey: string): Promise<void> {
        if (!Array.isArray(data.data.features)) {
            data.data.features = [];
        }

        if (context.type !== 'share_feature' && bboxKey) {
            loadedBounds.add(bboxKey);
        }

        updateFeatureCount();

        const rawData = markRaw(data.data) as GeoJsonFeatureCollection;
        await addFeaturesToMap(rawData);

        if (context.type === 'share_feature' && data.data.features.length > 0) {
            const feature = markRaw(convertMapLibreFeature(data.data.features[0])) as MapPageFeature;
            await onFeatureShareLoaded(feature);
        }

        if (
            context.isPublicShare &&
            context.shareId &&
            (context.type === 'share_tag' || context.type === 'share_collection') &&
            publicShareRefinedFitShareId.value !== context.shareId
        ) {
            const usable = data.data.features.filter((f) => !f.properties._isLabelPoint && !f.properties._isSmallFeatureReplacement) as MapPageFeature[];
            if (usable.length > 0) {
                publicShareRefinedFitShareId.value = context.shareId;
                await waitForMapEvent('idle');
                await zoomToTaggedFeatures(usable, { padding: 28, duration: 0 });
            }
        }

        if (pendingExtentFitWithoutGeolocation.value && context.type === 'default' && !context.isPublicShare) {
            const usable = data.data.features.filter((f) => !f.properties._isLabelPoint && !f.properties._isSmallFeatureReplacement) as MapPageFeature[];
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

    /** Unified data-loading entry point; handles default view, collection, and all public-share modes. */
    async function loadDataForCurrentView(): Promise<void> {
        if (!map.value) return;

        try {
            let bounds;
            try {
                bounds = map.value.getBounds();
            } catch {
                return;
            }

            if (currentAbortController) {
                currentAbortController.abort();
            }
            currentAbortController = new AbortController();
            loadError.value = null;

            try {
                let context = getLoadContext();

                if (context.isPublicShare) {
                    isDataLoading.value = true;
                    const shareInfoLoaded = await ensurePublicShareInfo(currentAbortController.signal);
                    if (!shareInfoLoaded) return;
                    context = getLoadContext();

                    if (!context.shareInfo || context.type === 'share_unknown') {
                        console.error('Share info not properly loaded after ensurePublicShareInfo', { context });
                        handlePublicShareError('Failed to load share information');
                        return;
                    }
                }

                // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- map.value can become null here if the component is deactivated (map destroyed) while ensurePublicShareInfo() above was in flight
                if (!map.value) return;
                bounds = map.value.getBounds();
                const zoom = map.value.getZoom();
                const viewportBbox: [number, number, number, number] = [bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()];

                const isFirstPublicBboxShareLoad =
                    !!context.shareId && (context.type === 'share_tag' || context.type === 'share_collection') && publicShareRefinedFitShareId.value !== context.shareId;
                const bboxForApi: [number, number, number, number] = isFirstPublicBboxShareLoad ? [-180, -85.05112878, 180, 85.05112878] : viewportBbox;

                let bboxKey = getBoundingBoxKey(bboxForApi, zoom);
                if (context.isPublicShare && context.shareId) {
                    bboxKey = `${bboxKey}_share_${context.shareId}`;
                } else if (context.type === 'collection' && context.collectionId) {
                    bboxKey = `${bboxKey}_collection_${context.collectionId}`;
                } else if (context.tags && context.tags.length > 0) {
                    const sortedTags = [...context.tags].sort();
                    const tagsKey = sortedTags.map((tag) => encodeURIComponent(tag)).join('_');
                    bboxKey = `${bboxKey}_tags_${tagsKey}`;
                }

                if (context.type !== 'share_feature' && loadedBounds.has(bboxKey)) {
                    return;
                }

                const bboxString = getBoundingBoxString(bboxForApi);

                isDataLoading.value = true;
                // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- cancelPendingRequests() (called on deactivate) can null this out while ensurePublicShareInfo() above was in flight
                if (!currentAbortController) {
                    currentAbortController = new AbortController();
                }

                const data = await fetchLoadData(context, bboxString, zoom, currentAbortController.signal);
                if (data === null) return;

                await handleLoadSuccess(data, context, bboxKey);
            } catch (error) {
                if (isAbortError(error)) return;
                console.error('Error loading data:', error);
                const context = getLoadContext();
                const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : 'Failed to load map data.';
                handleLoadError(message, context);
            }
        } finally {
            isDataLoading.value = false;
            currentAbortController = null;
            if (isInitialLoad.value) {
                isInitialLoad.value = false;
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
        featuresInExtent.value = [];
        featureCount.value = 0;
        featureCountUpdatePending = false;
        invalidateSourceCache();
        cachedGeoJsonData.value = null;
    }

    return {
        isDataLoading,
        isInitialLoad,
        loadError,
        featuresInExtent,
        featureCount,
        cachedGeoJsonData,
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

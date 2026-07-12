/**
 * Collection ("`?collection=`") and tag ("`?tag=`") filter modes: loads ALL features for the
 * collection/tag (not just the viewport) so the whole set can be zoomed-to, and owns the
 * available-tags list used by the sidebar's tag filter dropdown.
 */
import { computed, markRaw, ref, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import { useRoute } from 'vue-router';
import { useStore } from 'vuex';
import maplibregl, { type Map as MapLibreMap, type GeoJSONSource } from 'maplibre-gl';
import { getCollection, getCollectionFeatures } from '@/api/services/collectionsApi';
import { getFeature, filterFeaturesByTags } from '@/api/services/featuresApi';
import { convertMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import { toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast';
import { sortTagsByPriority, sortUserTagsAlphabetically } from '@/utils/tagUtils.js';
import { getFeatureCoordinates } from '@/utils/map/maplibre';
import { filterPointsOnBorders } from '@/utils/map/maplibre/featureFiltering.js';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import type { RootState } from '@/assets/js/store';
import type { UserInfo } from '@/assets/js/types/store-types';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import type { MapPageFeature } from './mapPageTypes';

/** Narrow view of root getters this composable reads by namespaced key. */
interface RootGetters {
    'auth/userInfo': UserInfo | null;
}

export interface UseCollectionTagFiltersDeps {
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    isDataLoading: Ref<boolean>;
    loadError: Ref<string | null>;
    featuresInExtent: Ref<MapPageFeature[]>;
    featureCount: Ref<number>;
    cachedGeoJsonData: ShallowRef<GeoJsonFeatureCollection | null>;
    selectedFeature: Ref<MapPageFeature | null>;
    navigateAndRefresh: (navigationFn: () => void, clearAllBounds?: boolean) => Promise<void>;
    addFeaturesToMap: (geojsonData: GeoJsonFeatureCollection) => Promise<void>;
    invalidateSourceCache: () => void;
    clearLoadedBounds: () => void;
    loadDataForCurrentView: () => Promise<void>;
    waitForMap: () => Promise<void>;
    waitForMapEvent: (eventName: string, timeout?: number) => Promise<void>;
    zoomToFeature: (feature: MapPageFeature) => Promise<void>;
    updateFeatureCount: () => void;
    updateFeaturesInExtent: () => void;
}

export function useCollectionTagFilters(deps: UseCollectionTagFiltersDeps) {
    const {
        map,
        labelMarkerManager,
        isDataLoading,
        loadError,
        featuresInExtent,
        featureCount,
        cachedGeoJsonData,
        selectedFeature,
        navigateAndRefresh,
        addFeaturesToMap,
        invalidateSourceCache,
        clearLoadedBounds,
        loadDataForCurrentView,
        waitForMap,
        waitForMapEvent,
        zoomToFeature,
        updateFeatureCount,
        updateFeaturesInExtent,
    } = deps;

    const route = useRoute();
    const store = useStore<RootState>();

    const collectionId = computed<string | null>(() => (route.query.collection as string | undefined) ?? null);
    const initialSelectedTags: ComputedRef<string[]> = computed(() => {
        const tag = route.query.tag;
        if (!tag) return [];
        return Array.isArray(tag) ? (tag as string[]) : [tag];
    });

    const isCollectionMode = ref(false);
    const collectionName: Ref<string | null> = ref(null);
    const isTagFilterActive = ref(false);
    const tagFilteredFeatures: Ref<MapPageFeature[]> = ref([]);
    const currentTags: Ref<string[] | null> = ref(null);
    const currentTagMatchMode: Ref<'AND' | 'OR'> = ref('AND');
    const availableTags: Ref<string[]> = ref([]);

    async function fetchAvailableTags(): Promise<void> {
        const getters = store.getters as RootGetters;
        if (!getters['auth/userInfo']) return;
        try {
            const { getFeaturesByTag } = await import('@/api/services/featuresApi');
            const data = (await getFeaturesByTag()) as { user_tags?: Record<string, unknown>; system_tags?: Record<string, unknown> };
            const userTags = data.user_tags ? Object.keys(data.user_tags) : [];
            const systemTags = data.system_tags ? Object.keys(data.system_tags) : [];

            const sortedUserTags = sortUserTagsAlphabetically(userTags);
            const sortedSystemTags = sortTagsByPriority(systemTags);

            availableTags.value = [...sortedUserTags, ...sortedSystemTags];
        } catch (error) {
            console.error('Error fetching available tags:', error);
            availableTags.value = [];
        }
    }

    /**
     * @param options.padding fitBounds padding (px) or per-side object; default 50
     * @param options.duration fitBounds/flyTo duration (ms); 0 for instant; default 500
     */
    async function zoomToTaggedFeatures(
        features: MapPageFeature[],
        options: { padding?: number | { top: number; bottom: number; left: number; right: number }; duration?: number } = {},
    ): Promise<void> {
        if (!map.value || features.length === 0) return;

        const padding = options.padding ?? 50;
        const duration = options.duration ?? 500;

        let minLon = Infinity;
        let minLat = Infinity;
        let maxLon = -Infinity;
        let maxLat = -Infinity;

        features.forEach((feature) => {
            if (!feature.geometry.coordinates) return;
            const coords = getFeatureCoordinates(feature.geometry);
            coords.forEach((coord) => {
                const [lon, lat] = Array.isArray(coord) && coord.length >= 2 ? coord : [null, null];
                if (lon != null && lat != null && isFinite(lon) && isFinite(lat)) {
                    if (lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90) {
                        minLon = Math.min(minLon, lon);
                        minLat = Math.min(minLat, lat);
                        maxLon = Math.max(maxLon, lon);
                        maxLat = Math.max(maxLat, lat);
                    }
                }
            });
        });

        if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) return;

        minLon = Math.max(-180, Math.min(180, minLon));
        minLat = Math.max(-90, Math.min(90, minLat));
        maxLon = Math.max(-180, Math.min(180, maxLon));
        maxLat = Math.max(-90, Math.min(90, maxLat));

        if (minLon === maxLon && minLat === maxLat) {
            await navigateAndRefresh(() => {
                if (duration === 0) {
                    map.value?.jumpTo({ center: [minLon, minLat], zoom: 14 });
                } else {
                    map.value?.flyTo({ center: [minLon, minLat], zoom: 14, duration });
                }
            });
            return;
        }

        const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]);

        await navigateAndRefresh(() => {
            map.value?.fitBounds(bounds, { padding, duration, maxZoom: MAX_ZOOM_LEVEL });
        });
    }

    /** Ensure the map source is fully empty before merging in an "ALL features" (non-bbox) response. */
    async function ensureSourceEmpty(): Promise<void> {
        const mapInstance = map.value;
        if (!mapInstance?.getSource('geojson-data')) return;

        const maxAttempts = 3;
        for (let attempts = 0; attempts < maxAttempts; attempts++) {
            const source: GeoJSONSource | undefined = mapInstance.getSource('geojson-data');
            const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
            const currentData = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };

            if (currentData.features.length === 0) {
                break;
            }

            source?.setData({ type: 'FeatureCollection', features: [] });
            if (attempts < maxAttempts - 1) {
                await waitForMapEvent('idle');
            }
        }
    }

    function clearMapForFilterSwitch(): void {
        const source: GeoJSONSource | undefined = map.value?.getSource('geojson-data');
        source?.setData({ type: 'FeatureCollection', features: [] });
        featuresInExtent.value = [];
        selectedFeature.value = null;
        featureCount.value = 0;
        clearLoadedBounds();

        if (labelMarkerManager.value) {
            labelMarkerManager.value.clearAllMarkers();
        }

        invalidateSourceCache();
        cachedGeoJsonData.value = null;
    }

    async function handleCollectionFilter(collectionIdParam: string | null): Promise<void> {
        if (!collectionIdParam) return;

        await waitForMap();

        isDataLoading.value = true;
        try {
            const collectionData = (await getCollection(collectionIdParam)) as { collection?: { name: string } };
            if (!collectionData.collection) {
                throw new Error('Failed to load collection info');
            }

            collectionName.value = collectionData.collection.name;
            isCollectionMode.value = true;

            clearMapForFilterSwitch();

            await waitForMapEvent('idle');

            const featuresData = (await getCollectionFeatures(collectionIdParam)) as { data?: GeoJsonFeatureCollection };
            if (!featuresData.data) {
                console.error('Collection features response missing data:', featuresData);
                throw new Error('Invalid collection features response');
            }

            const geojsonData = featuresData.data;
            if (Array.isArray(geojsonData.features)) {
                await ensureSourceEmpty();

                const rawData = markRaw(geojsonData);
                await addFeaturesToMap(rawData);

                await waitForMapEvent('idle');

                if (map.value?.getSource('geojson-data')) {
                    const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
                    const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
                    const sourceData = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
                    const features = sourceData.features.filter((f) => !f.properties._isLabelPoint && !f.properties._isSmallFeatureReplacement) as MapPageFeature[];

                    if (features.length > 0) {
                        await zoomToTaggedFeatures(features);
                    } else {
                        console.warn('Collection loaded but no features found after processing');
                    }
                }
            } else {
                console.warn('Collection has no features or invalid feature data:', geojsonData);
            }
        } catch (error) {
            console.error('Error loading collection:', error);
            toastApiError(error, 'Failed to load collection');
            collectionName.value = null;
            isCollectionMode.value = false;
            const source: GeoJSONSource | undefined = map.value?.getSource('geojson-data');
            source?.setData({ type: 'FeatureCollection', features: [] });
            clearLoadedBounds();
            await loadDataForCurrentView();
        } finally {
            isDataLoading.value = false;
        }
    }

    async function handleUrlFeatureId(featureIdParam: string | number | undefined, removeFeatureIdFromUrl: () => void): Promise<void> {
        if (!featureIdParam) return;

        try {
            isDataLoading.value = true;

            const data = (await getFeature(featureIdParam)) as { feature?: { geojson: { properties?: Record<string, unknown>; geometry: MapPageFeature['geometry'] } } };

            if (!data.feature) {
                console.error(`Feature ${featureIdParam} not found or access denied`);
                toast.error(`Feature ${featureIdParam} not found`);
                removeFeatureIdFromUrl();
                isDataLoading.value = false;
                return;
            }

            const geojsonData = data.feature.geojson;
            const properties: Record<string, unknown> = geojsonData.properties ? { ...geojsonData.properties } : {};
            properties.database_id = featureIdParam;

            const feature: MapPageFeature = {
                type: 'Feature',
                properties,
                geometry: geojsonData.geometry,
            };

            isDataLoading.value = false;

            await waitForMap();

            if (map.value?.getSource('geojson-data')) {
                const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
                const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
                const currentData = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
                const existingFeatures = currentData.features;

                const exists = existingFeatures.some((f) => f.properties.database_id === featureIdParam);
                if (!exists) {
                    existingFeatures.push(feature);
                    source?.setData({ type: 'FeatureCollection', features: existingFeatures });
                }

                await zoomToFeature(markRaw(convertMapLibreFeature(feature)) as MapPageFeature);
                removeFeatureIdFromUrl();
            }
        } catch (error) {
            console.error(`Error fetching feature ${featureIdParam}:`, error);
            toastApiError(error, `Failed to load feature ${featureIdParam}`);
            removeFeatureIdFromUrl();
            isDataLoading.value = false;
        }
    }

    async function handleUrlTag(): Promise<void> {
        await waitForMap();

        if (!currentTags.value || currentTags.value.length === 0) {
            if (initialSelectedTags.value.length > 0) {
                currentTags.value = initialSelectedTags.value;
                isTagFilterActive.value = true;
            } else if (route.query.tag) {
                const tagValue = Array.isArray(route.query.tag) ? route.query.tag[0] : route.query.tag;
                currentTags.value = [tagValue as string];
                isTagFilterActive.value = true;
            }
        } else {
            isTagFilterActive.value = true;
        }

        if (!currentTags.value || currentTags.value.length === 0) return;

        isDataLoading.value = true;
        try {
            clearMapForFilterSwitch();

            await waitForMapEvent('idle');

            const data = (await filterFeaturesByTags(currentTags.value, currentTagMatchMode.value)) as { data?: GeoJsonFeatureCollection };
            if (data.data?.features) {
                await ensureSourceEmpty();

                const rawData = markRaw(data.data);
                await addFeaturesToMap(rawData);

                await waitForMapEvent('idle');

                if (map.value?.getSource('geojson-data')) {
                    const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
                    const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
                    const sourceData = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
                    const features = sourceData.features.filter((f) => !f.properties._isLabelPoint && !f.properties._isSmallFeatureReplacement) as MapPageFeature[];

                    if (features.length > 0) {
                        await zoomToTaggedFeatures(features);
                    }
                }
            }
        } catch (error) {
            console.error('Error loading tag-filtered features:', error);
            loadError.value = error instanceof Error ? error.message : 'Failed to load tag-filtered features';
        } finally {
            isDataLoading.value = false;
        }
    }

    function handleTagFilterChange({ tags, matchMode }: { tags: string[] | null; matchMode?: 'AND' | 'OR' }): void {
        if (!map.value?.getSource('geojson-data')) return;

        if (!tags || tags.length === 0) {
            isTagFilterActive.value = false;
            currentTags.value = null;
            currentTagMatchMode.value = 'AND';

            clearLoadedBounds();
            void loadDataForCurrentView();
            return;
        }

        isTagFilterActive.value = true;
        currentTags.value = tags;
        currentTagMatchMode.value = matchMode ?? 'AND';

        clearLoadedBounds();
        void loadDataForCurrentView();
    }

    /** Immediate client-side pre-filter of already-loaded features while `handleTagFilterChange`'s reload is in flight. */
    function filterExistingFeaturesByTags(selectedTags: string[] | null): void {
        const mapInstance = map.value;
        if (!mapInstance?.getSource('geojson-data') || !selectedTags || selectedTags.length === 0) {
            return;
        }

        isTagFilterActive.value = true;

        const source: GeoJSONSource | undefined = mapInstance.getSource('geojson-data');
        const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
        const data = serialized.data ?? { type: 'FeatureCollection' as const, features: [] };
        const allFeatures = data.features as MapPageFeature[];

        const filteredFeatures = allFeatures.filter((f) => {
            if (f.properties._isLabelPoint || f.properties._isSmallFeatureReplacement) return false;

            const props = f.properties;
            let tags: unknown = props.tags ?? [];
            if (typeof tags === 'string') {
                try {
                    tags = JSON.parse(tags);
                } catch {
                    tags = [];
                }
            }
            if (!Array.isArray(tags)) tags = [];

            let systemTags: unknown = props.system_tags ?? [];
            if (typeof systemTags === 'string') {
                try {
                    systemTags = JSON.parse(systemTags);
                } catch {
                    systemTags = [];
                }
            }
            if (!Array.isArray(systemTags)) systemTags = [];

            const allFeatureTags = [...(tags as string[]), ...(systemTags as string[])];
            return selectedTags.every((tag) => allFeatureTags.includes(tag));
        });

        if (filteredFeatures.length > 0) {
            const filteredGeojsonFeatures = filterPointsOnBorders(filteredFeatures) as MapPageFeature[];
            const filteredCollection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: filteredGeojsonFeatures.map((f) => markRaw(f)) };
            source?.setData(markRaw(filteredCollection));
        } else {
            const emptyCollection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: [] };
            source?.setData(markRaw(emptyCollection));
        }
        updateFeatureCount();
        updateFeaturesInExtent();
    }

    return {
        collectionId,
        initialSelectedTags,
        isCollectionMode,
        collectionName,
        isTagFilterActive,
        tagFilteredFeatures,
        currentTags,
        currentTagMatchMode,
        availableTags,
        fetchAvailableTags,
        zoomToTaggedFeatures,
        handleCollectionFilter,
        handleUrlFeatureId,
        handleUrlTag,
        handleTagFilterChange,
        filterExistingFeaturesByTags,
    };
}

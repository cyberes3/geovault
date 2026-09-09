/**
 * Collection (`?collection=`) and tag (`?tag=`) filter modes: bbox loads through the map
 * session pipeline, plus the available-tags list for the sidebar dropdown.
 */
import { computed, markRaw, ref, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import { useRoute } from 'vue-router';
import { useStore } from 'vuex';
import type { Map as MapLibreMap } from 'maplibre-gl';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { getCollection } from '@/api/services/collectionsApi';
import { getFeature } from '@/api/services/featuresApi';
import { convertMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import { toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast';
import { sortTagsByPriority, sortUserTagsAlphabetically } from '@/utils/tagUtils.js';
import { getFeatureCoordinates } from '@/utils/map/maplibre';
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
    selectedFeature: Ref<MapPageFeature | null>;
    navigateAndRefresh: (navigationFn: () => void, clearAllBounds?: boolean) => Promise<void>;
    addFeaturesToMap: (geojsonData: GeoJsonFeatureCollection) => Promise<void>;
    resetFeatureState: () => void;
    loadDataForCurrentView: () => Promise<void>;
    waitForMap: () => Promise<void>;
    zoomToFeature: (feature: MapPageFeature) => Promise<void>;
}

export function useCollectionTagFilters(deps: UseCollectionTagFiltersDeps) {
    const {
        map,
        labelMarkerManager,
        isDataLoading,
        loadError,
        selectedFeature,
        navigateAndRefresh,
        addFeaturesToMap,
        resetFeatureState,
        loadDataForCurrentView,
        waitForMap,
        zoomToFeature,
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
            const { getTagCatalog } = await import('@/api/services/featuresApi');
            const page = await getTagCatalog({ page_size: '100' });
            const userTags = page.items.filter((item) => item.kind === 'user').map((item) => item.name);
            const systemTags = page.items.filter((item) => item.kind === 'system').map((item) => item.name);

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

        const maplibregl = getLoadedMaplibreGl();
        const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]);

        await navigateAndRefresh(() => {
            map.value?.fitBounds(bounds, { padding, duration, maxZoom: MAX_ZOOM_LEVEL });
        });
    }

    function clearMapForFilterSwitch(): void {
        selectedFeature.value = null;
        resetFeatureState();

        if (labelMarkerManager.value) {
            labelMarkerManager.value.clearAllMarkers();
        }
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
            await loadDataForCurrentView();
        } catch (error) {
            console.error('Error loading collection:', error);
            toastApiError(error, 'Failed to load collection');
            collectionName.value = null;
            isCollectionMode.value = false;
            resetFeatureState();
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
            await addFeaturesToMap({ type: 'FeatureCollection', features: [feature] });

            const normalized = markRaw(convertMapLibreFeature(feature)) as MapPageFeature;
            selectedFeature.value = normalized;
            await zoomToFeature(normalized);
            removeFeatureIdFromUrl();
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
            await loadDataForCurrentView();
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

            resetFeatureState();
            void loadDataForCurrentView();
            return;
        }

        isTagFilterActive.value = true;
        currentTags.value = tags;
        currentTagMatchMode.value = matchMode ?? 'AND';

        resetFeatureState();
        void loadDataForCurrentView();
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
    };
}

/**
 * Vue adapter over FeatureInteraction plus feature CRUD / camera helpers.
 */
import { computed, markRaw, onBeforeUnmount, ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import { useStore } from 'vuex';
import type { Map as MapLibreMap, Marker } from 'maplibre-gl';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { convertMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import { getCoordinatesFromGeometry } from '@/utils/map/geometry';
import { getInverseColor } from '@/utils/map/colorUtils';
import { isValidMapLngLatPair } from '@/utils/map/mapGeography.js';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { toastApiError } from '@/utils/apiError';
import { downloadKmz } from '@/utils/sharing/downloadKmz';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { MapPageFeature, MapUserSettings } from './mapPageTypes';
import type { FeatureMutation } from '@/utils/map/session/FeatureMutation';
import type { FeatureSource } from '@/utils/map/common/FeatureSource';
import type { MapSession } from '@/utils/map/session/MapSession';
import type { RenderFeature } from '@/utils/map/common/types';
import type { VaultFeature } from '@/contracts/feature';

export interface UseFeatureSelectionDeps {
    session: MapSession;
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    showAllLabels: Ref<boolean>;
    navigateAndRefresh: (navigationFn: () => void, clearAllBounds?: boolean) => Promise<void>;
    updateFeatureCount: () => void;
    updateFeaturesInExtent: () => void;
    getUserMapSettings: () => MapUserSettings;
    isPublicShareMode: ComputedRef<boolean>;
    shareId: ComputedRef<string | null>;
    canManageHiddenFeatures: ComputedRef<boolean>;
    mutations: FeatureMutation;
    featureSource: FeatureSource;
}

export function useFeatureSelection(deps: UseFeatureSelectionDeps) {
    const {
        session,
        map,
        labelMarkerManager,
        showAllLabels,
        navigateAndRefresh,
        updateFeatureCount,
        updateFeaturesInExtent,
        isPublicShareMode,
        shareId,
        canManageHiddenFeatures,
        mutations,
        featureSource,
    } = deps;
    const store = useStore();
    const revision = ref(0);
    const hoverMarker: ShallowRef<Marker | null> = shallowRef(null);
    const unsubscribe = session.interaction.subscribe(() => {
        revision.value += 1;
    });
    onBeforeUnmount(unsubscribe);

    function asPageFeature(feature: RenderFeature | null): MapPageFeature | null {
        return feature ? markRaw(convertMapLibreFeature(feature)) as MapPageFeature : null;
    }

    const selectedFeature = computed<MapPageFeature | null>({
        get() {
            revision.value;
            return asPageFeature(session.interaction.selected);
        },
        set(value) {
            session.interaction.select(session.map, value as RenderFeature | null);
        },
    });

    const isEditingFeature = computed({
        get() {
            revision.value;
            return session.interaction.isEditing;
        },
        set(value) {
            if (value) session.interaction.beginEdit();
            else session.interaction.cancelEdit();
        },
    });

    const showElevationProfile = computed({
        get() {
            revision.value;
            return session.interaction.mode === 'elevationProfile';
        },
        set(value) {
            if (value) session.interaction.showElevationProfile();
            else session.interaction.closeElevationProfile();
        },
    });

    const overlappingFeatures = computed(() => {
        revision.value;
        return session.interaction.overlapping.map((feature) => markRaw(convertMapLibreFeature(feature)) as MapPageFeature);
    });

    const showFeaturePopup = computed({
        get() {
            revision.value;
            return session.interaction.mode === 'disambiguating' && session.interaction.overlapping.length > 1;
        },
        set(value) {
            if (!value) session.interaction.closePopup();
        },
    });

    const popupPosition = computed(() => {
        revision.value;
        const point = session.interaction.popupPoint;
        const container = map.value?.getContainer();
        return {
            x: point?.x ?? 0,
            y: point?.y ?? 0,
            containerWidth: container?.clientWidth || window.innerWidth,
            containerHeight: container?.clientHeight || window.innerHeight,
        };
    });

    const hoveredFeatureId = computed({
        get() {
            revision.value;
            return session.interaction.hoveredId;
        },
        set(value) {
            session.interaction.hover(session.map, value == null ? null : String(value));
        },
    });

    function getSourceFeatures(): MapPageFeature[] {
        return featureSource.buildRenderCollection().features as MapPageFeature[];
    }

    async function ensureFeatureOnMap(feature: MapPageFeature): Promise<void> {
        if (!map.value?.getSource('geojson-data')) return;
        const featureId = feature.properties.database_id as string | number | undefined;
        if (!featureId) return;
        const hidden = store.getters['userSettings/hiddenFeatures'] as Array<{ id: string }> | undefined;
        if (Array.isArray(hidden) && hidden.some((item) => String(item.id) === String(featureId))) {
            return;
        }
        if (featureSource.has(String(featureId))) return;
        session.ingestIncoming([feature as VaultFeature]);
    }

    async function zoomToFeature(feature: MapPageFeature | null): Promise<void> {
        const mapInstance = map.value;
        if (!mapInstance || !feature) {
            console.warn('zoomToFeature: Missing map or feature', { map: !!mapInstance, feature: !!feature });
            return;
        }

        await ensureFeatureOnMap(feature);

        const geometry = feature.geometry;
        if (!geometry.coordinates) {
            console.warn('zoomToFeature: Invalid geometry', { geometry, feature });
            return;
        }

        const coords = getCoordinatesFromGeometry(geometry);
        if (coords.length === 0) {
            console.warn('zoomToFeature: No coordinates found in geometry', geometry);
            return;
        }

        let minLon = Infinity;
        let minLat = Infinity;
        let maxLon = -Infinity;
        let maxLat = -Infinity;
        coords.forEach((coord) => {
            const [lon, lat] = Array.isArray(coord) && coord.length >= 2 ? coord : [null, null];
            if (lon != null && lat != null && isValidMapLngLatPair(lon, lat)) {
                minLon = Math.min(minLon, lon);
                minLat = Math.min(minLat, lat);
                maxLon = Math.max(maxLon, lon);
                maxLat = Math.max(maxLat, lat);
            }
        });

        if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) {
            console.warn('zoomToFeature: Invalid bounds calculated', { minLon, minLat, maxLon, maxLat });
            return;
        }

        minLon = Math.max(-180, Math.min(180, minLon));
        minLat = Math.max(-90, Math.min(90, minLat));
        maxLon = Math.max(-180, Math.min(180, maxLon));
        maxLat = Math.max(-90, Math.min(90, maxLat));

        const isMobile = window.innerWidth < 768;
        const hasFeatureInfoBox = !!selectedFeature.value && !isEditingFeature.value;

        if (minLon === maxLon && minLat === maxLat) {
            let padding: number | { top: number; bottom: number; left: number; right: number } = 50;
            if (isMobile && hasFeatureInfoBox) {
                const infoBoxMaxHeight = window.innerHeight * 0.6;
                padding = { top: 50, bottom: infoBoxMaxHeight + 20, left: 50, right: 50 };
            }
            await navigateAndRefresh(() => {
                mapInstance.flyTo({ center: [minLon, minLat], zoom: 10, duration: 500, padding });
            });
            return;
        }

        const padding =
            isMobile && hasFeatureInfoBox ? { top: 50, bottom: window.innerHeight * 0.6 + 20, left: 50, right: 50 } : { top: 50, bottom: 50, left: 50, right: 50 };

        if (mapInstance.getMaxZoom() !== MAX_ZOOM_LEVEL) {
            mapInstance.setMaxZoom(MAX_ZOOM_LEVEL);
        }

        const maplibregl = getLoadedMaplibreGl();
        await navigateAndRefresh(() => {
            try {
                const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]);
                mapInstance.fitBounds(bounds, { padding, duration: 500, maxZoom: MAX_ZOOM_LEVEL });
            } catch (error) {
                console.error('zoomToFeature: Error fitting bounds (fallback)', error);
                mapInstance.flyTo({ padding: typeof padding === 'object' ? padding.top : padding, duration: 500 });
            }
        });
    }

    function handleFeatureListClick(feature: MapPageFeature | null): void {
        if (!feature) return;
        const normalized = markRaw(convertMapLibreFeature(feature)) as MapPageFeature;
        session.interaction.select(session.map, normalized as RenderFeature);
        void zoomToFeature(normalized);
    }

    function handleFeatureSelect(feature: MapPageFeature): void {
        session.interaction.select(session.map, feature as RenderFeature);
    }

    function handleEditFeature(): void {
        session.interaction.beginEdit();
    }

    function handleCancelEdit(): void {
        session.interaction.cancelEdit();
    }

    function handleFeatureDeleted(feature: MapPageFeature | null): void {
        const featureId = feature?.properties.database_id as string | number | undefined;
        if (featureId) {
            mutations.remove(String(featureId));
            updateFeatureCount();
            if (showAllLabels.value && labelMarkerManager.value) {
                labelMarkerManager.value.removeMarker(String(featureId));
            }
        }
        session.interaction.select(session.map, null);
    }

    async function handleFeatureSaved(updatedFeature: MapPageFeature | null): Promise<void> {
        if (!updatedFeature?.properties.database_id && selectedFeature.value?.properties.database_id != null) {
            const { getFeature } = await import('@/api/services/featuresApi');
            const id = selectedFeature.value.properties.database_id as string | number;
            const data = await getFeature(id) as { feature?: { geojson?: MapPageFeature; id?: string | number } };
            if (data.feature?.geojson) {
                updatedFeature = {
                    ...data.feature.geojson,
                    properties: { ...data.feature.geojson.properties, database_id: data.feature.id ?? id },
                };
            }
        }
        if (updatedFeature?.properties.database_id != null) {
            const featureId = updatedFeature.properties.database_id as string | number;
            if (featureSource.has(String(featureId)) && map.value) {
                const updatedFeatureCopy = JSON.parse(JSON.stringify(updatedFeature)) as MapPageFeature;
                updatedFeatureCopy.properties.database_id = featureId;
                mutations.applyPatch(updatedFeatureCopy as VaultFeature);
                labelMarkerManager.value?.updateMarkers(getSourceFeatures());
                updateFeaturesInExtent();
                updateFeatureCount();
                if (selectedFeature.value?.properties.database_id === featureId) {
                    session.interaction.select(session.map, updatedFeatureCopy as RenderFeature);
                }
            }
        }
        session.interaction.cancelEdit();
    }

    async function handleHideFeature(feature: MapPageFeature | null): Promise<void> {
        if (!canManageHiddenFeatures.value || !feature) return;
        const featureId = feature.properties.database_id as string | number | undefined;
        if (!featureId) return;
        const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager')).default;
        hiddenFeaturesManager.addHidden(featureId, () => {
            void store.dispatch('userSettings/addHiddenFeature', {
                featureId: String(featureId),
                featureName: (feature.properties.name as string | undefined) ?? null,
                geometryType: feature.geometry.type,
            });
            mutations.hide(String(featureId));
            updateFeatureCount();
            if (selectedFeature.value?.properties.database_id === featureId) {
                session.interaction.select(session.map, null);
            }
            updateFeaturesInExtent();
        });
    }

    async function handleUnhideFeature(featureId: string | number, clearLoadedBounds: () => void, loadDataForCurrentView: () => Promise<void>): Promise<void> {
        if (!canManageHiddenFeatures.value) return;
        const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager')).default;
        try {
            hiddenFeaturesManager.removeHidden(featureId, () => {
                void store.dispatch('userSettings/removeHiddenFeature', String(featureId));
            });
            await hiddenFeaturesManager.forceFlush();
            clearLoadedBounds();
            await loadDataForCurrentView();
            updateFeaturesInExtent();
        } catch (error) {
            console.error('Error unhiding feature:', error);
            toastApiError(error, 'Failed to unhide feature');
        }
    }

    async function handleUnhideAllHidden(clearLoadedBounds: () => void, loadDataForCurrentView: () => Promise<void>): Promise<void> {
        if (!canManageHiddenFeatures.value) return;
        try {
            const { clearHiddenFeatures } = await import('@/utils/userSettingsService');
            await clearHiddenFeatures();
            void store.dispatch('userSettings/setHiddenFeatures', []);
            clearLoadedBounds();
            await loadDataForCurrentView();
            updateFeaturesInExtent();
        } catch (error) {
            console.error('Error clearing hidden features:', error);
            toastApiError(error, 'Failed to unhide all features');
        }
    }

    async function handleEditBoxVisibilityChange(
        payload: { featureId?: string | number; hidden?: boolean } | null,
        clearLoadedBounds: () => void,
        loadDataForCurrentView: () => Promise<void>,
    ): Promise<void> {
        if (!payload?.featureId) return;
        if (payload.hidden) {
            await handleHideFeature({ type: 'Feature', properties: { database_id: payload.featureId }, geometry: { type: 'Point', coordinates: [0, 0] } });
        } else {
            await handleUnhideFeature(payload.featureId, clearLoadedBounds, loadDataForCurrentView);
        }
    }

    async function handleQuickPointCreated(createdFeature: MapPageFeature | null): Promise<void> {
        if (createdFeature && map.value?.getSource('geojson-data')) {
            if (createdFeature.geometry.type !== 'Point') {
                throw new Error('Quick point was not a point');
            }
            session.ingestIncoming([createdFeature as VaultFeature]);
            updateFeatureCount();
            updateFeaturesInExtent();
        }
    }

    function handleDownloadFeatureKmz(): void {
        const feature = selectedFeature.value;
        if (!feature) return;
        const featureId = (feature.properties.feature_ref ?? feature.properties.database_id) as string | number | undefined;
        if (!featureId) return;
        void downloadKmz({
            feature_ref: featureId,
            share: isPublicShareMode.value && shareId.value ? shareId.value : undefined,
        }).catch((error) => {
            toastApiError(error, 'Failed to download KMZ.');
        });
    }

    function handleElevationProfileClose(): void {
        session.interaction.closeElevationProfile();
        handleHoverClear();
    }

    function handleHoverPoint(point: number[] | { coordinates?: number[]; lon?: number; lat?: number }): void {
        if (!map.value) return;
        let coordinates: number[];
        if (Array.isArray(point) && point.length >= 2) {
            coordinates = point;
        } else if ('coordinates' in point && Array.isArray(point.coordinates)) {
            coordinates = point.coordinates;
        } else if ('lon' in point && point.lon !== undefined && point.lat !== undefined) {
            coordinates = [point.lon, point.lat];
        } else {
            return;
        }
        if (hoverMarker.value) {
            hoverMarker.value.remove();
            hoverMarker.value = null;
        }
        const markerColor = (selectedFeature.value?.properties.stroke as string) || '#ff0000';
        const el = document.createElement('div');
        el.style.width = '11px';
        el.style.height = '11px';
        el.style.borderRadius = '50%';
        el.style.backgroundColor = markerColor;
        el.style.border = `1px solid ${getInverseColor(markerColor)}`;
        el.style.boxSizing = 'border-box';
        const maplibregl = getLoadedMaplibreGl();
        hoverMarker.value = new maplibregl.Marker({ element: el, anchor: 'center' }).setLngLat([coordinates[0], coordinates[1]]).addTo(map.value);
    }

    function handleHoverClear(): void {
        if (hoverMarker.value) {
            hoverMarker.value.remove();
            hoverMarker.value = null;
        }
    }

    function handleClickPoint(point: number[] | { coordinates?: number[]; lon?: number; lat?: number }): void {
        if (!map.value) return;
        const mapInstance = map.value;
        let coordinates: number[];
        if (Array.isArray(point) && point.length >= 2) {
            coordinates = point;
        } else if ('coordinates' in point && Array.isArray(point.coordinates)) {
            coordinates = point.coordinates;
        } else if ('lon' in point && point.lon !== undefined && point.lat !== undefined) {
            coordinates = [point.lon, point.lat];
        } else {
            return;
        }
        const currentZoom = mapInstance.getZoom();
        void navigateAndRefresh(() => {
            mapInstance.flyTo({ center: [coordinates[0], coordinates[1]], zoom: currentZoom, duration: 500 });
        });
    }

    return {
        selectedFeature,
        isEditingFeature,
        showElevationProfile,
        overlappingFeatures,
        showFeaturePopup,
        popupPosition,
        hoveredFeatureId,
        updateFeatureHighlighting: () => {},
        ensureFeatureOnMap,
        zoomToFeature,
        handleFeatureListClick,
        handleFeatureSelect,
        handleEditFeature,
        handleCancelEdit,
        handleFeatureDeleted,
        handleFeatureSaved,
        handleHideFeature,
        handleUnhideFeature,
        handleUnhideAllHidden,
        handleEditBoxVisibilityChange,
        handleQuickPointCreated,
        handleDownloadFeatureKmz,
        handleElevationProfileClose,
        handleHoverPoint,
        handleHoverClear,
        handleClickPoint,
    };
}

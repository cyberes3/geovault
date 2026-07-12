/**
 * Click/hover feature selection: the click handler's overlapping-feature disambiguation,
 * hover highlighting, the selected/editing feature state backing `FeatureInfoBox` /
 * `FeatureEditBox` / `FeatureSelectionPopup`, and the edit/save/delete/hide handlers that
 * mutate the map source in place after a feature CRUD action.
 */
import { markRaw, ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue';
import { useStore } from 'vuex';
import maplibregl, { type Map as MapLibreMap, type MapMouseEvent, type Marker, type GeoJSONSource } from 'maplibre-gl';
import { convertMapLibreFeature } from '@/utils/map/maplibre/featureConversion.js';
import {
    getFeatureIconUrl,
    getIconSourceUrl,
    loadIconImage,
    shouldUseIcon,
    getStrokeWidthExpressionWithHighlight,
    getCircleRadiusExpressionWithHighlight,
    getIconSizeExpressionWithHighlight,
} from '@/utils/map/maplibre/featureStyling.js';
import { createZoomBasedRadiusExpression } from '@/utils/map/maplibre/featureStyles.js';
import { getFeatureCoordinates } from '@/utils/map/maplibre';
import { getInverseColor } from '@/utils/map/colorUtils';
import { isValidMapLngLatPair } from '@/utils/map/mapGeography.js';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import { toastApiError } from '@/utils/apiError';
import { APIHOST } from '@/config.js';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import type { MapPageFeature, MapUserSettings } from './mapPageTypes';

export interface UseFeatureSelectionDeps {
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    showAllLabels: Ref<boolean>;
    navigateAndRefresh: (navigationFn: () => void, clearAllBounds?: boolean) => Promise<void>;
    updateFeatureCount: () => void;
    updateFeaturesInExtent: () => void;
    getUserMapSettings: () => MapUserSettings;
    isPublicShareMode: ComputedRef<boolean>;
    shareId: ComputedRef<string | null>;
    /** Gate for hide/unhide actions: main map route, not a public share, and the user is authenticated. */
    canManageHiddenFeatures: ComputedRef<boolean>;
}

interface RawMapLibreFeature {
    properties?: Record<string, unknown> & { _originalFeatureId?: unknown; _isSmallFeatureReplacement?: boolean; _isLabelPoint?: boolean };
    geometry?: MapPageFeature['geometry'];
}

export function useFeatureSelection(deps: UseFeatureSelectionDeps) {
    const { map, labelMarkerManager, showAllLabels, navigateAndRefresh, updateFeatureCount, updateFeaturesInExtent, getUserMapSettings, isPublicShareMode, shareId, canManageHiddenFeatures } = deps;
    const store = useStore();

    const selectedFeature: Ref<MapPageFeature | null> = ref(null);
    const isEditingFeature = ref(false);
    const showElevationProfile = ref(false);
    const overlappingFeatures: Ref<MapPageFeature[]> = ref([]);
    const showFeaturePopup = ref(false);
    const popupPosition = ref({ x: 0, y: 0, containerWidth: 0, containerHeight: 0 });
    const hoveredFeatureId: Ref<string | number | null> = ref(null);
    const hoverMarker: ShallowRef<Marker | null> = shallowRef(null);

    function getSourceFeatures(): MapPageFeature[] {
        if (!map.value?.getSource('geojson-data')) return [];
        const source = map.value.getSource('geojson-data');
        const serialized = source?.serialize() as { data?: GeoJsonFeatureCollection };
        return (serialized.data?.features ?? []) as MapPageFeature[];
    }

    function setSourceFeatures(features: MapPageFeature[]): void {
        if (!map.value?.getSource('geojson-data')) return;
        const source: GeoJSONSource | undefined = map.value.getSource('geojson-data');
        const collection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features: features.map((f) => markRaw(f)) };
        source?.setData(markRaw(collection));
    }

    /**
     * Update paint properties for lines, polygon-outlines, and points to highlight hovered/selected features.
     */
    function updateFeatureHighlighting(): void {
        if (!map.value) return;
        const mapInstance = map.value;

        // `database_id`/`hoveredFeatureId` may be numeric at runtime; these highlight helpers only use `===` against
        // the feature's `database_id` property so we just need the TS-facing type to match their `string|null` JSDoc signatures.
        const selectedFeatureId = (selectedFeature.value?.properties.database_id ?? null) as string | null | undefined;
        const hoveredId = hoveredFeatureId.value as string | null | undefined;

        const lineWidthExpression = getStrokeWidthExpressionWithHighlight(2, hoveredId, selectedFeatureId, 1.5);
        if (mapInstance.getLayer('lines')) {
            mapInstance.setPaintProperty('lines', 'line-width', lineWidthExpression);
        }
        if (mapInstance.getLayer('polygon-outlines')) {
            mapInstance.setPaintProperty('polygon-outlines', 'line-width', lineWidthExpression);
        }

        const baseRadiusExpression = createZoomBasedRadiusExpression(4, 2);
        const radiusExpression = getCircleRadiusExpressionWithHighlight(baseRadiusExpression, hoveredId, selectedFeatureId, 1.5);
        if (mapInstance.getLayer('points')) {
            mapInstance.setPaintProperty('points', 'circle-radius', radiusExpression);
            mapInstance.setPaintProperty('points', 'circle-stroke-width', 1);
            mapInstance.setPaintProperty('points', 'circle-stroke-color', '#000000');
            mapInstance.setPaintProperty('points', 'circle-stroke-opacity', 1);
        }

        const replacementRadiusExpression = createZoomBasedRadiusExpression(3, 1.5);
        const replacementRadiusHighlight = getCircleRadiusExpressionWithHighlight(replacementRadiusExpression, hoveredId, selectedFeatureId, 1.5);
        if (mapInstance.getLayer('replacement-points')) {
            mapInstance.setPaintProperty('replacement-points', 'circle-radius', replacementRadiusHighlight);
            mapInstance.setPaintProperty('replacement-points', 'circle-stroke-width', 1);
            mapInstance.setPaintProperty('replacement-points', 'circle-stroke-color', '#000000');
            mapInstance.setPaintProperty('replacement-points', 'circle-stroke-opacity', 1);
        }

        const iconSizeExpression = getIconSizeExpressionWithHighlight(1.0, hoveredId, selectedFeatureId, 1.05);
        if (mapInstance.getLayer('point-icons')) {
            mapInstance.setLayoutProperty('point-icons', 'icon-size', iconSizeExpression);
        }
    }

    /** Full click handler: query rendered features near the click, disambiguate overlaps, select. */
    function onMapClick(e: MapMouseEvent): void {
        const mapInstance = map.value;
        if (!mapInstance) return;

        const layersToQuery = ['points', 'point-icons', 'replacement-points', 'lines', 'polygons', 'polygon-outlines'].filter((layerId) => mapInstance.getLayer(layerId));
        if (layersToQuery.length === 0) return;

        const bbox: [maplibregl.PointLike, maplibregl.PointLike] = [
            [e.point.x - 15, e.point.y - 15],
            [e.point.x + 15, e.point.y + 15],
        ];
        const features = mapInstance.queryRenderedFeatures(bbox, { layers: layersToQuery }) as unknown as RawMapLibreFeature[];

        const clickableFeatures = features.filter((f) => !f.properties?._isLabelPoint);

        if (showElevationProfile.value) {
            showElevationProfile.value = false;
            handleHoverClear();
        }

        const sourceFeatures = getSourceFeatures();
        const processedFeatures = clickableFeatures.map((f) => {
            if (f.properties?._isSmallFeatureReplacement) {
                const originalId = f.properties._originalFeatureId;
                if (originalId) {
                    const originalFeature = sourceFeatures.find((feature) => feature.properties.database_id === originalId && !feature.properties._isSmallFeatureReplacement);
                    if (originalFeature) return originalFeature;
                }
            }
            return f;
        });

        const uniqueFeatures: RawMapLibreFeature[] = [];
        const seenIds = new Set<unknown>();
        for (const feature of processedFeatures) {
            const featureId = feature.properties?.database_id as string | number | undefined;
            if (featureId && !seenIds.has(featureId)) {
                seenIds.add(featureId);
                uniqueFeatures.push(feature);
            } else if (!featureId) {
                uniqueFeatures.push(feature);
            }
        }

        if (uniqueFeatures.length === 0) {
            selectedFeature.value = null;
            isEditingFeature.value = false;
            showFeaturePopup.value = false;
            handleHoverClear();
        } else if (uniqueFeatures.length === 1) {
            selectedFeature.value = markRaw(convertMapLibreFeature(uniqueFeatures[0])) as MapPageFeature;
            isEditingFeature.value = false;
            showFeaturePopup.value = false;
        } else {
            overlappingFeatures.value = uniqueFeatures.map((f) => markRaw(convertMapLibreFeature(f)) as MapPageFeature);
            popupPosition.value = {
                x: e.point.x,
                y: e.point.y,
                containerWidth: mapInstance.getContainer().clientWidth || window.innerWidth,
                containerHeight: mapInstance.getContainer().clientHeight || window.innerHeight,
            };
            showFeaturePopup.value = true;
        }
    }

    const MOUSE_HOVER_LAYERS = ['points', 'point-icons', 'replacement-points', 'lines', 'polygons', 'polygon-outlines'];

    function onMapMouseMove(e: MapMouseEvent): void {
        const mapInstance = map.value;
        if (!mapInstance) return;

        const layersToQuery = MOUSE_HOVER_LAYERS.filter((layerId) => mapInstance.getLayer(layerId));
        if (layersToQuery.length === 0) return;

        const bbox: [maplibregl.PointLike, maplibregl.PointLike] = [
            [e.point.x - 5, e.point.y - 5],
            [e.point.x + 5, e.point.y + 5],
        ];
        const features = mapInstance.queryRenderedFeatures(bbox, { layers: layersToQuery }) as unknown as RawMapLibreFeature[];
        const hoverableFeatures = features.filter((f) => !f.properties?._isLabelPoint);

        mapInstance.getCanvas().style.cursor = hoverableFeatures.length > 0 ? 'pointer' : '';

        if (hoverableFeatures.length > 0) {
            const hoveredId = hoverableFeatures[0].properties?.database_id as string | number | undefined;
            if (hoveredFeatureId.value !== hoveredId) {
                hoveredFeatureId.value = hoveredId ?? null;
                updateFeatureHighlighting();
            }
        } else if (hoveredFeatureId.value !== null) {
            hoveredFeatureId.value = null;
            updateFeatureHighlighting();
        }
    }

    function onMapMouseOut(): void {
        if (map.value) {
            map.value.getCanvas().style.cursor = '';
        }
        if (hoveredFeatureId.value !== null) {
            hoveredFeatureId.value = null;
            updateFeatureHighlighting();
        }
    }

    /** Ensure a single feature (e.g. from search or a `?featureId=` URL) exists on the map source, processing its icon if needed. */
    async function ensureFeatureOnMap(feature: MapPageFeature): Promise<void> {
        const mapInstance = map.value;
        if (!mapInstance?.getSource('geojson-data')) return;

        const properties = feature.properties;
        const featureId = properties.database_id as string | number | undefined;
        if (!featureId) return;

        const existingFeatures = getSourceFeatures();
        const exists = existingFeatures.some((f) => f.properties.database_id === featureId);
        if (exists) return;

        const geoJsonFeature: MapPageFeature = { type: 'Feature', geometry: feature.geometry, properties };

        if (geoJsonFeature.geometry.type === 'Point') {
            const iconUrl = getFeatureIconUrl(geoJsonFeature.properties);
            const zoom = mapInstance.getZoom();
            const userSettings = getUserMapSettings();
            const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;
            const shouldShowIcon = !!iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom);

            if (shouldShowIcon) {
                const resolvedUrl = getIconSourceUrl(iconUrl, geoJsonFeature.properties);
                const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`;
                geoJsonFeature.properties['_icon-id'] = iconId;

                if (!mapInstance.hasImage(iconId)) {
                    try {
                        await loadIconImage(mapInstance, iconId, resolvedUrl);
                    } catch (err) {
                        console.warn(`Failed to load icon ${iconId}:`, err);
                        delete geoJsonFeature.properties['_icon-id'];
                    }
                }
            }
        }

        existingFeatures.push(geoJsonFeature);
        setSourceFeatures(existingFeatures);

        if (labelMarkerManager.value) {
            labelMarkerManager.value.updateMarkers(existingFeatures);
        }
    }

    /** Fit the camera to a single feature's geometry, adding it to the map source first if needed. */
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

        const coords = getFeatureCoordinates(geometry);
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
            } else if (lon != null && lat != null) {
                console.warn('zoomToFeature: Coordinate out of valid range', { lon, lat });
            }
        });

        if (!isFinite(minLon) || !isFinite(minLat) || !isFinite(maxLon) || !isFinite(maxLat)) {
            console.warn('zoomToFeature: Invalid bounds calculated', { minLon, minLat, maxLon, maxLat });
            return;
        }

        if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
            minLon = Math.max(-180, Math.min(180, minLon));
            minLat = Math.max(-90, Math.min(90, minLat));
            maxLon = Math.max(-180, Math.min(180, maxLon));
            maxLat = Math.max(-90, Math.min(90, maxLat));
        }

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

        await navigateAndRefresh(() => {
            try {
                const bounds = new maplibregl.LngLatBounds([minLon, minLat], [maxLon, maxLat]);

                let zoomClampHandler: (() => void) | null = () => {
                    const currentZoom = mapInstance.getZoom();
                    if (currentZoom > MAX_ZOOM_LEVEL) {
                        mapInstance.setZoom(MAX_ZOOM_LEVEL);
                    }
                };
                mapInstance.on('zoom', zoomClampHandler);

                mapInstance.fitBounds(bounds, { padding, duration: 500, maxZoom: MAX_ZOOM_LEVEL });

                void mapInstance.once('moveend', () => {
                    if (zoomClampHandler) {
                        mapInstance.off('zoom', zoomClampHandler);
                        zoomClampHandler = null;
                    }
                    const finalZoom = mapInstance.getZoom();
                    if (finalZoom > MAX_ZOOM_LEVEL) {
                        mapInstance.setZoom(MAX_ZOOM_LEVEL);
                    }
                });
            } catch (error) {
                console.error('zoomToFeature: Error fitting bounds (fallback)', error);
                try {
                    // `flyTo` has neither a `bounds` nor a `maxZoom` option (unlike `fitBounds` above, which just
                    // threw) - both were never valid FlyToOptions fields, so this fallback just flies to the
                    // current center/zoom re-applying padding (the map's max zoom is already globally clamped via
                    // `setMaxZoom()` above). Preserved as-is; this branch is already a last-resort fallback for a
                    // `fitBounds` failure that should be rare in practice.
                    mapInstance.flyTo({ padding: typeof padding === 'object' ? padding.top : padding, duration: 500 });
                } catch (error2) {
                    console.error('zoomToFeature: Error with flyTo fallback (fallback)', error2);
                }
            }
        });
    }

    function handleFeatureListClick(feature: MapPageFeature | null): void {
        if (!feature) return;
        isEditingFeature.value = false;
        showFeaturePopup.value = false;
        const normalized = markRaw(convertMapLibreFeature(feature)) as MapPageFeature;
        selectedFeature.value = normalized;
        void zoomToFeature(normalized);
    }

    function handleFeatureSelect(feature: MapPageFeature): void {
        selectedFeature.value = feature;
        isEditingFeature.value = false;
        showFeaturePopup.value = false;
    }

    function handleEditFeature(): void {
        isEditingFeature.value = true;
    }

    function handleCancelEdit(): void {
        isEditingFeature.value = false;
    }

    function handleFeatureDeleted(feature: MapPageFeature | null): void {
        const properties = feature?.properties ?? {};
        const featureId = properties.database_id as string | number | undefined;

        if (featureId && map.value?.getSource('geojson-data')) {
            const features = getSourceFeatures().filter((f) => f.properties.database_id !== featureId);
            setSourceFeatures(features);
            updateFeatureCount();

            if (showAllLabels.value && labelMarkerManager.value) {
                labelMarkerManager.value.removeMarker(String(featureId));
            }
        }

        selectedFeature.value = null;
        isEditingFeature.value = false;
    }

    function handleFeatureSaved(updatedFeature: MapPageFeature | null): void {
        if (updatedFeature?.properties.database_id != null) {
            const featureId = updatedFeature.properties.database_id as string | number;

            if (map.value?.getSource('geojson-data')) {
                const mapInstance = map.value;
                const features = getSourceFeatures();
                const featureIndex = features.findIndex((f) => f.properties.database_id === featureId && !f.properties._isLabelPoint && !f.properties._isSmallFeatureReplacement);

                if (featureIndex !== -1) {
                    const updatedFeatureCopy = JSON.parse(JSON.stringify(updatedFeature)) as MapPageFeature;
                    updatedFeatureCopy.properties.database_id = featureId;

                    if (updatedFeatureCopy.geometry.type === 'Point') {
                        const iconUrl = getFeatureIconUrl(updatedFeatureCopy.properties);
                        const zoom = mapInstance.getZoom();
                        const userSettings = getUserMapSettings();
                        const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;
                        const shouldShowIcon = !!iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom);

                        if (shouldShowIcon) {
                            const resolvedUrl = getIconSourceUrl(iconUrl, updatedFeatureCopy.properties);
                            const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`;
                            updatedFeatureCopy.properties['_icon-id'] = iconId;

                            if (!mapInstance.hasImage(iconId)) {
                                loadIconImage(mapInstance, iconId, resolvedUrl).catch((err: unknown) => {
                                    console.warn(`Failed to load icon ${iconId}:`, err);
                                    delete updatedFeatureCopy.properties['_icon-id'];
                                });
                            }
                        } else {
                            delete updatedFeatureCopy.properties['_icon-id'];
                        }
                    } else {
                        delete updatedFeatureCopy.properties['_icon-id'];
                    }

                    features[featureIndex] = markRaw(updatedFeatureCopy);
                    setSourceFeatures(features);

                    if (showAllLabels.value && labelMarkerManager.value) {
                        labelMarkerManager.value.updateMarkers(features);
                    }

                    updateFeaturesInExtent();
                    updateFeatureCount();

                    if (selectedFeature.value?.properties.database_id === featureId) {
                        selectedFeature.value = markRaw(convertMapLibreFeature(updatedFeatureCopy)) as MapPageFeature;
                    }
                } else {
                    console.log(`Feature ${featureId} not found on map, skipping update`);
                }
            }
        }

        isEditingFeature.value = false;
    }

    async function handleHideFeature(feature: MapPageFeature | null): Promise<void> {
        if (!canManageHiddenFeatures.value || !feature) return;

        const properties = feature.properties;
        const featureId = properties.database_id as string | number | undefined;
        const featureName = properties.name as string | undefined;
        const geometryType = feature.geometry.type;
        if (!featureId) return;

        const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager')).default;

        const optimisticUpdate = () => {
            void store.dispatch('userSettings/addHiddenFeature', {
                featureId: String(featureId),
                featureName: featureName ?? null,
                geometryType,
            });

            if (map.value?.getSource('geojson-data')) {
                const features = getSourceFeatures().filter((f) => f.properties.database_id !== featureId);
                setSourceFeatures(features);
                updateFeatureCount();
            }

            if (selectedFeature.value?.properties.database_id === featureId) {
                selectedFeature.value = null;
                isEditingFeature.value = false;
            }

            updateFeaturesInExtent();
        };

        hiddenFeaturesManager.addHidden(featureId, optimisticUpdate);
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
            const mapInstance = map.value;
            if (createdFeature.geometry.type !== 'Point') {
                throw new Error('Quick point was not a point');
            }
            const iconUrl = getFeatureIconUrl(createdFeature.properties);
            const zoom = mapInstance.getZoom();
            const userSettings = getUserMapSettings();
            const replaceIconsLowZoom = userSettings.replace_icons_low_zoom !== undefined ? !!userSettings.replace_icons_low_zoom : true;
            const shouldShowIcon = !!iconUrl && shouldUseIcon(zoom, iconUrl, replaceIconsLowZoom);

            if (shouldShowIcon) {
                const resolvedUrl = getIconSourceUrl(iconUrl, createdFeature.properties);
                const iconId = `icon-${resolvedUrl.replace(/[^a-zA-Z0-9]/g, '_')}`;
                createdFeature.properties['_icon-id'] = iconId;

                if (!mapInstance.hasImage(iconId)) {
                    try {
                        await loadIconImage(mapInstance, iconId, resolvedUrl);
                    } catch (err) {
                        console.warn(`Failed to load icon ${iconId}:`, err);
                        delete createdFeature.properties['_icon-id'];
                    }
                }
            }

            const existingFeatures = getSourceFeatures();
            existingFeatures.push(createdFeature);
            setSourceFeatures(existingFeatures);

            updateFeatureCount();
            updateFeaturesInExtent();

            if (showAllLabels.value && labelMarkerManager.value) {
                labelMarkerManager.value.updateMarkers(existingFeatures);
            }
        }
    }

    function handleDownloadFeatureKmz(): void {
        const feature = selectedFeature.value;
        if (!feature) return;

        const properties = feature.properties;
        const featureId = properties.database_id as string | number | undefined;
        if (!featureId) return;

        let url = `${APIHOST}/api/export-kmz?feature=${encodeURIComponent(String(featureId))}`;
        if (isPublicShareMode.value && shareId.value) {
            url += `&share=${encodeURIComponent(shareId.value)}`;
        }
        window.open(url, '_blank');
    }

    function handleElevationProfileClose(): void {
        showElevationProfile.value = false;
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

        let markerColor = '#ff0000';
        if (selectedFeature.value) {
            markerColor = (selectedFeature.value.properties.stroke as string) || '#ff0000';
        }
        const borderColor = getInverseColor(markerColor);

        const el = document.createElement('div');
        el.style.width = '11px';
        el.style.height = '11px';
        el.style.borderRadius = '50%';
        el.style.backgroundColor = markerColor;
        el.style.border = `1px solid ${borderColor}`;
        el.style.boxSizing = 'border-box';

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
        updateFeatureHighlighting,
        onMapClick,
        onMapMouseMove,
        onMapMouseOut,
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
        getSourceFeatures,
        setSourceFeatures,
    };
}

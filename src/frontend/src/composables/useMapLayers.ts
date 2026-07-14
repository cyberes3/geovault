/**
 * Basemap tile sources (fetch/switch) and 3D terrain / hillshade state and toggling.
 * Label visibility (a sibling toggle in `MapControlsSidebar`) also lives here since turning
 * labels off/on requires the same "clear source, reload bbox data" dance as switching layers.
 */
import { ref, shallowRef, type Ref, type ShallowRef } from 'vue';
import type { Map as MapLibreMap, GeoJSONSource } from 'maplibre-gl';
import {
    MapTilerConfig,
    setupTerrain as maptilerSetupTerrain,
    removeTerrain as maptilerRemoveTerrain,
    addHillshade,
    removeHillshade as maptilerRemoveHillshade,
} from '@/utils/map/maplibre/maptilerIntegration.js';
import { restoreGeoJsonFeatures, restoreMapView, getMapState, getGeoJsonData } from '@/utils/map/maplibre/layerSwitching.js';
import { getTileSources, type TileSource } from '@/api/services/tilesApi';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { MAX_ZOOM_LEVEL, DEFAULT_GLYPHS_URL, resolveMapStyle } from '@/utils/map/maplibre/mapInitialization.js';
import type { StyleSpecification } from 'maplibre-gl';

export interface UseMapLayersDeps {
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    showAllLabels: Ref<boolean>;
    createMapInstance: (mapConfig: { center: [number, number]; zoom: number; pitch?: number; bearing?: number; style?: StyleSpecification | string }) => Promise<void>;
    destroyMap: () => void;
    ensureMapResize: () => void;
    waitForMapEvent: (eventName: string, timeout?: number) => Promise<void>;
    updateLayerMaxZoom: (minMaxZoom?: number) => void;
    getDefaultBasemap: () => string | undefined;
    /** Persistent cache of GeoJSON features that survives `setStyle()` calls (owned by `useFeatureData`). */
    cachedGeoJsonData: ShallowRef<GeoJsonFeatureCollection | null>;
    hasLoadedBounds: () => boolean;
    loadDataForCurrentView: () => Promise<void>;
    onAfterFeaturesChanged: () => void;
    setLoadError: (message: string) => void;
}

export function useMapLayers(deps: UseMapLayersDeps) {
    const {
        map,
        labelMarkerManager,
        showAllLabels,
        createMapInstance,
        destroyMap,
        ensureMapResize,
        waitForMapEvent,
        updateLayerMaxZoom,
        getDefaultBasemap,
        cachedGeoJsonData,
        hasLoadedBounds,
        loadDataForCurrentView,
        onAfterFeaturesChanged,
        setLoadError,
    } = deps;

    const tileSources: Ref<TileSource[]> = ref([]);
    const selectedLayer = ref('osm');
    const maptilerConfig: ShallowRef<MapTilerConfig | null> = shallowRef(null);
    const terrainEnabled = ref(false);
    const showTerrainTooltip = ref(false);
    const hillshadeEnabled = ref(false);

    async function fetchTileSources(): Promise<TileSource[]> {
        try {
            const data = await getTileSources();
            tileSources.value = data.sources.filter((source) => !source.hidden);

            const defaultBasemap = getDefaultBasemap();
            if (defaultBasemap && tileSources.value.find((s) => s.id === defaultBasemap)) {
                selectedLayer.value = defaultBasemap;
            } else if (!selectedLayer.value || !tileSources.value.find((s) => s.id === selectedLayer.value)) {
                if (tileSources.value.length > 0) {
                    selectedLayer.value = tileSources.value[0].id;
                }
            }

            return data.sources;
        } catch (error) {
            console.error('Error fetching tile sources:', error);
            tileSources.value = [
                {
                    id: 'osm',
                    name: 'OpenStreetMap',
                    type: 'xyz',
                    requires_proxy: false,
                    client_config: {
                        type: 'xyz',
                        url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                        tileSize: 256,
                    },
                },
            ];
            if (!selectedLayer.value) {
                selectedLayer.value = 'osm';
            }
            return [];
        }
    }

    async function fetchMaptilerConfig(sources: TileSource[]): Promise<void> {
        maptilerConfig.value = new MapTilerConfig();
        await maptilerConfig.value.fetchConfig(sources);
    }

    function shouldApplyAtmosphere(layerName: string): boolean {
        if (!terrainEnabled.value) return false;
        const name = layerName.toLowerCase();
        return name.includes('imagery') || name.includes('satellite');
    }

    function addHillshadeIfNeeded(): void {
        if (!map.value || !maptilerConfig.value) return;
        if (!hillshadeEnabled.value) return;
        addHillshade(map.value, maptilerConfig.value, 'feature-layer');
    }

    function removeHillshade(): void {
        if (!map.value) return;
        maptilerRemoveHillshade(map.value);
    }

    function removeTerrain(): void {
        removeHillshade();
        if (map.value) {
            maptilerRemoveTerrain(map.value);
        }
    }

    async function setupTerrain(): Promise<void> {
        if (!maptilerConfig.value || !map.value) return;

        const tileSource = tileSources.value.find((s) => s.id === selectedLayer.value);
        const layerName = tileSource?.name ?? '';
        const applyAtmosphere = shouldApplyAtmosphere(layerName);

        await maptilerSetupTerrain(map.value, maptilerConfig.value, applyAtmosphere);
        if (hillshadeEnabled.value) {
            addHillshadeIfNeeded();
        }
    }

    async function toggleTerrain(): Promise<void> {
        if (!map.value) return;

        const newState = !terrainEnabled.value;

        if (newState) {
            terrainEnabled.value = true;
            await setupTerrain();
            map.value.easeTo({ pitch: 50, duration: 800 });

            if (!showTerrainTooltip.value) {
                showTerrainTooltip.value = true;
                setTimeout(() => {
                    showTerrainTooltip.value = false;
                }, 3000);
            }
        } else {
            terrainEnabled.value = false;
            removeTerrain();
            map.value.easeTo({ pitch: 0, duration: 800 });
        }
    }

    /** Apply a tile source (style-based or raster) to the map. */
    async function applyTileSource(layerValue: string): Promise<void> {
        if (!map.value) return;
        const mapInstance = map.value;

        const tileSource = tileSources.value.find((s) => s.id === layerValue);
        if (!tileSource) {
            console.error(`Tile source not found: ${layerValue}`);
            return;
        }

        mapInstance.setStyle(resolveMapStyle(tileSource, DEFAULT_GLYPHS_URL));
        await waitForMapEvent('styledata');
        mapInstance.setMaxZoom(MAX_ZOOM_LEVEL);
        updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);
    }

    async function applyTerrainAndHillshade(layerValue: string): Promise<void> {
        if (!map.value || !maptilerConfig.value?.isAvailable()) return;

        const tileSource = tileSources.value.find((s) => s.id === layerValue);
        const layerName = tileSource?.name ?? '';

        if (terrainEnabled.value) {
            const applyAtmosphere = shouldApplyAtmosphere(layerName);
            await maptilerSetupTerrain(map.value, maptilerConfig.value, applyAtmosphere);
        }

        if (hillshadeEnabled.value) {
            addHillshadeIfNeeded();
        }
    }

    function handleHillshadeChange(enabled: boolean): void {
        hillshadeEnabled.value = enabled;
        if (!map.value || !maptilerConfig.value) return;
        if (enabled) {
            addHillshade(map.value, maptilerConfig.value, 'feature-layer');
        } else {
            maptilerRemoveHillshade(map.value);
        }
    }

    async function handleLabelsVisibilityChange(showLabels: boolean, loadedBoundsClear: () => void): Promise<void> {
        showAllLabels.value = showLabels;
        if (!labelMarkerManager.value) return;

        labelMarkerManager.value.setVisibility(showLabels);

        if (showLabels) {
            loadedBoundsClear();
            await loadDataForCurrentView();
        } else {
            const source: GeoJSONSource | undefined = map.value?.getSource('geojson-data');
            if (!source) return;

            const serializedData = source.serialize().data;
            const currentData: GeoJsonFeatureCollection = typeof serializedData === 'string' ? { type: 'FeatureCollection', features: [] } : (serializedData as GeoJsonFeatureCollection);

            const featuresWithoutLabelPoints = currentData.features.filter((f) => !f.properties._isLabelPoint);

            source.setData({
                type: 'FeatureCollection',
                features: featuresWithoutLabelPoints,
            });
        }
    }

    /**
     * Switch map layer - completely resets the map for raster sources, or swaps the style in
     * place for style-based (MapTiler) sources, restoring saved camera + GeoJSON features either way.
     */
    async function switchMapLayer(layerValue: string, isInitialSetup = false): Promise<void> {
        if (!map.value) return;

        const mapState = getMapState(map.value) as { center: { lng: number; lat: number }; zoom: number; pitch: number; bearing: number } | null;
        if (!mapState) return;

        let geojsonData: GeoJsonFeatureCollection | null = null;

        if (cachedGeoJsonData.value?.features && cachedGeoJsonData.value.features.length > 0) {
            geojsonData = cachedGeoJsonData.value;
        }

        if (!geojsonData?.features || geojsonData.features.length === 0) {
            let attempts = 0;
            while (!geojsonData && attempts < 3) {
                const sourceData = getGeoJsonData(map.value);
                if (sourceData?.features && sourceData.features.length > 0) {
                    geojsonData = sourceData;
                    cachedGeoJsonData.value = sourceData;
                    break;
                }
                if (attempts < 2) {
                    await new Promise((resolve) => setTimeout(resolve, 10));
                }
                attempts++;
            }
        }

        geojsonData ??= { type: 'FeatureCollection', features: [] };

        const hadFeaturesToRestore = geojsonData.features.length > 0;

        selectedLayer.value = layerValue;
        const tileSource = tileSources.value.find((s) => s.id === layerValue);
        if (!tileSource) {
            console.error(`Tile source not found: ${layerValue}`);
            return;
        }

        const clientConfig = tileSource.client_config;
        const isStyleBased = !!clientConfig.style_url || clientConfig.type === 'maptiler';

        const restoreAndVerify = async (): Promise<boolean> => {
            const mapInstance = map.value;
            if (!mapInstance) return false;
            await restoreGeoJsonFeatures(mapInstance, geojsonData, showAllLabels.value, labelMarkerManager.value);
            onAfterFeaturesChanged();
            await new Promise((resolve) => setTimeout(resolve, 100));

            const source: GeoJSONSource | undefined = mapInstance.getSource('geojson-data');
            if (!source) return false;
            const serializedData = source.serialize().data;
            const collection = typeof serializedData === 'string' ? null : (serializedData as GeoJsonFeatureCollection);
            if (collection && collection.features.length > 0) {
                cachedGeoJsonData.value = collection;
                return true;
            }
            return false;
        };

        if (isInitialSetup || isStyleBased) {
            await applyTileSource(layerValue);
            restoreMapView(map.value, mapState.center, mapState.zoom, mapState.pitch, mapState.bearing);
            await waitForMapEvent('idle');
            map.value.setMaxZoom(MAX_ZOOM_LEVEL);
            updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);

            let featuresRestored = await restoreAndVerify();

            if (hadFeaturesToRestore && !featuresRestored) {
                console.log('Features were not restored, attempting to restore from cached data');
                featuresRestored = await restoreAndVerify();
                if (!featuresRestored && !hasLoadedBounds()) {
                    console.log('Restoration failed, loading from API (no cached bounds)');
                    await loadDataForCurrentView();
                }
            }

            await applyTerrainAndHillshade(layerValue);
            return;
        }

        // Raster-based layer switching: completely reset the map.
        destroyMap();

        await new Promise((resolve) => setTimeout(resolve, 0));
        try {
            await createMapInstance({
                center: [mapState.center.lng, mapState.center.lat],
                zoom: mapState.zoom,
                pitch: mapState.pitch,
                bearing: mapState.bearing,
            });

            await waitForMapEvent('load');
            await applyTileSource(layerValue);

            restoreMapView(map.value, mapState.center, mapState.zoom, mapState.pitch, mapState.bearing);
            map.value.setMaxZoom(MAX_ZOOM_LEVEL);
            updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);

            await waitForMapEvent('idle');

            let featuresRestored = await restoreAndVerify();

            if (hadFeaturesToRestore && !featuresRestored) {
                console.log('Features were not restored, attempting to restore from cached data');
                featuresRestored = await restoreAndVerify();
                if (!featuresRestored && !hasLoadedBounds()) {
                    console.log('Restoration failed, loading from API (no cached bounds)');
                    await loadDataForCurrentView();
                }
            } else if (!hadFeaturesToRestore && !featuresRestored && !hasLoadedBounds()) {
                await loadDataForCurrentView();
            }

            await applyTerrainAndHillshade(layerValue);
            ensureMapResize();
        } catch (error) {
            console.error('Error switching map layer:', error);
            setLoadError(error instanceof Error ? error.message : 'Failed to switch map layer');
        }
    }

    return {
        tileSources,
        selectedLayer,
        maptilerConfig,
        terrainEnabled,
        showTerrainTooltip,
        hillshadeEnabled,
        fetchTileSources,
        fetchMaptilerConfig,
        shouldApplyAtmosphere,
        addHillshadeIfNeeded,
        removeHillshade,
        removeTerrain,
        setupTerrain,
        toggleTerrain,
        applyTileSource,
        applyTerrainAndHillshade,
        handleHillshadeChange,
        handleLabelsVisibilityChange,
        switchMapLayer,
    };
}

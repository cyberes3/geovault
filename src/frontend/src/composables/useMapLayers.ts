/**
 * Vue bindings for TileRuntime: basemap switch, terrain, hillshade, and label visibility.
 */
import { ref, shallowRef, type Ref, type ShallowRef } from 'vue';
import type { Map as MapLibreMap } from 'maplibre-gl';
import type { TileSource } from '@/api/services/tilesApi';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { MapTilerConfig } from '@/utils/map/maplibre/maptilerIntegration.js';
import { restoreMapView, getMapState } from '@/utils/map/maplibre/layerSwitching.js';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import type { FeatureSource, HiddenIdSet } from '@/utils/map/common/FeatureSource';
import type { MapRuntime } from '@/utils/map/common/MapRuntime';
import type { TileRuntime } from '@/utils/map/common/TileRuntime';

export interface UseMapLayersDeps {
    map: ShallowRef<MapLibreMap | null>;
    labelMarkerManager: ShallowRef<LabelMarkerManager | null>;
    showAllLabels: Ref<boolean>;
    ensureMapResize: () => void;
    updateLayerMaxZoom: (minMaxZoom?: number) => void;
    getDefaultBasemap: () => string | undefined;
    hasLoadedBounds: () => boolean;
    loadDataForCurrentView: () => Promise<void>;
    onAfterFeaturesChanged: () => void;
    setLoadError: (message: string) => void;
    tiles: TileRuntime;
    runtime: MapRuntime;
    featureSource: FeatureSource;
    hiddenFeatures: HiddenIdSet;
}

export function useMapLayers(deps: UseMapLayersDeps) {
    const {
        map,
        labelMarkerManager,
        showAllLabels,
        ensureMapResize,
        updateLayerMaxZoom,
        getDefaultBasemap,
        hasLoadedBounds,
        loadDataForCurrentView,
        onAfterFeaturesChanged,
        setLoadError,
        tiles,
        runtime,
        featureSource,
        hiddenFeatures,
    } = deps;

    const tileSources: Ref<TileSource[]> = ref([]);
    const selectedLayer = ref('osm');
    const maptilerConfig: ShallowRef<MapTilerConfig | null> = shallowRef(null);
    const terrainEnabled = ref(false);
    const showTerrainTooltip = ref(false);
    const hillshadeEnabled = ref(false);

    function syncFromTiles(): void {
        tileSources.value = tiles.sources;
        if (tiles.selectedId) {
            selectedLayer.value = tiles.selectedId;
        }
        maptilerConfig.value = tiles.maptiler;
        terrainEnabled.value = tiles.terrainEnabled;
        hillshadeEnabled.value = tiles.hillshadeEnabled;
    }

    function commitFeatures(): void {
        featureSource.commit(hiddenFeatures);
        onAfterFeaturesChanged();
        if (showAllLabels.value && labelMarkerManager.value) {
            labelMarkerManager.value.updateMarkers(featureSource.buildRenderCollection(hiddenFeatures).features);
        }
    }

    async function fetchTileSources(): Promise<TileSource[]> {
        try {
            await tiles.loadCatalog(getDefaultBasemap());
            syncFromTiles();
            return tiles.sources;
        } catch (error) {
            console.error('Error fetching tile sources:', error);
            tileSources.value = [];
            setLoadError('Unable to load map tile sources.');
            return [];
        }
    }

    async function fetchMaptilerConfig(): Promise<void> {
        maptilerConfig.value = tiles.maptiler;
    }

    function applyUserTerrainDefaults(enableTerrain: boolean, enableHillshade: boolean): void {
        tiles.terrainEnabled = enableTerrain;
        tiles.hillshadeEnabled = enableHillshade;
        syncFromTiles();
    }

    function addHillshadeIfNeeded(): void {
        tiles.setHillshade(map.value, tiles.hillshadeEnabled);
        syncFromTiles();
    }

    function removeHillshade(): void {
        tiles.setHillshade(map.value, false);
        syncFromTiles();
    }

    function removeTerrain(): void {
        tiles.terrainEnabled = false;
        void tiles.applyTerrainAndHillshade(map.value);
        syncFromTiles();
    }

    async function setupTerrain(): Promise<void> {
        tiles.terrainEnabled = true;
        await tiles.applyTerrainAndHillshade(map.value);
        syncFromTiles();
    }

    async function toggleTerrain(): Promise<void> {
        if (!map.value) return;
        if (!tiles.terrainEnabled) {
            await setupTerrain();
            map.value.easeTo({ pitch: 50, duration: 800 });
            if (!showTerrainTooltip.value) {
                showTerrainTooltip.value = true;
                setTimeout(() => {
                    showTerrainTooltip.value = false;
                }, 3000);
            }
        } else {
            removeTerrain();
            map.value.easeTo({ pitch: 0, duration: 800 });
        }
    }

    async function applyTileSource(layerValue: string): Promise<void> {
        await tiles.switchBasemap(runtime, layerValue);
        syncFromTiles();
        updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);
    }

    async function applyTerrainAndHillshade(_layerValue?: string): Promise<void> {
        await tiles.applyTerrainAndHillshade(map.value);
        syncFromTiles();
    }

    function handleHillshadeChange(enabled: boolean): void {
        tiles.setHillshade(map.value, enabled);
        syncFromTiles();
    }

    async function handleLabelsVisibilityChange(showLabels: boolean, loadedBoundsClear: () => void): Promise<void> {
        showAllLabels.value = showLabels;
        if (!labelMarkerManager.value) return;

        labelMarkerManager.value.setVisibility(showLabels);

        if (showLabels) {
            loadedBoundsClear();
            await loadDataForCurrentView();
            return;
        }

        featureSource.clearLabelSynthetics();
        commitFeatures();
    }

    async function switchMapLayer(layerValue: string, isInitialSetup = false): Promise<void> {
        if (!map.value) return;

        const mapState = getMapState(map.value);
        if (!mapState) return;

        const hadFeaturesToRestore = featureSource.size() > 0;
        if (!tiles.sources.some((source) => source.id === layerValue)) {
            console.error(`Tile source not found: ${layerValue}`);
            return;
        }

        try {
            await applyTileSource(layerValue);
            restoreMapView(map.value, mapState.center, mapState.zoom, mapState.pitch, mapState.bearing);
            await runtime.waitForIdle();
            map.value.setMaxZoom(MAX_ZOOM_LEVEL);
            updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);

            if (hadFeaturesToRestore) {
                runtime.ensureGeoJsonSource();
                commitFeatures();
            } else if (!hasLoadedBounds() && !isInitialSetup) {
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
        applyUserTerrainDefaults,
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

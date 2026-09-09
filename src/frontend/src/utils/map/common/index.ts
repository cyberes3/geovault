import { loadMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { resolveMapStyle, createTransformRequest } from '@/utils/map/maplibre/mapInitialization.js';
import { tileSourceCatalog } from '@/utils/map/tileSources/sharedCatalog.js';
import { FeatureSource } from './FeatureSource';
import { GeoJsonSourceHandle } from './GeoJsonSourceHandle';
import { MapRuntime } from './MapRuntime';
import { MapCamera } from './MapCamera';
import { TileRuntime } from './TileRuntime';
import { LocationRuntime } from './LocationRuntime';
import { MapLibrePreviewMap, createGeoJsonPreviewMap, createPointPickerMap } from './MapLibrePreviewMap';
import { WORLD_VIEW_CENTER_LONLAT, WORLD_VIEW_ZOOM } from '@/utils/map/worldViewDefault';

export { FeatureSource } from './FeatureSource';
export { GeoJsonSourceHandle } from './GeoJsonSourceHandle';
export { MapRuntime } from './MapRuntime';
export { MapCamera } from './MapCamera';
export { TileRuntime } from './TileRuntime';
export { LocationRuntime } from './LocationRuntime';
export { MapLibrePreviewMap, createGeoJsonPreviewMap, createPointPickerMap } from './MapLibrePreviewMap';
export { canonicalFeatureId, isSyntheticFeature } from './featureIdentity';
export { tileSourceCatalog } from '@/utils/map/tileSources/sharedCatalog.js';

export const worldView = {
    center: WORLD_VIEW_CENTER_LONLAT,
    zoom: WORLD_VIEW_ZOOM,
};

export async function loadEngine(engine: 'maplibre'): Promise<unknown> {
    if (engine !== 'maplibre') {
        throw new Error('Only MapLibre is loaded by the map kernel');
    }
    return loadMaplibreGl();
}

export const mapCommonApi = {
    loadEngine,
    resolveMapStyle,
    createTransformRequest,
    FeatureSource,
    GeoJsonSourceHandle,
    MapRuntime,
    MapCamera,
    TileRuntime,
    LocationRuntime,
    MapLibrePreviewMap,
    createGeoJsonPreviewMap,
    createPointPickerMap,
    useTileSources: () => tileSourceCatalog.load(),
    worldView,
};

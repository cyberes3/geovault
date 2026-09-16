import type { Map as MapLibreMap, AddLayerObject } from 'maplibre-gl';
import { BASE_TILE_LAYER_IDS, FEATURE_LAYER_STACK, GEOJSON_SOURCE_ID } from '@/utils/map/mapLayers';
import { FEATURE_LAYER_CONFIGS } from './featureLayerSpec';

const registeredEpochs = new WeakMap<object, number>();

function enforceLayerOrder(map: MapLibreMap): void {
    for (const baseId of BASE_TILE_LAYER_IDS) {
        if (!map.getLayer(baseId)) continue;
        const first = map.getStyle().layers[0];
        if (first && first.id !== baseId) {
            map.moveLayer(baseId, first.id);
        }
    }

    for (const layerId of FEATURE_LAYER_STACK) {
        if (map.getLayer(layerId)) {
            map.moveLayer(layerId);
        }
    }
}

/** Register the feature overlay stack once for a style epoch. No source → no layers. */
export function registerFeatureLayers(map: MapLibreMap | null | undefined, styleEpoch: number): void {
    if (!map?.getSource(GEOJSON_SOURCE_ID)) return;
    if (registeredEpochs.get(map) === styleEpoch) {
        const missing = FEATURE_LAYER_STACK.some((id) => !map.getLayer(id));
        if (!missing) return;
    }

    for (const layerId of FEATURE_LAYER_STACK) {
        if (map.getLayer(layerId)) continue;
        const config = FEATURE_LAYER_CONFIGS[layerId]?.();
        if (!config) continue;
        map.addLayer(config as unknown as AddLayerObject);
    }

    enforceLayerOrder(map);
    registeredEpochs.set(map, styleEpoch);
}

export function ensureLayersExist(map: MapLibreMap | null | undefined): void {
    registerFeatureLayers(map, registeredEpochs.get(map as object) ?? -1);
}

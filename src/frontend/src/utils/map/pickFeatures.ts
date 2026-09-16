import type { Map as MapLibreMap, PointLike } from 'maplibre-gl';
import { FEATURE_LAYER_STACK } from '@/utils/map/mapLayers';
import { canonicalFeatureId, isSyntheticFeature, originalFeatureId } from '@/utils/map/common/featureIdentity';
import type { FeatureSource } from '@/utils/map/common/FeatureSource';
import type { RenderFeature } from '@/utils/map/common/types';

export interface MapPoint {
    x: number;
    y: number;
}

/** Query feature layers at a screen point. Skip labels, resolve replacements, dedupe. */
export function pickFeaturesAtPoint(
    map: MapLibreMap,
    point: MapPoint,
    radiusPx: number,
    featureSource: FeatureSource,
): RenderFeature[] {
    const layers = FEATURE_LAYER_STACK.filter((id) => map.getLayer(id));
    if (layers.length === 0) return [];

    const bbox: [PointLike, PointLike] = [
        [point.x - radiusPx, point.y - radiusPx],
        [point.x + radiusPx, point.y + radiusPx],
    ];
    const hits = map.queryRenderedFeatures(bbox, { layers }) as unknown as RenderFeature[];
    const rendered = featureSource.buildRenderCollection();
    const resolved: RenderFeature[] = [];
    const seen = new Set<string>();

    for (const hit of hits) {
        if (hit.properties?._isLabelPoint) continue;

        let feature = hit;
        if (hit.properties?._isSmallFeatureReplacement) {
            const parent = originalFeatureId(hit);
            const original = rendered.features.find((candidate) => (
                canonicalFeatureId(candidate) === parent && !isSyntheticFeature(candidate)
            ));
            if (original) {
                feature = original as RenderFeature;
            }
        }

        const id = canonicalFeatureId(feature);
        if (id) {
            if (seen.has(id)) continue;
            seen.add(id);
        }
        resolved.push(feature);
    }

    return resolved;
}

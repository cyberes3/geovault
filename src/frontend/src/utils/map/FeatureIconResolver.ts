import type { Map as MapLibreMap } from 'maplibre-gl';
import { getFeatureIconUrl, getIconSourceUrl, loadIconImage } from '@/utils/map/maplibre/featureLayerSpec';
import type { FeatureSource, HiddenIdSet } from '@/utils/map/common/FeatureSource';

/**
 * Loads `icon-*` images through MapLibre's missing-style-image resolver.
 * Non-icon ids return immediately.
 */
export class FeatureIconResolver {
    private readonly features: FeatureSource;
    private readonly hidden?: HiddenIdSet | null;

    constructor(features: FeatureSource, hidden?: HiddenIdSet | null) {
        this.features = features;
        this.hidden = hidden;
    }

    attach(map: MapLibreMap): void {
        map.setMissingStyleImageResolver((id: string) => this.resolve(map, id));
    }

    resolve(map: MapLibreMap, iconId: string): void | Promise<void> {
        if (!iconId.startsWith('icon-')) return;

        const features = this.features.buildRenderCollection(this.hidden).features;
        for (const feature of features) {
            if (feature.properties?.['_icon-id'] !== iconId) continue;
            const iconUrl = getFeatureIconUrl(feature.properties);
            if (!iconUrl) return;
            const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties);
            return loadIconImage(map, iconId, resolvedUrl).catch((error: unknown) => {
                console.warn(`Failed to load missing icon ${iconId}:`, error);
            });
        }
    }
}

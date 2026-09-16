import type { VaultFeature } from '@/contracts/feature';
import { getFeature } from '@/api/services/featuresApi';
import { FeatureSource } from '@/utils/map/common/FeatureSource';
import { HiddenFeatureSet } from './HiddenFeatureSet';
import { ElevationStore } from './ElevationStore';

import type { IngestContext } from '@/utils/map/common/types';

export class FeatureMutation {
    private readonly source: FeatureSource;
    private readonly hidden: HiddenFeatureSet;
    private readonly elevations: ElevationStore;
    private readonly canWrite: () => boolean;
    private readonly getIngestContext: () => IngestContext;

    constructor(
        source: FeatureSource,
        hidden: HiddenFeatureSet,
        elevations: ElevationStore,
        canWrite: () => boolean,
        getIngestContext: () => IngestContext,
    ) {
        this.source = source;
        this.hidden = hidden;
        this.elevations = elevations;
        this.canWrite = canWrite;
        this.getIngestContext = getIngestContext;
    }

    applyPatch(feature: VaultFeature): void {
        if (!this.canWrite()) return;
        this.elevations.capture(feature);
        this.source.ingest([feature], this.getIngestContext(), this.hidden);
        this.source.commit(this.hidden);
    }

    add(feature: VaultFeature): void {
        this.applyPatch(feature);
    }

    remove(id: string): void {
        this.hidden.delete(id);
        this.elevations.delete(id);
        this.source.remove(id);
        this.source.commit(this.hidden);
    }

    hide(id: string): void {
        if (!this.canWrite()) return;
        this.hidden.add(id);
        this.source.remove(id);
        this.source.commit(this.hidden);
    }

    async refreshFromServer(id: string): Promise<VaultFeature | null> {
        const data = await getFeature(id) as { feature?: { geojson?: VaultFeature; id?: string | number } };
        const geojson = data.feature?.geojson;
        if (!geojson) return null;
        const feature: VaultFeature = {
            ...geojson,
            properties: { ...geojson.properties, database_id: data.feature?.id ?? id },
        };
        this.applyPatch(feature);
        return this.source.get(id) ?? feature;
    }
}

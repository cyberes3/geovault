import type { VaultFeature } from '@/contracts/feature';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { getFeaturesInBbox, getFeature, getExtentHint } from '@/api/services/featuresApi';
import { getPublicShareTagFeatures, getPublicShareCollectionFeatures, getPublicShareFeature } from '@/api/services/sharingApi';
import { isAbortError } from '@/utils/apiError';
import { FeatureSource } from '@/utils/map/common/FeatureSource';
import { getBoundingBoxString } from '@/utils/map/maplibre/mapUtils.js';
import { FeatureViewportCache } from './FeatureViewportCache';
import { HiddenFeatureSet } from './HiddenFeatureSet';
import type { LoadContext } from './types';
import { ViewportKey } from './ViewportKey';

export interface LoadRequest {
    context: LoadContext;
    bbox: [number, number, number, number] | null;
    zoom: number;
    replaceSource?: boolean;
    force?: boolean;
}

export interface LoadResult {
    generation: number;
    features: VaultFeature[];
    fromCache: boolean;
    truncated: boolean;
    fallbackUsed: boolean;
    empty: boolean;
}

export class LoadPipeline {
    generation = 0;
    private abort: AbortController | null = null;

    constructor(
        private readonly source: FeatureSource,
        private readonly cache: FeatureViewportCache,
        private readonly hidden: HiddenFeatureSet,
    ) {}

    cancel(): void {
        this.generation += 1;
        this.abort?.abort();
        this.abort = null;
    }

    async load(request: LoadRequest): Promise<LoadResult | null> {
        const generation = ++this.generation;
        this.abort?.abort();
        this.abort = new AbortController();
        const signal = this.abort.signal;

        const replaceSource = request.replaceSource ?? request.context.replaceSource;
        const key = ViewportKey.from({
            bbox: request.context.spatial === 'global' ? null : request.bbox,
            matchMode: 'tags' in request.context ? request.context.matchMode : undefined,
            tags: 'tags' in request.context ? request.context.tags : null,
            shareId: request.context.kind === 'share' ? request.context.shareId : null,
            collectionId: request.context.kind === 'collection' ? request.context.collectionId : null,
            spatial: request.context.spatial,
        });

        const cached = !request.force ? this.cache.get(key) : undefined;
        if (cached) {
            if (generation !== this.generation) return null;
            if (replaceSource) this.source.clear();
            this.source.upsert(cached, this.hidden);
            return {
                generation,
                features: cached,
                fromCache: true,
                truncated: false,
                fallbackUsed: false,
                empty: cached.length === 0,
            };
        }

        try {
            const fetched = await this.fetch(request, signal);
            if (generation !== this.generation) return null;
            this.cache.set(key, fetched.features);
            if (replaceSource) this.source.clear();
            this.source.upsert(fetched.features, this.hidden);
            return { generation, fromCache: false, ...fetched };
        } catch (error) {
            if (isAbortError(error) || generation !== this.generation) return null;
            throw error;
        } finally {
            if (generation === this.generation) {
                this.abort = null;
            }
        }
    }

    async loadExtentHint(): Promise<[number, number, number, number] | null> {
        const payload = await getExtentHint();
        const bbox = payload.bbox;
        if (!Array.isArray(bbox)) return null;
        const values = bbox.map(Number) as [number, number, number, number];
        if (!values.every((value) => Number.isFinite(value))) return null;
        return values;
    }

    private async fetch(request: LoadRequest, signal: AbortSignal): Promise<Omit<LoadResult, 'generation' | 'fromCache'>> {
        const context = request.context;
        if (context.kind === 'featureFocus') {
            const data = await getFeature(context.featureId) as { feature?: { geojson?: VaultFeature; id?: string | number } };
            const geojson = data.feature?.geojson;
            if (!geojson) {
                return { features: [], truncated: false, fallbackUsed: false, empty: true };
            }
            const feature: VaultFeature = {
                ...geojson,
                properties: { ...geojson.properties, database_id: data.feature?.id ?? context.featureId },
            };
            return { features: [feature], truncated: false, fallbackUsed: false, empty: false };
        }

        if (context.kind === 'share' && context.shareType === 'feature') {
            const result = await getPublicShareFeature(context.shareId, signal);
            const features = (result.features ?? []) as VaultFeature[];
            return { features, truncated: false, fallbackUsed: false, empty: features.length === 0 };
        }

        const bbox = request.bbox ?? [-180, -85.05112878, 180, 85.05112878];
        const bboxString = getBoundingBoxString(bbox);
        let collection: GeoJsonFeatureCollection | null = null;
        let truncated = false;
        let fallbackUsed = false;

        if (context.kind === 'share' && context.shareType === 'tag') {
            const result = await getPublicShareTagFeatures(context.shareId, bboxString, request.zoom, signal);
            collection = result.data;
            truncated = !!(result as { truncated?: boolean }).truncated;
            fallbackUsed = !!(result as { fallback_used?: boolean }).fallback_used;
        } else if (context.kind === 'share' && context.shareType === 'collection') {
            const result = await getPublicShareCollectionFeatures(context.shareId, bboxString, request.zoom, signal);
            collection = result.data;
            truncated = !!(result as { truncated?: boolean }).truncated;
            fallbackUsed = !!(result as { fallback_used?: boolean }).fallback_used;
        } else {
            const result = await getFeaturesInBbox({
                bbox: bboxString,
                zoom: request.zoom,
                collection: context.kind === 'collection' ? context.collectionId : null,
                tags: 'tags' in context && context.tags.length > 0 ? context.tags : null,
                matchMode: 'matchMode' in context ? context.matchMode : undefined,
                signal,
            });
            collection = result.data;
            truncated = !!(result as { truncated?: boolean }).truncated;
            fallbackUsed = !!(result as { fallback_used?: boolean }).fallback_used;
        }

        const features = (collection?.features ?? []) as VaultFeature[];
        return {
            features,
            truncated,
            fallbackUsed,
            empty: features.length === 0,
        };
    }
}

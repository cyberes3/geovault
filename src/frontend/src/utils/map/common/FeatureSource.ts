import type { VaultFeature } from '@/contracts/feature';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { filterFeaturesByBounds } from '@/utils/map/featureExtent';
import { filterPointsOnBorders } from '@/utils/map/maplibre/featureFiltering';
import type { MapFeature } from '@/utils/map/maplibre/mapFeatureTypes';
import { canonicalFeatureId, cloneFeature, isSyntheticFeature, originalFeatureId, stripRuntimeProperties } from './featureIdentity';
import type { GeoJsonSourceHandle } from './GeoJsonSourceHandle';
import type { FeatureRuntimeOverlay, RenderFeature } from './types';

export interface HiddenIdSet {
    has(id: string): boolean;
}

export interface FeatureBoundsLike {
    getWest(): number;
    getEast(): number;
    getSouth(): number;
    getNorth(): number;
}

/**
 * Canonical in-memory feature store. MapLibre is a render sink, not the source of truth.
 */
export class FeatureSource {
    private readonly features = new Map<string, VaultFeature>();
    private readonly runtime = new Map<string, FeatureRuntimeOverlay>();
    private readonly synthetics = new Map<string, RenderFeature>();
    private readonly borderCheckedIds = new Set<string>();
    private readonly borderSuppressedIds = new Set<string>();
    private generation = 0;
    private sink: GeoJsonSourceHandle | null = null;

    attachSink(sink: GeoJsonSourceHandle | null): void {
        this.sink = sink;
    }

    getGeneration(): number {
        return this.generation;
    }

    size(): number {
        return this.features.size;
    }

    has(id: string): boolean {
        return this.features.has(String(id));
    }

    get(id: string): VaultFeature | undefined {
        return this.features.get(String(id));
    }

    ids(): string[] {
        return Array.from(this.features.keys());
    }

    clear(): void {
        this.features.clear();
        this.runtime.clear();
        this.synthetics.clear();
        this.borderCheckedIds.clear();
        this.borderSuppressedIds.clear();
        this.generation += 1;
    }

    upsert(incoming: VaultFeature[], hidden?: HiddenIdSet | null): string[] {
        const accepted: string[] = [];
        for (const feature of incoming) {
            if (isSyntheticFeature(feature)) continue;
            const id = canonicalFeatureId(feature);
            if (!id) continue;
            if (hidden?.has(id)) {
                this.remove(id);
                continue;
            }
            this.features.set(id, cloneFeature(feature));
            accepted.push(id);
        }
        if (accepted.length > 0) {
            this.generation += 1;
        }
        return accepted;
    }

    /** Removes a feature and every synthetic that points at it. */
    remove(id: string): boolean {
        const key = String(id);
        const existed = this.features.delete(key);
        this.runtime.delete(key);
        this.borderCheckedIds.delete(key);
        this.borderSuppressedIds.delete(key);
        for (const [syntheticId, synthetic] of this.synthetics) {
            if (originalFeatureId(synthetic) === key || syntheticId.startsWith(`${key}:`)) {
                this.synthetics.delete(syntheticId);
            }
        }
        if (existed) {
            this.generation += 1;
        }
        return existed;
    }

    removeHidden(hidden: HiddenIdSet): string[] {
        const removed: string[] = [];
        for (const id of this.ids()) {
            if (hidden.has(id)) {
                this.remove(id);
                removed.push(id);
            }
        }
        return removed;
    }

    setRuntime(id: string, overlay: FeatureRuntimeOverlay): void {
        this.runtime.set(String(id), { ...this.runtime.get(String(id)), ...overlay });
    }

    getRuntime(id: string): FeatureRuntimeOverlay | undefined {
        return this.runtime.get(String(id));
    }

    setSynthetic(key: string, feature: RenderFeature): void {
        this.synthetics.set(key, feature);
        this.generation += 1;
    }

    removeSynthetic(key: string): void {
        if (this.synthetics.delete(key)) {
            this.generation += 1;
        }
    }

    clearLabelSynthetics(): void {
        for (const key of [...this.synthetics.keys()]) {
            if (key.endsWith(':label')) {
                this.synthetics.delete(key);
            }
        }
        this.generation += 1;
    }

    clearSynthetics(prefix?: string): void {
        if (!prefix) {
            this.synthetics.clear();
            this.generation += 1;
            return;
        }
        for (const key of this.synthetics.keys()) {
            if (key.startsWith(prefix)) {
                this.synthetics.delete(key);
            }
        }
        this.generation += 1;
    }

    ensureLabelPoints(ids: string[], factory: (feature: VaultFeature) => RenderFeature | null): void {
        for (const id of ids) {
            const key = `${id}:label`;
            if (this.synthetics.has(key)) continue;
            const feature = this.features.get(id);
            if (!feature) continue;
            const label = factory(feature);
            if (label) {
                this.synthetics.set(key, label);
            }
        }
    }

    exportCanonical(): VaultFeature[] {
        return Array.from(this.features.values()).map((feature) => ({
            type: 'Feature',
            geometry: feature.geometry,
            properties: stripRuntimeProperties(feature.properties as Record<string, unknown>),
            geojson_hash: feature.geojson_hash,
        }));
    }

    buildRenderCollection(hidden?: HiddenIdSet | null): GeoJsonFeatureCollection {
        const rendered: RenderFeature[] = [];
        for (const [id, feature] of this.features) {
            if (hidden?.has(id)) continue;
            rendered.push(this.toRenderFeature(id, feature));
        }
        this.applyIncrementalBorderFilter(rendered);
        const visible = rendered.filter((feature) => {
            const id = canonicalFeatureId(feature);
            return !id || !this.borderSuppressedIds.has(id);
        });
        for (const synthetic of this.synthetics.values()) {
            const parent = originalFeatureId(synthetic);
            if (parent && (hidden?.has(parent) || !this.features.has(parent))) continue;
            visible.push(synthetic);
        }
        return { type: 'FeatureCollection', features: visible as GeoJsonFeatureCollection['features'] };
    }

    featuresInBounds(bounds: FeatureBoundsLike, hidden?: HiddenIdSet | null): VaultFeature[] {
        const collection = this.buildRenderCollection(hidden);
        return filterFeaturesByBounds(collection.features, bounds as never, true, true);
    }

    commit(hidden?: HiddenIdSet | null): void {
        if (!this.sink) return;
        this.sink.setData(this.buildRenderCollection(hidden));
    }

    private toRenderFeature(id: string, feature: VaultFeature): RenderFeature {
        const overlay = this.runtime.get(id);
        const properties: RenderFeature['properties'] = { ...feature.properties, database_id: feature.properties.database_id ?? feature.properties.feature_ref ?? id };
        if (overlay?.iconId) properties['_icon-id'] = overlay.iconId;
        if (overlay?.tooSmall) properties._isTooSmall = true;
        if (overlay?.elevation !== undefined) properties._elevation = overlay.elevation;
        if (overlay?.elevations) properties._elevations = overlay.elevations;
        if (overlay?.coordinateTimes) {
            properties._coordinateProperties = { times: overlay.coordinateTimes };
        }
        if (overlay?.detectedIconColor) properties._detectedIconColor = overlay.detectedIconColor;
        return {
            type: 'Feature',
            id,
            geometry: feature.geometry,
            properties,
            geojson_hash: feature.geojson_hash,
        };
    }

    private applyIncrementalBorderFilter(features: RenderFeature[]): void {
        const newPoints = features.filter((feature) => {
            if (feature.geometry.type !== 'Point' || isSyntheticFeature(feature)) return false;
            const id = canonicalFeatureId(feature);
            return !!id && !this.borderCheckedIds.has(id);
        });
        if (newPoints.length === 0) return;

        const filtered = filterPointsOnBorders(features as MapFeature[]) as RenderFeature[];
        const kept = new Set(filtered.map((feature) => canonicalFeatureId(feature)).filter((id): id is string => !!id));
        for (const point of newPoints) {
            const id = canonicalFeatureId(point);
            if (!id) continue;
            this.borderCheckedIds.add(id);
            if (!kept.has(id)) {
                this.borderSuppressedIds.add(id);
            }
        }
    }
}

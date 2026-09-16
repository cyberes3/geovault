import type { Geometry } from 'geojson';
import type { VaultFeature } from '@/contracts/feature';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';
import { filterFeaturesByBounds } from '@/utils/map/featureExtent';
import { getCoordinatesFromGeometry } from '@/utils/map/geometry';
import { filterPointsOnBorders } from '@/utils/map/maplibre/featureFiltering';
import { calculateLineCenter, calculatePolygonCentroid } from '@/utils/map/maplibre/labelPlacement';
import { getFeatureIconUrl, getIconSourceUrl, iconRuntimeId, shouldUseIcon } from '@/utils/map/maplibre/featureLayerSpec';
import type { MapFeature } from '@/utils/map/maplibre/mapFeatureTypes';
import { canonicalFeatureId, cloneFeature, isSyntheticFeature, originalFeatureId, stripRuntimeProperties } from './featureIdentity';
import type { GeoJsonSourceHandle } from './GeoJsonSourceHandle';
import type { FeatureRuntimeOverlay, IngestContext, RenderFeature } from './types';

export interface HiddenIdSet {
    has(id: string): boolean;
}

export interface FeatureBoundsLike {
    getWest(): number;
    getEast(): number;
    getSouth(): number;
    getNorth(): number;
}

export type { IngestContext };

const MIN_PIXEL_SIZE = 2;

function mercatorY(lat: number): number {
    const rad = (lat * Math.PI) / 180;
    return Math.log(Math.tan(Math.PI / 4 + rad / 2));
}

function worldPixels(zoom: number, viewSize: { width: number; height: number }): number {
    const tileWorld = 256 * Math.pow(2, zoom);
    return Math.max(tileWorld, viewSize.width, viewSize.height);
}

function polygonScreenSize(geometry: VaultFeature['geometry'], ctx: IngestContext): { width: number; height: number } {
    const coords = getCoordinatesFromGeometry(geometry as Geometry);
    if (coords.length === 0) return { width: 0, height: 0 };
    let minLon = Infinity;
    let minLat = Infinity;
    let maxLon = -Infinity;
    let maxLat = -Infinity;
    for (const [lon, lat] of coords) {
        minLon = Math.min(minLon, lon);
        minLat = Math.min(minLat, lat);
        maxLon = Math.max(maxLon, lon);
        maxLat = Math.max(maxLat, lat);
    }
    const world = worldPixels(ctx.zoom, ctx.viewSize);
    return {
        width: ((maxLon - minLon) / 360) * world,
        height: (Math.abs(mercatorY(maxLat) - mercatorY(minLat)) / (2 * Math.PI)) * world,
    };
}

function lineScreenSize(geometry: VaultFeature['geometry'], ctx: IngestContext): number {
    const coords = getCoordinatesFromGeometry(geometry as Geometry);
    if (coords.length < 2) return 0;
    const world = worldPixels(ctx.zoom, ctx.viewSize);
    let pixels = 0;
    for (let i = 1; i < coords.length; i += 1) {
        const [lon0, lat0] = coords[i - 1];
        const [lon1, lat1] = coords[i];
        const dx = ((lon1 - lon0) / 360) * world;
        const dy = ((mercatorY(lat1) - mercatorY(lat0)) / (2 * Math.PI)) * world;
        pixels += Math.hypot(dx, dy);
    }
    return pixels;
}

function thirdCoord(coord: unknown): unknown {
    return Array.isArray(coord) && coord.length >= 3 && coord[2] != null ? coord[2] : undefined;
}

function preserveElevation(feature: VaultFeature): void {
    const geometry = feature.geometry;
    const properties = feature.properties as Record<string, unknown>;
    const coords = geometry.coordinates as unknown;

    if (geometry.type === 'Point') {
        const elevation = thirdCoord(coords);
        if (elevation !== undefined) properties._elevation = elevation;
        return;
    }
    if (geometry.type === 'MultiPoint' && Array.isArray(coords) && coords.length > 0) {
        const elevation = thirdCoord(coords[0]);
        if (elevation !== undefined) properties._elevation = elevation;
        return;
    }
    if (geometry.type === 'LineString' && Array.isArray(coords)) {
        const elevations = coords.map(thirdCoord).filter((value) => value !== undefined);
        if (elevations.length > 0) properties._elevations = elevations;
    } else if (geometry.type === 'MultiLineString' && Array.isArray(coords)) {
        const elevations: unknown[] = [];
        for (const line of coords) {
            if (!Array.isArray(line)) continue;
            for (const coord of line) {
                const elevation = thirdCoord(coord);
                if (elevation !== undefined) elevations.push(elevation);
            }
        }
        if (elevations.length > 0) properties._elevations = elevations;
    }

    const times = (properties.coordinateProperties as { times?: unknown } | undefined)?.times;
    if (times) {
        properties._coordinateProperties = { times };
    }
}

/**
 * Canonical in-memory feature store. MapLibre is a render sink, not the source of truth.
 * The only render path is ingest() then commit().
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
            const cloned = cloneFeature(feature);
            preserveElevation(cloned);
            this.features.set(id, cloned);
            accepted.push(id);
        }
        if (accepted.length > 0) {
            this.generation += 1;
        }
        return accepted;
    }

    ingest(incoming: VaultFeature[], ctx: IngestContext, hidden?: HiddenIdSet | null): string[] {
        const accepted = this.upsert(incoming, hidden);
        this.applyBorderFilterStage();
        this.applyLabelSynthetics(ctx);
        this.applyIconRuntimeIds(ctx);
        this.applySmallFeatureReplacements(ctx);
        return accepted;
    }

    refreshZoomDependent(ctx: IngestContext): void {
        this.applyIconRuntimeIds(ctx);
        this.applySmallFeatureReplacements(ctx);
    }

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
        this.removeSyntheticsBySuffix(':label');
    }

    clearSynthetics(prefix?: string): void {
        if (!prefix) {
            this.synthetics.clear();
            this.generation += 1;
            return;
        }
        for (const key of [...this.synthetics.keys()]) {
            if (key.startsWith(prefix)) {
                this.synthetics.delete(key);
            }
        }
        this.generation += 1;
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
        const properties: RenderFeature['properties'] = {
            ...feature.properties,
            database_id: id,
        };
        if (overlay?.iconId) properties['_icon-id'] = overlay.iconId;
        else delete properties['_icon-id'];
        if (overlay?.tooSmall) properties._isTooSmall = true;
        else delete properties._isTooSmall;
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

    private applyBorderFilterStage(): void {
        const rendered: RenderFeature[] = [];
        for (const [id, feature] of this.features) {
            rendered.push(this.toRenderFeature(id, feature));
        }
        this.applyIncrementalBorderFilter(rendered);
    }

    private applyLabelSynthetics(ctx: IngestContext): void {
        if (!ctx.showLabels) {
            this.clearLabelSynthetics();
            return;
        }
        for (const [id, feature] of this.features) {
            if (this.borderSuppressedIds.has(id)) continue;
            const key = `${id}:label`;
            if (this.synthetics.has(key)) continue;
            const name = feature.properties?.name;
            if (!name || String(name).trim() === '') continue;
            const geometryType = feature.geometry.type;
            let coordinates: number[] | null = null;
            if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
                coordinates = calculatePolygonCentroid(feature.geometry as never);
            } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
                coordinates = calculateLineCenter(feature.geometry as never);
            }
            if (!coordinates) continue;
            this.synthetics.set(key, {
                type: 'Feature',
                id: `label-point-${id}`,
                geometry: { type: 'Point', coordinates },
                properties: {
                    ...feature.properties,
                    database_id: `${id}:label`,
                    _isLabelPoint: true,
                    _originalFeatureId: id,
                },
            });
        }
        this.generation += 1;
    }

    private applyIconRuntimeIds(ctx: IngestContext): void {
        for (const [id, feature] of this.features) {
            if (feature.geometry.type !== 'Point') {
                this.setRuntime(id, { iconId: undefined });
                continue;
            }
            const properties = feature.properties as Record<string, unknown>;
            const iconUrl = getFeatureIconUrl(properties);
            if (shouldUseIcon(ctx.zoom, iconUrl, ctx.replaceIconsLowZoom)) {
                const resolvedUrl = getIconSourceUrl(iconUrl as string, properties);
                this.setRuntime(id, { iconId: iconRuntimeId(resolvedUrl) });
            } else {
                const markerColor = feature.properties?.['marker-color'];
                this.setRuntime(id, {
                    iconId: undefined,
                    detectedIconColor: this.runtime.get(id)?.detectedIconColor
                        ?? (typeof markerColor === 'string' ? markerColor : undefined),
                });
            }
        }
    }

    private applySmallFeatureReplacements(ctx: IngestContext): void {
        this.removeSyntheticsBySuffix(':small');
        for (const [id, feature] of this.features) {
            const geometryType = feature.geometry.type;
            let tooSmall = false;
            let center: number[] | null = null;
            if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
                const size = polygonScreenSize(feature.geometry, ctx);
                tooSmall = size.width < MIN_PIXEL_SIZE || size.height < MIN_PIXEL_SIZE;
                if (tooSmall) center = calculatePolygonCentroid(feature.geometry as never);
            } else if (geometryType === 'LineString' || geometryType === 'MultiLineString') {
                tooSmall = lineScreenSize(feature.geometry, ctx) < MIN_PIXEL_SIZE;
                if (tooSmall) center = calculateLineCenter(feature.geometry as never);
            }
            this.setRuntime(id, { tooSmall });
            if (!tooSmall || !center) continue;
            const color = (feature.properties?.stroke as string | undefined) || '#ff0000';
            this.synthetics.set(`${id}:small`, {
                type: 'Feature',
                id: `${id}:small`,
                geometry: { type: 'Point', coordinates: center },
                properties: {
                    database_id: `${id}_small_replacement`,
                    name: feature.properties?.name,
                    'marker-color': color,
                    _isSmallFeatureReplacement: true,
                    _originalFeatureId: id,
                    _originalGeometryType: geometryType,
                },
            });
        }
    }

    private removeSyntheticsBySuffix(suffix: string): void {
        for (const key of [...this.synthetics.keys()]) {
            if (key.endsWith(suffix)) {
                this.synthetics.delete(key);
            }
        }
        this.generation += 1;
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

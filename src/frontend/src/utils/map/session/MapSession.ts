import type { Map as MapLibreMap } from 'maplibre-gl';
import { FeatureSource } from '@/utils/map/common/FeatureSource';
import { GeoJsonSourceHandle } from '@/utils/map/common/GeoJsonSourceHandle';
import { MapRuntime } from '@/utils/map/common/MapRuntime';
import { MapCamera } from '@/utils/map/common/MapCamera';
import { TileRuntime } from '@/utils/map/common/TileRuntime';
import { LocationRuntime } from '@/utils/map/common/LocationRuntime';
import { HiddenFeatureSet } from './HiddenFeatureSet';
import { FeatureViewportCache } from './FeatureViewportCache';
import { MapFilterState } from './MapFilterState';
import { LoadPipeline } from './LoadPipeline';
import { FeatureInteraction } from './FeatureInteraction';
import { FeatureMutation } from './FeatureMutation';
import { ElevationStore } from './ElevationStore';
import type { MapRouteLocation } from './types';
import type { PublicShareInfo } from '@/composables/mapPageTypes';
import { getFeatureIconUrl, getIconSourceUrl, loadIconImage } from '@/utils/map/maplibre/featureStyling.js';

export interface MapSessionHost {
    getContainer(): HTMLElement | null;
    getAntialias(): boolean;
    getDefaultBasemap(): string | undefined;
    getHiddenIds(): Array<string | number>;
    canWrite(): boolean;
    showLabels(): boolean;
    onMoveOrZoomStart(): void;
    onMoveEnd(): void;
    onZoomEnd(zoom: number): void;
    onZoomFrame(): void;
    onClick(event: unknown): void;
    onMouseMove(event: unknown): void;
    onMouseOut(): void;
    onWebGlLost(): void;
}

/**
 * One map-page state machine. `activate` / `deactivate` / `onRouteChange` are the only boot paths.
 */
export class MapSession {
    readonly features = new FeatureSource();
    readonly hidden = new HiddenFeatureSet();
    readonly filters = new MapFilterState();
    readonly cache = new FeatureViewportCache();
    readonly pipeline: LoadPipeline;
    readonly interaction = new FeatureInteraction();
    readonly elevations = new ElevationStore();
    readonly mutations: FeatureMutation;
    readonly camera = new MapCamera();
    readonly tiles = new TileRuntime();
    readonly location = new LocationRuntime();
    readonly runtime: MapRuntime;
    readonly sink: GeoJsonSourceHandle;

    active = false;
    booted = false;
    routeSignature = '';
    hiddenSignature = '';
    pendingExtentFit = false;
    publicShareInfo: PublicShareInfo | null = null;
    publicShareError: string | null = null;
    publicShareRefinedFitId: string | null = null;

    constructor(private readonly host: MapSessionHost) {
        this.sink = new GeoJsonSourceHandle(() => this.runtime.map, () => this.host.showLabels());
        this.features.attachSink(this.sink);
        this.pipeline = new LoadPipeline(this.features, this.cache, this.hidden);
        this.mutations = new FeatureMutation(this.features, this.hidden, this.elevations, () => this.host.canWrite());
        this.runtime = new MapRuntime({
            onMoveOrZoomStart: () => this.host.onMoveOrZoomStart(),
            onMoveEnd: () => this.host.onMoveEnd(),
            onZoomEnd: (zoom) => this.host.onZoomEnd(zoom),
            onZoomFrame: () => this.host.onZoomFrame(),
            onClick: (event) => this.host.onClick(event),
            onMouseMove: (event) => this.host.onMouseMove(event),
            onMouseOut: () => this.host.onMouseOut(),
            isTrackingLocked: () => this.location.mode === 'follow',
            onTrackingUnlock: () => { this.location.unlockFollow(); },
            onWebGlLost: () => this.host.onWebGlLost(),
            onStyleImageMissing: (iconId) => { this.resolveMissingIcon(iconId); },
        });
    }

    private resolveMissingIcon(iconId: string): void {
        if (!iconId.startsWith('icon-') || !this.runtime.map) return;
        const features = this.features.buildRenderCollection(this.hidden).features;
        for (const feature of features) {
            if (feature.properties?.['_icon-id'] !== iconId) continue;
            const iconUrl = getFeatureIconUrl(feature.properties);
            if (!iconUrl) continue;
            const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties);
            loadIconImage(this.runtime.map, iconId, resolvedUrl).catch((err: unknown) => {
                console.warn(`Failed to load missing icon ${iconId}:`, err);
            });
            return;
        }
        console.warn(`Could not find feature for missing icon: ${iconId}`);
    }

    get map(): MapLibreMap | null {
        return this.runtime.map;
    }

    routeKey(location: MapRouteLocation): string {
        const query = location.query;
        const tag = Array.isArray(query.tag) ? query.tag.join(',') : (query.tag ?? '');
        return [location.path, query.id ?? '', query.collection ?? '', tag, query.featureId ?? '', query.match_mode ?? ''].join('|');
    }

    syncHiddenFromHost(): { changed: boolean; unhid: boolean } {
        const ids = this.host.getHiddenIds().map(String);
        const signature = [...ids].sort().join(',');
        const previous = new Set(this.hidden.values());
        const next = new Set(ids);
        let unhid = false;
        for (const id of previous) {
            if (!next.has(id)) {
                unhid = true;
                break;
            }
        }
        const changed = signature !== this.hiddenSignature;
        this.hidden.replace(ids);
        this.hiddenSignature = signature;
        if (changed) {
            this.features.removeHidden(this.hidden);
        }
        return { changed, unhid };
    }

    async activate(location: MapRouteLocation): Promise<'restore' | 'boot' | 'reload'> {
        this.active = true;
        const hiddenSync = this.syncHiddenFromHost();
        const nextKey = this.routeKey(location);
        const routeChanged = nextKey !== this.routeSignature;
        this.filters.applyRoute(location);

        if (this.booted && this.runtime.hasMap && !routeChanged) {
            this.runtime.resize();
            this.features.commit(this.hidden);
            return hiddenSync.unhid ? 'reload' : 'restore';
        }

        this.routeSignature = nextKey;
        if (routeChanged && this.booted) {
            await this.resetForRoute(location);
            return 'boot';
        }

        return 'boot';
    }

    deactivate(): void {
        this.active = false;
        this.camera.save(this.runtime.map);
        this.location.cleanup();
        this.pipeline.cancel();
        this.sink.flush();
    }

    async onRouteChange(location: MapRouteLocation): Promise<boolean> {
        const nextKey = this.routeKey(location);
        if (nextKey === this.routeSignature) return false;
        this.routeSignature = nextKey;
        this.filters.applyRoute(location);
        await this.resetForRoute(location);
        return true;
    }

    async resetForRoute(location: MapRouteLocation): Promise<void> {
        this.pipeline.cancel();
        this.cache.clear();
        this.features.clear();
        this.elevations.clear();
        this.interaction.clear();
        this.publicShareInfo = null;
        this.publicShareError = null;
        this.publicShareRefinedFitId = null;
        this.pendingExtentFit = false;
        this.filters.applyRoute(location);
        if (location.path === '/mapshare' && !location.query.id) {
            this.publicShareError = 'This share link is missing an id.';
        }
        this.features.commit(this.hidden);
    }

    markBooted(): void {
        this.booted = true;
    }

    dispose(): void {
        this.deactivate();
        this.sink.dispose();
        this.runtime.destroy();
        this.booted = false;
    }
}

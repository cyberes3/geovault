import type { Map as MapLibreMap, MapMouseEvent } from 'maplibre-gl';
import { FeatureSource } from '@/utils/map/common/FeatureSource';
import { GeoJsonSourceHandle } from '@/utils/map/common/GeoJsonSourceHandle';
import { MapRuntime } from '@/utils/map/common/MapRuntime';
import { MapCamera } from '@/utils/map/common/MapCamera';
import { TileRuntime } from '@/utils/map/common/TileRuntime';
import { LocationRuntime } from '@/utils/map/common/LocationRuntime';
import type { IngestContext } from '@/utils/map/common/types';
import { FeatureIconResolver } from '@/utils/map/FeatureIconResolver';
import { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers';
import { pickFeaturesAtPoint } from '@/utils/map/pickFeatures';
import { CLICK_HIT_RADIUS_PX, HOVER_HIT_RADIUS_PX } from '@/utils/map/mapLayers';
import { HiddenFeatureSet } from './HiddenFeatureSet';
import { FeatureViewportCache } from './FeatureViewportCache';
import { MapFilterState } from './MapFilterState';
import { LoadPipeline } from './LoadPipeline';
import { FeatureInteraction } from './FeatureInteraction';
import { FeatureMutation } from './FeatureMutation';
import { ElevationStore } from './ElevationStore';
import type { MapRouteLocation } from './types';
import type { PublicShareInfo } from '@/composables/mapPageTypes';

export interface MapSessionHost {
    getContainer(): HTMLElement | null;
    getAntialias(): boolean;
    getDefaultBasemap(): string | undefined;
    getHiddenIds(): Array<string | number>;
    canWrite(): boolean;
    showLabels(): boolean;
    replaceIconsLowZoom(): boolean;
    onWebGlLost(): void;
}

/**
 * One map-page state machine. Owns click/hover/zoom/bbox. `activate` / `deactivate` / `onRouteChange` are the only boot paths.
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
    readonly iconResolver: FeatureIconResolver;
    labels: LabelMarkerManager | null = null;

    active = false;
    booted = false;
    routeSignature = '';
    hiddenSignature = '';
    pendingExtentFit = false;
    publicShareInfo: PublicShareInfo | null = null;
    publicShareError: string | null = null;
    publicShareRefinedFitId: string | null = null;
    errorMessage: string | null = null;

    onViewportBusy: (() => void) | null = null;
    onViewportIdle: (() => void) | null = null;

    private readonly host: MapSessionHost;

    constructor(host: MapSessionHost) {
        this.host = host;
        this.sink = new GeoJsonSourceHandle(() => this.runtime.map, () => this.host.showLabels());
        this.features.attachSink(this.sink);
        this.pipeline = new LoadPipeline(this.features, this.cache, this.hidden);
        this.mutations = new FeatureMutation(
            this.features,
            this.hidden,
            this.elevations,
            () => this.host.canWrite(),
            () => this.ingestContext(),
        );
        this.iconResolver = new FeatureIconResolver(this.features, this.hidden);
        this.runtime = new MapRuntime({
            onMoveOrZoomStart: () => this.onMoveOrZoomStart(),
            onMoveEnd: () => this.onMoveEnd(),
            onZoomEnd: (zoom) => this.onZoomEnd(zoom),
            onZoomFrame: () => this.onZoomFrame(),
            onClick: (event) => this.onClick(event),
            onMouseMove: (event) => this.onMouseMove(event),
            onMouseOut: () => this.onMouseOut(),
            isTrackingLocked: () => this.location.mode === 'follow',
            onTrackingUnlock: () => { this.location.unlockFollow(); },
            onWebGlLost: () => {
                this.errorMessage = 'The map graphics context was lost. Refresh the page.';
                this.host.onWebGlLost();
            },
        });
        this.runtime.iconResolver = this.iconResolver;
    }

    get map(): MapLibreMap | null {
        return this.runtime.map;
    }

    ingestContext(): IngestContext {
        const map = this.runtime.map;
        const container = map?.getContainer();
        return {
            zoom: map?.getZoom() ?? 0,
            replaceIconsLowZoom: this.host.replaceIconsLowZoom(),
            showLabels: this.host.showLabels(),
            viewSize: {
                width: container?.clientWidth || 800,
                height: container?.clientHeight || 600,
            },
        };
    }

    ingestIncoming(incoming: Parameters<FeatureSource['ingest']>[0]): void {
        this.features.ingest(incoming, this.ingestContext(), this.hidden);
        this.features.commit(this.hidden);
        this.labels?.sync();
    }

    refreshZoomDependent(): void {
        this.features.refreshZoomDependent(this.ingestContext());
        this.features.commit(this.hidden);
        this.labels?.sync();
    }

    onMoveOrZoomStart(): void {
        this.onViewportBusy?.();
    }

    onMoveEnd(): void {
        this.camera.save(this.runtime.map);
        this.onViewportIdle?.();
    }

    onZoomEnd(_zoom: number): void {
        this.refreshZoomDependent();
        this.onViewportIdle?.();
    }

    onZoomFrame(): void {
        this.labels?.sync(true);
    }

    onClick(event: MapMouseEvent): void {
        if (!this.runtime.map) return;
        const hits = pickFeaturesAtPoint(this.runtime.map, event.point, CLICK_HIT_RADIUS_PX, this.features);
        this.interaction.handleClick(this.runtime.map, hits, { x: event.point.x, y: event.point.y });
    }

    onMouseMove(event: MapMouseEvent): void {
        if (!this.runtime.map) return;
        const hits = pickFeaturesAtPoint(this.runtime.map, event.point, HOVER_HIT_RADIUS_PX, this.features);
        this.runtime.map.getCanvas().style.cursor = hits.length > 0 ? 'pointer' : '';
        const id = hits[0] ? String(hits[0].properties?.database_id ?? '') || null : null;
        this.interaction.hover(this.runtime.map, id);
    }

    onMouseOut(): void {
        if (this.runtime.map) {
            this.runtime.map.getCanvas().style.cursor = '';
        }
        this.interaction.hover(this.runtime.map, null);
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
            this.camera.restore(this.runtime.map);
            this.features.commit(this.hidden);
            this.labels?.sync();
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
        this.interaction.clear(this.runtime.map);
        this.publicShareInfo = null;
        this.publicShareError = null;
        this.publicShareRefinedFitId = null;
        this.pendingExtentFit = false;
        this.filters.applyRoute(location);
        if (location.path === '/mapshare' && !location.query.id) {
            this.publicShareError = 'This share link is missing an id.';
        }
        this.features.commit(this.hidden);
        this.labels?.clear();
    }

    attachLabels(): void {
        if (!this.runtime.map) return;
        this.labels?.clear();
        this.labels = new LabelMarkerManager(this.runtime.map, this.features);
        this.labels.setVisibility(this.host.showLabels());
    }

    markBooted(): void {
        this.booted = true;
    }

    dispose(): void {
        this.deactivate();
        this.labels?.clear();
        this.labels = null;
        this.sink.dispose();
        this.runtime.destroy();
        this.booted = false;
    }
}

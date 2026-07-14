/**
 * Ambient declaration for the `window.gv_core` global the core app exposes to extensions at
 * runtime (see the core frontend's `src/types/geovault.d.ts` for the full surface). Only the
 * subset this extension actually reads is declared here, with locally-defined shapes rather than
 * importing core's real types, since this is a separate TypeScript project with no shared `@/`
 * path resolution back into core.
 */
import type { MaybeRefOrGetter } from 'vue';
import type { Map as MapLibreMap, Marker } from 'maplibre-gl';

export interface LocationMarkerCoords {
    latitude: number;
    longitude: number;
}

export interface ToastService {
    success(message: string): void;
    error(message: string): void;
    info(message: string): void;
    warning(message: string): void;
}

export interface TileSourceClientConfig {
    url?: string;
    style_url?: string;
    type?: string;
    tileSize?: number;
    attribution?: string;
    minzoom?: number;
    maxzoom?: number;
    [key: string]: unknown;
}

export interface TileSource {
    id: string;
    name: string;
    type: string;
    requires_proxy?: boolean;
    hidden?: boolean;
    client_config?: TileSourceClientConfig;
    [key: string]: unknown;
}

export interface TileSourceCatalog {
    load(): Promise<TileSource[]>;
}

export interface RasterTileUrlsStatic {
    fromTileSource(tileSource?: Partial<TileSource>): string[];
}

export interface GeolocationManager {
    isTracking: boolean;
    startTracking: (onUpdate: (coords: LocationMarkerCoords) => void, onError?: (error: unknown) => void) => void;
    stopTracking: () => void;
    getCurrentPosition: () => Promise<LocationMarkerCoords>;
}

export interface WebSocketHeartbeatOptions {
    sendPing: () => void;
    onTimeout: () => void;
    intervalMs?: number;
    timeoutMs?: number;
}

export interface WebSocketHeartbeatInstance {
    start(): void;
    stop(): void;
    onPong(): void;
}

export interface ExtensionSetupUtils {
    getNestedValue: (obj: unknown, key: string) => unknown;
    loadSettingsFromValues: (
        config: Array<{ key: string; defaultValue: unknown }>,
        settings: Record<string, unknown> | null
    ) => Record<string, unknown>;
    keyValueToNested: (key: string, value: unknown) => unknown;
}

declare global {
    interface Window {
        gv_core: {
            GeoVault: {
                toast: ToastService;
                utils: ExtensionSetupUtils;
            };
            /** Only present because this extension code reads it defensively; core's real `window.gv_core` never sets it (extensions get `platformState`, not the raw store), so this always resolves to `undefined`. */
            store?: {
                getters?: Record<string, unknown>;
            };
            createRouteWrapper: (component: unknown, options: Record<string, unknown>) => unknown;
            tileSourceCatalog: TileSourceCatalog;
            RasterTileUrls: RasterTileUrlsStatic;
            OSM_TILE_SOURCE_ID: string;
            geolocationManager: GeolocationManager;
            /** Null until `loadMaplibreGl()` resolves - MapLibre GL JS is loaded lazily, not eagerly at boot. */
            maplibre: typeof import('maplibre-gl') | null;
            loadMaplibreGl: () => Promise<typeof import('maplibre-gl')>;
            WebSocketHeartbeat: new (options: WebSocketHeartbeatOptions) => WebSocketHeartbeatInstance;
            isValidMapLngLatPair: (lon: number, lat: number) => boolean;
            createUserLocationMarker: (map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined) => Promise<Marker | null>;
            updateUserLocationMarker: (marker: Marker | null | undefined, coords: LocationMarkerCoords | null | undefined) => void;
            removeUserLocationMarker: (marker: Marker | null | undefined) => void;
            setupCopyMapCoordinatesOnContextMenu: (map: MapLibreMap) => () => void;
            useDocumentTitle: (titleSource: MaybeRefOrGetter<string>) => void;
        };
        /** Mirrors `window.gv_core.maplibre` once `loadMaplibreGl()` resolves - see core's `lazyMaplibreGl.js`. */
        maplibregl: typeof import('maplibre-gl') | undefined;
    }
}

export {};

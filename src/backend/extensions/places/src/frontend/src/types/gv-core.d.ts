/**
 * Ambient declaration for the `window.gv_core` global the core app exposes to extensions at
 * runtime (see the core frontend's `src/types/geovault.d.ts` for the full surface). Only the
 * subset this extension actually reads is declared here, with locally-defined shapes rather than
 * importing core's real types, since this is a separate TypeScript project with no shared `@/`
 * path resolution back into core.
 */
import type { Component, MaybeRefOrGetter } from 'vue';
import type { MaplibreGlNamespace } from './maplibre';
import type { PlatformStateBridge } from './platform-state';

export interface ToastService {
    success(message: string): void;
    error(message: string): void;
    info(message: string): void;
    warning(message: string): void;
}

export interface GeocodingResult {
    id?: string | number;
    text?: string;
    place_name?: string;
    [key: string]: unknown;
}

export interface CoordinatePair {
    lat: number;
    lng: number;
}

export interface CoordinateValidationResult {
    valid: boolean;
    error: string | null;
}

export interface GeocodingSearchResult {
    ok: boolean;
    features: GeocodingResult[];
    error?: string;
}

export interface ExtensionSetupUtils {
    parseCoordinates: (input: string) => CoordinatePair | null;
    looksLikeCoordinates: (input: string) => boolean;
    validateCoordinates: (coordinates: [number, number], geometryType: string | null | undefined) => CoordinateValidationResult;
    searchGeocoding: (query: string, options?: { signal?: AbortSignal }) => Promise<GeocodingSearchResult>;
    getGeocodingResultCoordinates: (result: GeocodingResult) => { lon: number; lat: number } | null;
    getGeocodingResultLabel: (result: GeocodingResult) => string;
    getNestedValue: (obj: unknown, key: string) => unknown;
    loadSettingsFromValues: (
        config: Array<{ key: string; defaultValue: unknown }>,
        settings: Record<string, unknown> | null
    ) => Record<string, unknown>;
    keyValueToNested: (key: string, value: unknown) => unknown;
}

export interface TileSourceClientConfig {
    type?: string;
    style_url?: string;
    url?: string;
    tileSubdomains?: string[];
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
    requires_proxy: boolean;
    hidden?: boolean;
    exaggeration?: number;
    opacity?: number;
    client_config: TileSourceClientConfig;
}

export interface TileSourceCatalog {
    load: () => Promise<TileSource[]>;
    resolveSource: (sources: TileSource[], preferredId?: string) => TileSource;
}

export interface RasterTileUrlsLike {
    fromTileSource: (tileSource?: Partial<TileSource>) => string[];
}

export interface GeolocationCurrentPosition {
    latitude: number;
    longitude: number;
}

export interface GeolocationManagerLike {
    checkPermission: () => Promise<string>;
    getCurrentPosition: () => Promise<GeolocationCurrentPosition>;
}

declare global {
    interface Window {
        gv_core: {
            GeoVault: {
                toast: ToastService;
                utils: ExtensionSetupUtils;
            };
            createRouteWrapper: (component: Component, options: Record<string, unknown>) => Component;
            tileSourceCatalog: TileSourceCatalog;
            RasterTileUrls: RasterTileUrlsLike;
            OSM_TILE_SOURCE_ID: string;
            isValidMapLngLatPair: (lon: number, lat: number) => boolean;
            platformState: PlatformStateBridge;
            geolocationManager: GeolocationManagerLike;
            useDocumentTitle: (titleSource: MaybeRefOrGetter<string>) => void;
            maplibre: MaplibreGlNamespace | null;
            loadMaplibreGl: () => Promise<MaplibreGlNamespace>;
        };
        maplibregl: MaplibreGlNamespace | null;
    }
}

export {};

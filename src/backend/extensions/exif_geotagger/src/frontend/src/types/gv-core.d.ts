/**
 * Ambient declaration for the `window.gv_core` global the core app exposes to extensions at
 * runtime (see the core frontend's `src/types/geovault.d.ts` for the full surface). Only the
 * subset this extension actually reads is declared here, with locally-defined shapes rather than
 * importing core's real types, since this is a separate TypeScript project with no shared `@/`
 * path resolution back into core.
 */
import type { Component } from 'vue';
import type TileLayer from 'ol/layer/Tile';

export interface GeocodingResult {
    id?: string | number;
    text?: string;
    place_name?: string;
    [key: string]: unknown;
}

export interface ExtensionSetupUtils {
    parseCoordinates: (input: string) => { lat: number; lng: number } | null;
    searchGeocoding: (query: string, options?: { signal?: AbortSignal }) => Promise<{ ok: boolean; features: GeocodingResult[]; error?: string }>;
    getGeocodingResultCoordinates: (result: GeocodingResult) => { lon: number; lat: number } | null;
}

declare global {
    interface Window {
        gv_core: {
            GeoVault: {
                utils: ExtensionSetupUtils;
            };
            openLayersBasemap: {
                createTileLayer: (sourceId?: string) => Promise<TileLayer>;
            };
            createRouteWrapper: (component: Component, options: Record<string, unknown>) => Component;
        };
    }
}

export {};

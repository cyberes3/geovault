import type { GeoVaultSocketInstance, GeoVaultSocketOptions, GvCoreWindow } from './gv-core';

declare global {
    interface Window {
        gv_core: GvCoreWindow & {
            GeoVault?: {
                toast: GvCoreWindow['ui']['toast'];
                utils: {
                    loadSettingsFromValues: (
                        config: Array<{ key: string; defaultValue: unknown }>,
                        settings: Record<string, unknown> | null
                    ) => Record<string, unknown>;
                    keyValueToNested: (key: string, value: unknown) => unknown;
                    getNestedValue: (obj: unknown, key: string) => unknown;
                };
                platformState?: unknown;
            };
            maplibre?: unknown;
            loadMaplibreGl?: () => Promise<unknown>;
            tileSourceCatalog?: unknown;
            RasterTileUrls?: unknown;
            OSM_TILE_SOURCE_ID?: string;
            geolocationManager?: unknown;
            platformState?: unknown;
            GeoVaultSocket?: new (options: GeoVaultSocketOptions) => GeoVaultSocketInstance;
            WebSocketHeartbeat?: unknown;
            isValidMapLngLatPair?: (lon: number, lat: number) => boolean;
            useDocumentTitle?: (titleSource: unknown) => void;
            createUserLocationMarker?: (...args: unknown[]) => Promise<unknown>;
            updateUserLocationMarker?: (...args: unknown[]) => void;
            removeUserLocationMarker?: (...args: unknown[]) => void;
            setupCopyMapCoordinatesOnContextMenu?: (...args: unknown[]) => () => void;
        };
        maplibregl?: unknown;
    }
}

export {};

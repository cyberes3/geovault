/**
 * GeoVault Extension System Type Definitions
 *
 * The canonical setup-context types live in `@/extensions/extensionContractTypes`; this file only
 * adds the ambient `Window.gv_core` global declaration extensions rely on at runtime.
 */
import type { Component, MaybeRefOrGetter } from 'vue';
import type { Map as MapLibreMap, Marker } from 'maplibre-gl';
import type { ExtensionApi } from '@/utils/extensionApi';
import type { PlatformStateBridge } from '@/extensions/platformState';
import type {
    ExtensionMetadata,
    ExtensionSetup,
    ExtensionSetupContext,
    ExtensionSetupUtils,
    ScopedExtensionRegistry,
    ToastService
} from '@/extensions/extensionContractTypes';
import type { TileSourceCatalog } from '@/utils/map/tileSources/TileSourceCatalog';
import type { RasterTileUrls } from '@/utils/map/tileSources/RasterTileUrls';
import type { MapLibrePreviewMap } from '@/utils/map/common/MapLibrePreviewMap';
import type { GeoVaultSocket, GeoVaultSocketOptions } from '@/assets/js/websocket/GeoVaultSocket';
import type { WebSocketHeartbeat, WebSocketHeartbeatOptions } from '@/assets/js/websocket/WebSocketHeartbeat';
import type { GeolocationManager } from '@/utils/map/geolocationManager.js';
import type { LocationMarkerCoords } from '@/utils/map/maplibre/locationMarker.js';
import type { SetupCopyMapCoordinatesDeps } from '@/utils/map/copyMapCoordinatesOnContextMenu.js';

export type {
    ExtensionMetadata,
    ExtensionSetup,
    ExtensionSetupContext,
    ExtensionSetupUtils,
    ScopedExtensionRegistry,
    ToastService
};
export type { ExtensionApi } from '@/utils/extensionApi';
export type { PlatformStateBridge } from '@/extensions/platformState';

export interface ExtensionSettingSchema {
    key: string;
    type: 'string' | 'boolean' | 'number' | 'select';
    label: string;
    default?: unknown;
    description?: string;
    options?: { label: string; value: unknown }[];
    secret?: boolean;
}

/** Thin compat bag on `window.gv_core.GeoVault`. Prefer gv_core.ui / .settings / .net. */
export interface GeoVaultGlobal {
    utils: ExtensionSetupUtils;
    toast: ToastService;
    platformState: PlatformStateBridge;
}

/**
 * Shared platform APIs exposed to extensions. All core-provided globals live under window.gv_core.
 * There is no `store` here on purpose: extensions get read-mostly access to app state through
 * `platformState`, never the raw Vuex store (see `@/extensions/platformState`).
 */
declare global {
    interface Window {
        gv_core: {
            map: {
                loadEngine: (engine: 'maplibre' | 'none') => Promise<unknown>;
                loadMaplibreGl: () => Promise<unknown>;
                maplibre: unknown;
                tileSourceCatalog: TileSourceCatalog;
                RasterTileUrls: typeof RasterTileUrls;
                OSM_TILE_SOURCE_ID: string;
                geolocationManager: GeolocationManager;
                isValidMapLngLatPair: (lon: number, lat: number) => boolean;
                createUserLocationMarker: (map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined) => Promise<Marker | null>;
                useUserLocationMarker: (map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined) => Promise<Marker | null>;
                updateUserLocationMarker: (marker: Marker | null | undefined, coords: LocationMarkerCoords | null | undefined) => Promise<void> | void;
                removeUserLocationMarker: (marker: Marker | null | undefined) => Promise<void> | void;
                setupCopyMapCoordinatesOnContextMenu: (map: MapLibreMap, deps?: SetupCopyMapCoordinatesDeps) => () => void;
                createPointPickerMap: (container: HTMLElement, onPick: (lng: number, lat: number) => void) => Promise<MapLibrePreviewMap>;
                createGeoJsonPreviewMap: (container: HTMLElement) => Promise<MapLibrePreviewMap>;
                MapLibrePreviewMap: typeof MapLibrePreviewMap;
            };
            ui: {
                toast: ToastService;
                useDocumentTitle: (titleSource: MaybeRefOrGetter<string>) => void;
                copyToClipboard: (text: string) => Promise<void>;
                hexToRgb: (hex: string) => [number, number, number] | null;
            };
            net: {
                coreApi: unknown;
                listUsers: () => Promise<Array<{ id: number; email: string }>>;
                connectExtensionSocket: (options: GeoVaultSocketOptions) => GeoVaultSocket;
                downloadBlob: (url: string, filename: string) => Promise<void>;
            };
            settings: {
                awaitUserSettings: () => Promise<void>;
                status: () => 'idle' | 'loading' | 'ready' | 'error';
                getUnitPreference: () => string;
                getExtensionSetting: (extensionName: string, key: string) => unknown;
                useExtensionSettings: (extensionName: string) => {
                    get(key: string): unknown;
                    save(key: string, value: unknown): Promise<Record<string, unknown>>;
                };
            };
            sharing: {
                absoluteUrl: (path: string) => string;
                PublicShareSession: new () => {
                    status: 'idle' | 'loading' | 'ready' | 'invalid';
                    info: unknown;
                    error: string | null;
                    shareId: string | null;
                    readonly allowDownloads: boolean;
                    readonly includeTags: boolean;
                    resetForShareIdChange(shareId?: string | null): void;
                    ensureInfo(signal?: AbortSignal): Promise<boolean>;
                    toMapShareInfo(): unknown;
                };
            };
            GeoVault: GeoVaultGlobal;
            Vue: unknown;
            VueRouter: unknown;
            Vuex: unknown;
            axios: unknown;
            /** Resolves an outline heroicon by name, lazily (never on the eager boot path). Rejects for an unrecognized name - see `resolveExtensionIcon.ts`'s `createHeroiconResolver` and `extensions/lazyHeroiconResolver.ts`. */
            resolveHeroiconByName: (name: string) => Promise<Component>;
            /** Null until `loadMaplibreGl()` resolves - MapLibre GL JS is loaded lazily, not eagerly at boot. */
            maplibre: unknown;
            /** Lazily loads MapLibre GL JS (and its CSS), caching the result. Prefer this over reading `maplibre` directly when you can't guarantee it has already loaded. */
            loadMaplibreGl: () => Promise<unknown>;
            createRouteWrapper: (component: Component, options: { api: ExtensionApi; platformState?: PlatformStateBridge; router?: unknown; [key: string]: unknown }) => Component;
            BaseButton: unknown;
            BaseModal: unknown;
            Loader: unknown;
            LocationIcon: unknown;
            ScrollingSelect: unknown;
            SearchableCheckboxList: unknown;
            ToggleButton: unknown;
            SettingsInput: unknown;
            tileSourceCatalog: TileSourceCatalog;
            RasterTileUrls: typeof RasterTileUrls;
            OSM_TILE_SOURCE_ID: string;
            geolocationManager: GeolocationManager;
            platformState: PlatformStateBridge;
            realtimeSocket: unknown;
            GeoVaultSocket: new (options: GeoVaultSocketOptions) => GeoVaultSocket;
            WebSocketHeartbeat: new (options: WebSocketHeartbeatOptions) => WebSocketHeartbeat;
            isValidMapLngLatPair: (lon: number, lat: number) => boolean;
            createUserLocationMarker: (map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined) => Promise<Marker | null>;
            updateUserLocationMarker: (marker: Marker | null | undefined, coords: LocationMarkerCoords | null | undefined) => void;
            removeUserLocationMarker: (marker: Marker | null | undefined) => void;
            setupCopyMapCoordinatesOnContextMenu: (map: MapLibreMap, deps?: SetupCopyMapCoordinatesDeps) => () => void;
            useDocumentTitle: (titleSource: MaybeRefOrGetter<string>) => void;
        };
        GeoVault: GeoVaultGlobal;
        /** Mirrors `window.gv_core.maplibre` once `loadMaplibreGl()` resolves - see `lazyMaplibreGl.js`. */
        maplibregl: unknown;
        /** Vue ecosystem + shared UI parts, also exposed at top level so UMD extension builds that externalize these deps keep working. Prefer `window.gv_core.*` in core source. */
        Vue: unknown;
        VueRouter: unknown;
        Vuex: unknown;
        axios: unknown;
        BaseButton: unknown;
        BaseModal: unknown;
        Loader: unknown;
        LocationIcon: unknown;
        ScrollingSelect: unknown;
        SearchableCheckboxList: unknown;
        ToggleButton: unknown;
        SettingsInput: unknown;
    }
}

export {};

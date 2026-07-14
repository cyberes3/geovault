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
import type { TileSourceCatalog, RasterTileUrls, OpenLayersBasemapFactory } from '@/utils/map/openlayers';
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

/** Global platform surface exposed on `window.gv_core.GeoVault`. */
export interface GeoVaultGlobal {
    registry: ScopedExtensionRegistry;
    utils: ExtensionSetupUtils;
    toast: ToastService;
    platformState: PlatformStateBridge;
    tileSourceCatalog: TileSourceCatalog;
    RasterTileUrls: typeof RasterTileUrls;
    geolocationManager: GeolocationManager;
}

/**
 * Shared platform APIs exposed to extensions. All core-provided globals live under window.gv_core.
 * There is no `store` here on purpose: extensions get read-mostly access to app state through
 * `platformState`, never the raw Vuex store (see `@/extensions/platformState`).
 */
declare global {
    interface Window {
        gv_core: {
            GeoVault: GeoVaultGlobal;
            Vue: unknown;
            VueRouter: unknown;
            Vuex: unknown;
            axios: unknown;
            /** Resolves an outline heroicon by name, lazily (never on the eager boot path). Rejects for an unrecognized name - see `resolveExtensionIcon.ts`'s `createHeroiconResolver` and `extensions/lazyHeroiconResolver.ts`. */
            resolveHeroiconByName: (name: string) => Promise<Component>;
            /** Null until `loadOl()` resolves - OpenLayers is loaded lazily, not eagerly at boot. */
            ol: unknown;
            /** Lazily loads OpenLayers (core + submodules), caching the result. Prefer this over reading `ol` directly when you can't guarantee it has already loaded. */
            loadOl: () => Promise<unknown>;
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
            openLayersBasemap: OpenLayersBasemapFactory;
            OSM_TILE_SOURCE_ID: string;
            geolocationManager: GeolocationManager;
            platformState: PlatformStateBridge;
            realtimeSocket: unknown;
            WebSocketHeartbeat: new (options: WebSocketHeartbeatOptions) => WebSocketHeartbeat;
            isValidMapLngLatPair: (lon: number, lat: number) => boolean;
            createUserLocationMarker: (map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined) => Promise<Marker | null>;
            updateUserLocationMarker: (marker: Marker | null | undefined, coords: LocationMarkerCoords | null | undefined) => void;
            removeUserLocationMarker: (marker: Marker | null | undefined) => void;
            setupCopyMapCoordinatesOnContextMenu: (map: MapLibreMap, deps?: SetupCopyMapCoordinatesDeps) => () => void;
            useDocumentTitle: (titleSource: MaybeRefOrGetter<string>) => void;
        };
        GeoVault: GeoVaultGlobal;
        /** Mirrors `window.gv_core.ol` once `loadOl()` resolves - see `lazyOl.js`. */
        ol: unknown;
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

/**
 * GeoVault Extension System Type Definitions
 *
 * The canonical setup-context types live in `@/extensions/extensionContractTypes`; this file only
 * adds the ambient `Window.gv_core` global declaration extensions rely on at runtime.
 */
import type { Component } from 'vue';
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
            HeroiconsOutline: unknown;
            HeroiconsSolid: unknown;
            ol: unknown;
            maplibre: unknown;
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
            createUserLocationMarker: (map: unknown, coords: { latitude: number; longitude: number }) => unknown;
            updateUserLocationMarker: (marker: unknown, coords: { latitude: number; longitude: number }) => void;
            removeUserLocationMarker: (marker: unknown) => void;
            setupCopyMapCoordinatesOnContextMenu: (map: unknown, deps?: { toast?: ToastService }) => () => void;
            useDocumentTitle: (titleSource: string | (() => string) | { value: string }) => void;
        };
        GeoVault: GeoVaultGlobal;
    }
}

export {};

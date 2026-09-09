import './assets/css/main.css'

import { PublicShareSession } from '@/utils/sharing/publicShareSession'
import { downloadKmz } from '@/utils/sharing/downloadKmz'
import { remapPathnameToHash, mapSocialUrl, mapSpaUrl, trackSocialUrl, trackSpaUrl } from '@/utils/sharing/shareUrl'

/**
 * Dev/proxy-safe fallback:
 * If a social share URL is opened on the frontend dev server (or any origin that serves the SPA directly),
 * remap /share/map/<id>/ or /share/track/<id>/ to the hash route before app initialization/auth checks.
 */
function remapSocialSharePathToHashRoute(): void {
    if (typeof window === 'undefined') return
    const next = remapPathnameToHash(window.location.pathname, window.location.hash)
    if (next) {
        window.location.replace(next)
    }
}

remapSocialSharePathToHashRoute()

if ('serviceWorker' in navigator) {
    void navigator.serviceWorker.register('/sw.js?v=' + __SW_VERSION__)
}

import { createApp } from 'vue'
import * as VueState from 'vue'
import * as VueRouterState from 'vue-router'
import * as VuexState from 'vuex'
import App from './App.vue'
import store from "@/assets/js/store";
import router from "@/router.js";
import '@/assets/css/root.css'

import axios from 'axios';
import { toast } from '@/utils/toast';
import { updateUserSetting } from '@/utils/userSettingsService';
import { loadSettingsFromValues } from '@/utils/userSettingsService';
import { keyValueToNested, getNestedValue } from '@/utils/settingsUtils';
import { geolocationManager } from '@/utils/map/geolocationManager.js';
import { parseCoordinates, looksLikeCoordinates, validateCoordinates } from '@/utils/geo/coordinates';
import { searchGeocoding, getGeocodingResultCoordinates, getGeocodingResultLabel } from '@/utils/geocodingSearch.js';
import { listUsers } from '@/api/services/userApi';
import { httpClient } from '@/api/httpClient';
import { hexToRgb } from '@/utils/map/colorUtils';
import { useExtensionSettings } from '@/extensions/useExtensionSettings';
import { realtimeSocket } from '@/assets/js/websocket/realtimeSocket';
import { GeoVaultSocket } from '@/assets/js/websocket/GeoVaultSocket';
import { WebSocketHeartbeat } from '@/assets/js/websocket/WebSocketHeartbeat';
import { tileSourceCatalog } from '@/utils/map/tileSources/sharedCatalog.js';
import { RasterTileUrls } from '@/utils/map/tileSources/RasterTileUrls.js';
import { OSM_TILE_SOURCE_ID } from '@/utils/map/tileSources/constants.js';
import { isValidMapLngLatPair } from '@/utils/map/mapGeography.js';
import { useDocumentTitle } from '@/utils/documentTitle.js';
import { firstPaintPath, mountWaitsForExtensions } from '@/utils/runtime/BootGraph';
import { settingsReady } from '@/utils/settings/SettingsReady';

import { extensionRegistry } from '@/utils/extensionRegistry.js';
import { createRouteWrapper } from '@/extensions/routeWrapper';
import { createPlatformStateBridge } from '@/extensions/platformState';
import { loadExtensions } from '@/extensions/extensionLoader';

import { loadMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import type { LocationMarkerCoords } from '@/utils/map/maplibre/locationMarker';
import type { Map as MapLibreMap, Marker } from 'maplibre-gl';
import type { SetupCopyMapCoordinatesDeps } from '@/utils/map/copyMapCoordinatesOnContextMenu';

// PWA Install Prompt Handling
window.addEventListener('beforeinstallprompt', (e) => {
    // Prevent the mini-infobar from appearing on mobile
    e.preventDefault();
    // Stash the event so it can be triggered later.
    void store.dispatch('extensionsRuntime/setDeferredPrompt', e);
    console.log('PWA: beforeinstallprompt event captured');
});

const platformState = createPlatformStateBridge(store);

// Lazily resolves an arbitrary heroicon by name instead of bundling the whole library eagerly.
// This trampoline is a plain function so `window.gv_core.resolveHeroiconByName` is always callable,
// but the actual `import.meta.glob(...)`-backed resolver (and the lazy "icons" chunk it pulls in)
// only gets fetched on first use - see `extensions/lazyHeroiconResolver.ts`.
function resolveHeroiconByName(name: string) {
    return import('@/extensions/lazyHeroiconResolver').then((m) => m.resolveHeroiconByName(name));
}

// Shared, cross-cutting helpers made available to every extension. There is no raw store here on
// purpose: extensions get read-mostly access to app state through `platformState` above.
const extensionUtils = {
    updateUserSetting,
    loadSettingsFromValues,
    keyValueToNested,
    getNestedValue,
    parseCoordinates,
    looksLikeCoordinates,
    validateCoordinates,
    searchGeocoding,
    getGeocodingResultCoordinates,
    getGeocodingResultLabel,
    listUsers
};

async function loadEngine(engine: 'maplibre' | 'none'): Promise<unknown> {
    if (engine === 'none') {
        return undefined;
    }
    const { mapCommonApi } = await import('@/utils/map/common/index.js');
    Object.assign(gvCoreMap, mapCommonApi);
    return mapCommonApi.loadEngine('maplibre');
}

async function createUserLocationMarker(map: MapLibreMap | null | undefined, coords: LocationMarkerCoords | null | undefined): Promise<Marker | null> {
    const { createUserLocationMarker: createMarker } = await import('@/utils/map/maplibre/locationMarker.js');
    return createMarker(map, coords);
}

async function updateUserLocationMarker(marker: Marker | null | undefined, coords: LocationMarkerCoords | null | undefined): Promise<void> {
    const { updateUserLocationMarker: updateMarker } = await import('@/utils/map/maplibre/locationMarker.js');
    updateMarker(marker, coords);
}

async function removeUserLocationMarker(marker: Marker | null | undefined): Promise<void> {
    const { removeUserLocationMarker: removeMarker } = await import('@/utils/map/maplibre/locationMarker.js');
    removeMarker(marker);
}

function setupCopyMapCoordinatesOnContextMenu(map: MapLibreMap, deps?: SetupCopyMapCoordinatesDeps): () => void {
    let disposed = false;
    let innerTeardown: (() => void) | null = null;
    void import('@/utils/map/copyMapCoordinatesOnContextMenu.js').then(({ setupCopyMapCoordinatesOnContextMenu: setup }) => {
        if (disposed) {
            return;
        }
        innerTeardown = setup(map, deps);
    });
    return () => {
        disposed = true;
        innerTeardown?.();
    };
}

async function createGeoJsonPreviewMap(container: HTMLElement) {
    const { createGeoJsonPreviewMap: create } = await import('@/utils/map/common/MapLibrePreviewMap.js');
    return create(container);
}

async function createPointPickerMap(container: HTMLElement, onPick: (lng: number, lat: number) => void) {
    const { createPointPickerMap: create } = await import('@/utils/map/common/MapLibrePreviewMap.js');
    return create(container, onPick);
}

async function copyToClipboard(text: string): Promise<void> {
    await navigator.clipboard.writeText(text);
}

async function downloadBlob(url: string, filename: string): Promise<void> {
    const response = await fetch(url, { credentials: 'include' });
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(objectUrl);
}

function absoluteUrl(path: string): string {
    if (path.startsWith('http://') || path.startsWith('https://')) {
        return path;
    }
    const normalized = path.startsWith('/') ? path : `/${path}`;
    return `${window.location.origin}${normalized}`;
}

function getUnitPreference(): string {
    const units = getNestedValue(platformState.userSettings.value, 'account.units');
    return typeof units === 'string' ? units : 'imperial';
}

function getExtensionSetting(extensionName: string, key: string): unknown {
    return getNestedValue(platformState.userSettings.value, `extensions.${extensionName}.${key}`);
}

function connectExtensionSocket(options: ConstructorParameters<typeof GeoVaultSocket>[0]) {
    return new GeoVaultSocket(options);
}

const gvCoreMap = {
    loadEngine,
    loadMaplibreGl,
    maplibre: null as unknown,
    tileSourceCatalog,
    RasterTileUrls,
    OSM_TILE_SOURCE_ID,
    geolocationManager,
    isValidMapLngLatPair,
    createUserLocationMarker,
    updateUserLocationMarker,
    removeUserLocationMarker,
    setupCopyMapCoordinatesOnContextMenu,
    createGeoJsonPreviewMap,
    createPointPickerMap,
    useUserLocationMarker: createUserLocationMarker,
};

const gvCoreUi = {
    toast,
    useDocumentTitle,
    copyToClipboard,
    hexToRgb,
};

const gvCoreNet = {
    coreApi: httpClient,
    listUsers,
    connectExtensionSocket,
    downloadBlob,
};

const gvCoreSettings = {
    awaitUserSettings: () => settingsReady.awaitReady(() => store.dispatch('userSettings/fetchUserSettings')),
    status: () => settingsReady.status,
    getUnitPreference,
    getExtensionSetting,
    useExtensionSettings: (extensionName: string) => useExtensionSettings(extensionName, platformState),
};

const gvCoreSharing = {
    absoluteUrl,
    downloadKmz,
    PublicShareSession,
    mapSocialUrl,
    mapSpaUrl,
    trackSocialUrl,
    trackSpaUrl,
    remapPathnameToHash,
};

// Thin compat bag: toast/utils/platformState during this pass. Prefer gv_core.ui / .settings / .net.
const GeoVault = {
    utils: extensionUtils,
    toast,
    platformState,
};

window.gv_core = {
    map: gvCoreMap,
    ui: gvCoreUi,
    net: gvCoreNet,
    settings: gvCoreSettings,
    sharing: gvCoreSharing,
    GeoVault,
    Vue: VueState,
    VueRouter: VueRouterState,
    Vuex: VuexState,
    axios,
    resolveHeroiconByName,
    maplibre: null,
    loadMaplibreGl,
    createRouteWrapper,
    tileSourceCatalog,
    RasterTileUrls,
    OSM_TILE_SOURCE_ID,
    geolocationManager,
    platformState,
    realtimeSocket,
    GeoVaultSocket,
    WebSocketHeartbeat,
    isValidMapLngLatPair,
    createUserLocationMarker,
    updateUserLocationMarker,
    removeUserLocationMarker,
    setupCopyMapCoordinatesOnContextMenu,
    useDocumentTitle,
    BaseButton: null,
    BaseModal: null,
    Loader: null,
    LocationIcon: null,
    ScrollingSelect: null,
    SearchableCheckboxList: null,
    ToggleButton: null,
    SettingsInput: null
};

// Top-level aliases so extension UMD bundles (external vue, etc.) keep working
window.GeoVault = window.gv_core.GeoVault;
window.Vue = window.gv_core.Vue;
window.VueRouter = window.gv_core.VueRouter;
window.Vuex = window.gv_core.Vuex;
window.axios = window.gv_core.axios;

// No eager `loadMaplibreGl()` calls here on purpose - see lazyMaplibreGl.js.
// Map-rendering code calls `window.gv_core.loadMaplibreGl()` itself, right before it
// needs to render a map. That populates `window.maplibregl` as a side effect once resolved.

import BaseButton from '@/components/parts/BaseButton.vue';
import ToggleButton from '@/components/parts/ToggleButton.vue';
import Loader from '@/components/parts/Loader.vue';
import LocationIcon from '@/components/parts/LocationIcon.vue';
import ScrollingSelect from '@/components/parts/ScrollingSelect.vue';
import SearchableCheckboxList from '@/components/parts/SearchableCheckboxList.vue';
import SettingsInput from '@/components/settings/components/SettingsInput.vue';
import BaseModal from '@/components/parts/BaseModal.vue';
import ColorPickerElement from '@/components/parts/ColorPickerElement.vue';

window.gv_core.BaseButton = BaseButton;
window.gv_core.BaseModal = BaseModal;
window.gv_core.Loader = Loader;
window.gv_core.LocationIcon = LocationIcon;
window.gv_core.ScrollingSelect = ScrollingSelect;
window.gv_core.SearchableCheckboxList = SearchableCheckboxList;
window.gv_core.ToggleButton = ToggleButton;
window.gv_core.SettingsInput = SettingsInput;
window.BaseButton = window.gv_core.BaseButton;
window.BaseModal = window.gv_core.BaseModal;
window.Loader = window.gv_core.Loader;
window.LocationIcon = window.gv_core.LocationIcon;
window.ScrollingSelect = window.gv_core.ScrollingSelect;
window.SearchableCheckboxList = window.gv_core.SearchableCheckboxList;
window.ToggleButton = window.gv_core.ToggleButton;
window.SettingsInput = window.gv_core.SettingsInput;

const app = createApp(App);

// Register global components for extensions to use
app.component('BaseButton', BaseButton);
app.component('ToggleButton', ToggleButton);
app.component('Loader', Loader);
app.component('SettingsInput', SettingsInput);
app.component('BaseModal', BaseModal);
app.component('ColorPickerElement', ColorPickerElement);

app.use(router).use(store);

function addNotFoundRoute(): void {
    if (router.hasRoute('NotFound')) {
        return;
    }
    router.addRoute({
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        meta: { title: 'Not Found' },
        component: () => import('./components/NotFoundPage.vue'),
    });
}

const extensionLoad = loadExtensions({ app, router, store, platformState, utils: extensionUtils, toast });

if (mountWaitsForExtensions(firstPaintPath())) {
    void extensionLoad.then(() => {
        addNotFoundRoute();
        app.mount('#app');
    });
} else {
    app.mount('#app');
    void extensionLoad.then(() => {
        addNotFoundRoute();
        if (router.currentRoute.value.matched.length === 0) {
            void router.replace(router.currentRoute.value.fullPath);
        }
    });
}

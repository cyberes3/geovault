import './assets/css/main.css'

/**
 * Dev/proxy-safe fallback:
 * If a social share URL is opened on the frontend dev server (or any origin that serves the SPA directly),
 * remap /share/map/<id>/ to the hash route before app initialization/auth checks.
 */
function remapSocialSharePathToHashRoute() {
    if (typeof window === 'undefined') return
    if (window.location.hash) return

    const match = window.location.pathname.match(/^\/share\/map\/([0-9a-fA-F-]{36})\/?$/)
    if (!match) return

    const shareId = match[1]
    window.location.replace(`/#/mapshare?id=${encodeURIComponent(shareId)}`)
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
import 'simple-code-editor/themes/themes.css'
import 'simple-code-editor/themes/themes-base16.css'

import axios from 'axios';
import { toast } from '@/utils/toast';
import { updateUserSetting } from '@/utils/userSettingsService';
import { loadSettingsFromValues } from '@/utils/userSettingsService';
import { keyValueToNested, getNestedValue } from '@/utils/settingsUtils';
import { geolocationManager } from '@/utils/map/geolocationManager.js';
import { parseCoordinates, looksLikeCoordinates, validateCoordinates } from '@/utils/geo/coordinates';
import { searchGeocoding, getGeocodingResultCoordinates, getGeocodingResultLabel } from '@/utils/geocodingSearch.js';
import { listUsers } from '@/api/services/userApi';
import { realtimeSocket } from '@/assets/js/websocket/realtimeSocket';
import * as HeroiconsOutline from '@heroicons/vue/24/outline';
import * as HeroiconsSolid from '@heroicons/vue/24/solid';
import { tileSourceCatalog, RasterTileUrls } from '@/utils/map/openlayers/index.js';

import { extensionRegistry } from '@/utils/extensionRegistry.js';
import { createRouteWrapper } from '@/extensions/routeWrapper';
import { createPlatformStateBridge } from '@/extensions/platformState';
import { loadExtensions } from '@/extensions/extensionLoader';

// OpenLayers imports for shared use
import * as ol from 'ol';
import * as olSource from 'ol/source';
import * as olLayer from 'ol/layer';
import * as olProj from 'ol/proj';
import * as olGeom from 'ol/geom';
import * as olStyle from 'ol/style';
import * as olInteraction from 'ol/interaction';
import Feature from 'ol/Feature';

import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';

// PWA Install Prompt Handling
window.addEventListener('beforeinstallprompt', (e) => {
    // Prevent the mini-infobar from appearing on mobile
    e.preventDefault();
    // Stash the event so it can be triggered later.
    void store.dispatch('extensionsRuntime/setDeferredPrompt', e);
    console.log('PWA: beforeinstallprompt event captured');
});

const platformState = createPlatformStateBridge(store);

// Shared, cross-cutting helpers made available to every extension. There is no raw store here on
// purpose: extensions get read-mostly access to app state through `platformState` above.
const extensionUtils = {
    updateUserSetting,
    loadSettingsFromValues,
    keyValueToNested,
    getNestedValue,
    getCurrentPosition: () => geolocationManager.getCurrentPosition(),
    checkGeolocationPermission: () => geolocationManager.checkPermission(),
    parseCoordinates,
    looksLikeCoordinates,
    validateCoordinates,
    searchGeocoding,
    getGeocodingResultCoordinates,
    getGeocodingResultLabel,
    listUsers
};

const olNamespace = {
    ...ol,
    source: olSource,
    layer: olLayer,
    proj: olProj,
    geom: olGeom,
    style: olStyle,
    interaction: olInteraction,
    Feature: Feature
};

// Shared platform APIs: single namespace for clarity. Top-level aliases kept for extension UMD builds.
const GeoVault = {
    registry: extensionRegistry,
    utils: extensionUtils,
    toast,
    platformState,
    tileSourceCatalog,
    RasterTileUrls
};

window.gv_core = {
    GeoVault,
    Vue: VueState,
    VueRouter: VueRouterState,
    Vuex: VuexState,
    axios,
    HeroiconsOutline,
    HeroiconsSolid,
    ol: olNamespace,
    maplibre: maplibregl,
    createRouteWrapper,
    tileSourceCatalog,
    RasterTileUrls,
    platformState,
    realtimeSocket,
    BaseButton: null, // set below after import
    BaseModal: null, // set below after import
    Loader: null, // set below after import
    LocationIcon: null, // set below after import
    ScrollingSelect: null, // set below after import
    SearchableCheckboxList: null, // set below after import
    ToggleButton: null, // set below after import
    SettingsInput: null // set below after import
};

// Top-level aliases so extension UMD bundles (external vue, ol, etc.) keep working
window.GeoVault = window.gv_core.GeoVault;
window.Vue = window.gv_core.Vue;
window.VueRouter = window.gv_core.VueRouter;
window.Vuex = window.gv_core.Vuex;
window.axios = window.gv_core.axios;
window.HeroiconsOutline = window.gv_core.HeroiconsOutline;
window.HeroiconsSolid = window.gv_core.HeroiconsSolid;
window.ol = window.gv_core.ol;
window.maplibregl = window.gv_core.maplibre;

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

// Start app after loading extensions (extension routes are added during loadExtensions)
void loadExtensions({ app, router, store, platformState, utils: extensionUtils, toast }).then(() => {
    // Add catch-all last so /extensions/* routes match before NotFound
    router.addRoute({
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        meta: { title: 'Not Found' },
        component: () => import('./components/NotFoundPage.vue'),
    });
    app.use(router)
        .use(store)
        .mount('#app');
});

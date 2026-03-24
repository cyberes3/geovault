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
    navigator.serviceWorker.register('/sw.js?v=' + __SW_VERSION__)
}

// PWA Install Prompt Handling
window.addEventListener('beforeinstallprompt', (e) => {
    // Prevent the mini-infobar from appearing on mobile
    e.preventDefault();
    // Stash the event so it can be triggered later.
    store.commit('setDeferredPrompt', e);
    console.log('PWA: beforeinstallprompt event captured');
});

import { createApp, h, markRaw } from 'vue'
import * as VueState from 'vue'
import * as VueRouterState from 'vue-router'
import * as VuexState from 'vuex'
import App from './App.vue'
import store from "@/assets/js/store.ts";
import router from "@/router.js";
import '@/assets/css/root.css'
import 'simple-code-editor/themes/themes.css'
import 'simple-code-editor/themes/themes-base16.css'

import { extensionRegistry } from './utils/extensionRegistry.js';
import axios from 'axios';
import { toast } from '@/utils/toast';
import { updateUserSetting, loadSettingsFromStore } from '@/utils/userSettingsService.js';
import { keyValueToNested, getNestedValue } from '@/utils/settingsUtils.js';
import { geolocationManager } from '@/utils/map/geolocationManager.js';
import { parseCoordinates, looksLikeCoordinates } from '@/utils/coordinateParser.js';
import { ExtensionApi } from './utils/extensionApi.js';
import { realtimeSocket } from '@/assets/js/websocket/realtimeSocket.js';
import * as HeroiconsOutline from '@heroicons/vue/24/outline';
import * as HeroiconsSolid from '@heroicons/vue/24/solid';

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

// Inject utils into registry
extensionRegistry.utils.updateUserSetting = updateUserSetting;
extensionRegistry.utils.loadSettingsFromStore = loadSettingsFromStore;
extensionRegistry.utils.keyValueToNested = keyValueToNested;
extensionRegistry.utils.getNestedValue = getNestedValue;
extensionRegistry.toast = toast;

// Shared platform APIs: single namespace for clarity. Top-level aliases kept for extension UMD builds.
const GeoVault = {
    registry: extensionRegistry,
    utils: {
        updateUserSetting,
        loadSettingsFromStore,
        keyValueToNested,
        getNestedValue,
        getCurrentPosition: () => geolocationManager.getCurrentPosition(),
        checkGeolocationPermission: () => geolocationManager.checkPermission(),
        parseCoordinates,
        looksLikeCoordinates
    },
    toast
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

/**
 * Creates a route component wrapper that provides extensionApi (and optionally extensionRouter)
 * to descendant components via inject(), and renders the given component using Vue's h().
 * Use this for extension routes so each extension gets its own api instance without overwriting
 * app-level provide. Call from extension setup: gv_core.createRouteWrapper(MyView, { api }) or
 * createRouteWrapper(MyView, { api, router }).
 * @param {import('vue').Component} component - The root component for the route
 * @param {{ api: import('./utils/extensionApi').ExtensionApi, router?: object, [key: string]: unknown }} options - api (required), router (optional), and any extra provide keys
 * @returns {object} Component option object with provide() and render()
 */
function createRouteWrapper(component, options = {}) {
    const h = VueState.h;
    if (!h) {
        console.warn('[gv_core.createRouteWrapper] Vue h not available');
        return component;
    }
    const { api, router = null, ...rest } = options;
    return {
        provide() {
            return {
                extensionApi: api,
                extensionRouter: router,
                ...rest
            };
        },
        render() {
            return h(component);
        }
    };
}

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
    BaseButton: null, // set below after import
    BaseModal: null, // set below after import
    Loader: null, // set below after import
    LocationIcon: null, // set below after import
    ScrollingSelect: null, // set below after import
    SearchableCheckboxList: null, // set below after import
    ToggleButton: null, // set below after import
    store: null
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
window.gv_core.realtimeSocket = realtimeSocket;

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
window.BaseButton = window.gv_core.BaseButton;
window.BaseModal = window.gv_core.BaseModal;
window.Loader = window.gv_core.Loader;
window.LocationIcon = window.gv_core.LocationIcon;
window.ScrollingSelect = window.gv_core.ScrollingSelect;
window.SearchableCheckboxList = window.gv_core.SearchableCheckboxList;
window.ToggleButton = window.gv_core.ToggleButton;

const app = createApp(App);

// Register global components for extensions to use
app.component('BaseButton', BaseButton);
app.component('ToggleButton', ToggleButton);
app.component('Loader', Loader);
app.component('SettingsInput', SettingsInput);
app.component('BaseModal', BaseModal);
app.component('ColorPickerElement', ColorPickerElement);

/**
 * Helper to find setup function from extension module.
 * 
 * Extensions must export the setup function as default: `export default setup`
 * UMD bundles create a global variable (e.g., window.ExampleExtension) that is the setup function.
 * 
 * @param {any} module - The imported module (unused, but kept for API consistency)
 * @param {string} extensionName - Extension name in snake_case
 * @returns {Function|null} The setup function or null if not found
 */
function findSetupFunction(module, extensionName) {
    // UMD bundles create a global variable based on the 'name' in vite.config.js
    // Pattern: capitalize each word, append 'Extension' (unless last word is already "extension")
    const words = extensionName.split('_');
    const lastWord = words[words.length - 1].toLowerCase();
    const globalName = words
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join('') + (lastWord === 'extension' ? '' : 'Extension');

    // UMD bundle sets the global to the setup function directly
    const setup = window[globalName];
    if (setup && typeof setup === 'function') {
        return setup;
    }

    return null;
}

/**
 * Helper to create a scoped router wrapper for extensions.
 *
 * @param {any} router - Vue Router instance
 * @param {string} prefix - URL prefix for scoping (e.g., '/extensions/my-extension')
 * @returns {Object} Scoped router with addRoute and navigate
 */
function createScopedRouter(router, prefix) {
    return {
        addRoute: (route) => {
            const raw = route.path;
            const relPath = (raw === '' || raw === '/') ? '' : (raw.startsWith('/') ? raw : `/${raw}`);
            route.path = `${prefix}${relPath}`;
            router.addRoute(route);
        },
        navigate: (path) => {
            const raw = path;
            const relPath = (raw === '' || raw === '/') ? '' : (raw.startsWith('/') ? raw : `/${raw}`);
            return router.push(`${prefix}${relPath}`);
        }
    };
}

/**
 * Helper to create a scoped registry wrapper.
 * 
 * @param {any} registry - Extension registry instance
 * @param {string} prefix - URL prefix for scoping (e.g., '/extensions/my-extension')
 * @returns {Object} Scoped registry object with registration methods
 */
function createScopedRegistry(registry, prefix) {
    return {
        registerNavLink: (link) => {
            const raw = link.path;
            const relPath = (raw === '' || raw === '/') ? '' : (raw.startsWith('/') ? raw : `/${raw}`);
            link.fullPath = `${prefix}${relPath}`;
            registry.registerNavLink(link);
        },
        registerSettingsTab: (tab) => {
            registry.registerSettingsTab(tab);
        },
        registerTool: (tool) => {
            const raw = tool.path;
            const relPath = (raw === '' || raw === '/') ? '' : (raw.startsWith('/') ? raw : `/${raw}`);
            tool.fullPath = `${prefix}${relPath}`;
            registry.registerTool(tool);
        },
        registerRoutes: (routes) => {
            // Scope all route paths with extension prefix
            const scopedRoutes = routes.map(route => {
                const raw = route.path;
                const relPath = (raw === '' || raw === '/') ? '' : (raw.startsWith('/') ? raw : `/${raw}`);
                return {
                    ...route,
                    path: `${prefix}${relPath}`
                };
            });
            registry.registerRoutes(scopedRoutes);
        }
    };
}

/**
 * Resolves an icon specification to a Vue component.
 * 
 * @param {string|null|undefined} icon - Icon specification (heroicon name, SVG path, or inline SVG)
 * @param {string} kebabName - Kebab-case extension name for constructing static URLs
 * @returns {Promise<Object|null>} Vue component or null
 */
async function resolveExtensionIcon(icon, kebabName) {
    if (!icon) {
        return null;
    }

    // 1. Handle inline SVG first (starts with <svg) - check this before file paths
    // because inline SVG might contain "/" in xmlns or paths
    if (typeof icon === 'string') {
        const trimmedIcon = icon.trim();
        if (trimmedIcon.startsWith('<svg')) {
            try {
                // Ensure xmlns is present in the SVG string (critical for paths to render)
                let processedSvg = trimmedIcon;
                if (!processedSvg.includes('xmlns=')) {
                    processedSvg = processedSvg.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');
                }

                // Store the SVG string in closure
                const baseSvgString = processedSvg;

                // Create Vue component that renders the full SVG string with CSS styling
                const SvgIconComponent = {
                    props: {
                        class: String
                    },
                    mounted() {
                        // Set innerHTML on the wrapper div to render the full SVG
                        if (this.$el) {
                            let svgWithClass = baseSvgString;
                            if (this.class) {
                                if (svgWithClass.includes('class=')) {
                                    svgWithClass = svgWithClass.replace(/class=["'][^"']*["']/g, `class="${this.class}"`);
                                } else {
                                    svgWithClass = svgWithClass.replace('<svg', `<svg class="${this.class}"`);
                                }
                            }
                            this.$el.innerHTML = svgWithClass;

                            // Apply CSS to make SVG elements use currentColor
                            const svgElement = this.$el.querySelector('svg');
                            if (svgElement) {
                                const styleId = `svg-icon-style-${Date.now()}-${Math.random()}`;
                                svgElement.setAttribute('data-icon-style-id', styleId);

                                const style = document.createElement('style');
                                style.id = styleId;
                                style.textContent = `
                                svg[data-icon-style-id="${styleId}"] * {
                                    fill: currentColor !important;
                                    stroke: currentColor !important;
                                }
                                svg[data-icon-style-id="${styleId}"] *[fill="none"] {
                                    fill: none !important;
                                }
                                svg[data-icon-style-id="${styleId}"] *[stroke="none"] {
                                    stroke: none !important;
                                }
                            `;
                                document.head.appendChild(style);
                            }
                        }
                    },
                    updated() {
                        // Update class when prop changes
                        if (this.$el) {
                            let svgWithClass = baseSvgString;
                            if (this.class) {
                                if (svgWithClass.includes('class=')) {
                                    svgWithClass = svgWithClass.replace(/class=["'][^"']*["']/g, `class="${this.class}"`);
                                } else {
                                    svgWithClass = svgWithClass.replace('<svg', `<svg class="${this.class}"`);
                                }
                            }
                            this.$el.innerHTML = svgWithClass;

                            // Re-apply CSS
                            const svgElement = this.$el.querySelector('svg');
                            if (svgElement) {
                                const styleId = svgElement.getAttribute('data-icon-style-id') || `svg-icon-style-${Date.now()}-${Math.random()}`;
                                svgElement.setAttribute('data-icon-style-id', styleId);

                                const existingStyle = document.getElementById(styleId);
                                if (!existingStyle) {
                                    const style = document.createElement('style');
                                    style.id = styleId;
                                    style.textContent = `
                                    svg[data-icon-style-id="${styleId}"] * {
                                        fill: currentColor !important;
                                        stroke: currentColor !important;
                                    }
                                    svg[data-icon-style-id="${styleId}"] *[fill="none"] {
                                        fill: none !important;
                                    }
                                    svg[data-icon-style-id="${styleId}"] *[stroke="none"] {
                                        stroke: none !important;
                                    }
                                `;
                                    document.head.appendChild(style);
                                }
                            }
                        }
                    },
                    beforeUnmount() {
                        // Clean up style element
                        const svgElement = this.$el?.querySelector('svg');
                        if (svgElement) {
                            const styleId = svgElement.getAttribute('data-icon-style-id');
                            if (styleId) {
                                const style = document.getElementById(styleId);
                                if (style) {
                                    style.remove();
                                }
                            }
                        }
                    },
                    render() {
                        // Return a div wrapper - the SVG will be inserted via innerHTML in mounted
                        return h('div', {
                            class: 'inline-flex items-center'
                        });
                    }
                };

                return markRaw(SvgIconComponent);
            } catch (err) {
                console.error(`Failed to parse inline SVG icon for extension ${kebabName}:`, err);
                return null;
            }
        }
    }

    // 2. Try heroicon name (check both outline and solid)
    // Check this after inline SVG to avoid false matches
    if (typeof icon === 'string' && !icon.includes('/') && !icon.trim().startsWith('<')) {
        // Check if it's a heroicon name
        const heroiconOutline = window.gv_core.HeroiconsOutline?.[icon];
        const heroiconSolid = window.gv_core.HeroiconsSolid?.[icon];

        if (heroiconOutline) {
            return markRaw(heroiconOutline);
        }
        if (heroiconSolid) {
            return markRaw(heroiconSolid);
        }
    }

    // 3. Handle SVG path (ends with .svg or contains / but doesn't start with <svg)
    if (typeof icon === 'string' && (icon.endsWith('.svg') || (icon.includes('/') && !icon.trim().startsWith('<svg')))) {
        try {
            // Construct static URL
            const iconUrl = icon.startsWith('/')
                ? icon
                : `/extensions/static/${kebabName}/${icon}`;

            // Fetch SVG content
            const response = await axios.get(iconUrl, { responseType: 'text' });
            const svgContent = response.data;

            // Ensure xmlns is present in the SVG string (critical for paths to render)
            let processedSvg = svgContent;
            if (!processedSvg.includes('xmlns=')) {
                processedSvg = processedSvg.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');
            }

            // Store the SVG string in closure
            const baseSvgString = processedSvg;

            // Create Vue component that renders the full SVG string with CSS styling
            const SvgIconComponent = {
                props: {
                    class: String
                },
                mounted() {
                    // Set innerHTML on the wrapper div to render the full SVG
                    if (this.$el) {
                        let svgWithClass = baseSvgString;
                        // Add or update class attribute in the SVG string
                        if (this.class) {
                            if (svgWithClass.includes('class=')) {
                                svgWithClass = svgWithClass.replace(/class=["'][^"']*["']/g, `class="${this.class}"`);
                            } else {
                                svgWithClass = svgWithClass.replace('<svg', `<svg class="${this.class}"`);
                            }
                        }
                        this.$el.innerHTML = svgWithClass;

                        // Apply CSS to make SVG elements use currentColor
                        // Target all SVG child elements except those with fill="none"
                        const svgElement = this.$el.querySelector('svg');
                        if (svgElement) {
                            // Create a style element for this SVG
                            const styleId = `svg-icon-style-${Date.now()}-${Math.random()}`;
                            svgElement.setAttribute('data-icon-style-id', styleId);

                            // Add CSS that makes all fills and strokes use currentColor
                            // but preserve elements that explicitly have fill="none"
                            const style = document.createElement('style');
                            style.id = styleId;
                            style.textContent = `
                                svg[data-icon-style-id="${styleId}"] * {
                                    fill: currentColor !important;
                                    stroke: currentColor !important;
                                }
                                svg[data-icon-style-id="${styleId}"] *[fill="none"] {
                                    fill: none !important;
                                }
                                svg[data-icon-style-id="${styleId}"] *[stroke="none"] {
                                    stroke: none !important;
                                }
                            `;
                            document.head.appendChild(style);
                        }
                    }
                },
                updated() {
                    // Update class when prop changes
                    if (this.$el) {
                        let svgWithClass = baseSvgString;
                        if (this.class) {
                            if (svgWithClass.includes('class=')) {
                                svgWithClass = svgWithClass.replace(/class=["'][^"']*["']/g, `class="${this.class}"`);
                            } else {
                                svgWithClass = svgWithClass.replace('<svg', `<svg class="${this.class}"`);
                            }
                        }
                        this.$el.innerHTML = svgWithClass;

                        // Re-apply CSS
                        const svgElement = this.$el.querySelector('svg');
                        if (svgElement) {
                            const styleId = svgElement.getAttribute('data-icon-style-id') || `svg-icon-style-${Date.now()}-${Math.random()}`;
                            svgElement.setAttribute('data-icon-style-id', styleId);

                            const existingStyle = document.getElementById(styleId);
                            if (!existingStyle) {
                                const style = document.createElement('style');
                                style.id = styleId;
                                style.textContent = `
                                    svg[data-icon-style-id="${styleId}"] * {
                                        fill: currentColor !important;
                                        stroke: currentColor !important;
                                    }
                                    svg[data-icon-style-id="${styleId}"] *[fill="none"] {
                                        fill: none !important;
                                    }
                                    svg[data-icon-style-id="${styleId}"] *[stroke="none"] {
                                        stroke: none !important;
                                    }
                                `;
                                document.head.appendChild(style);
                            }
                        }
                    }
                },
                beforeUnmount() {
                    // Clean up style element
                    const svgElement = this.$el?.querySelector('svg');
                    if (svgElement) {
                        const styleId = svgElement.getAttribute('data-icon-style-id');
                        if (styleId) {
                            const style = document.getElementById(styleId);
                            if (style) {
                                style.remove();
                            }
                        }
                    }
                },
                render() {
                    // Return a div wrapper - the SVG will be inserted via innerHTML in mounted
                    return h('div', {
                        class: 'inline-flex items-center'
                    });
                }
            };

            return markRaw(SvgIconComponent);
        } catch (err) {
            console.error(`Failed to load SVG file icon ${icon} for extension ${kebabName}:`, err);
            return null;
        }
    }

    return null;
}

/**
 * Dynamically load and setup extensions.
 * 
 * Each extension must export a setup() function as the default export:
 *   async function setup({ app, router, store, registry, api, utils, toast, metadata }) {
 *     // Extension initialization
 *   }
 *   export default setup
 */
async function loadExtensions() {
    try {
        const response = await axios.get('/api/extensions/');
        const extensions = response.data;
        const mapPrefixes = (Array.isArray(extensions) ? extensions : [])
            .filter(ext => ext && ext.map_route)
            .map(ext => `/extensions/${ext.name.replace(/_/g, '-')}`);
        store.commit('setExtensionMapRoutePrefixes', mapPrefixes);
        const publicSharePrefixes = (Array.isArray(extensions) ? extensions : [])
            .filter(ext => ext && ext.public_share_route)
            .map(ext => `/extensions/${ext.name.replace(/_/g, '-')}/share`);
        store.commit('setExtensionPublicShareRoutePrefixes', publicSharePrefixes);
        const successfullyLoaded = [];

        for (const ext of extensions) {
            if (ext.frontend_entry) {
                try {
                    // Load the extension script
                    const module = await import(/* @vite-ignore */ ext.frontend_entry);

                    // Dynamically load extension CSS if it exists (resolved by backend)
                    if (ext.frontend_css) {
                        const link = document.createElement('link');
                        link.rel = 'stylesheet';
                        link.href = ext.frontend_css;
                        document.head.appendChild(link);
                    }

                    // Scoped wrappers
                    const kebabName = ext.name.replace(/_/g, '-');
                    const prefix = `/extensions/${kebabName}`;

                    // Find the exported setup function
                    const setup = findSetupFunction(module, ext.name);

                    if (!setup || typeof setup !== 'function') {
                        console.error(
                            `Extension ${ext.name} has no valid setup function.\n` +
                            `Expected: export default setup (where setup is an async function)`
                        );
                        continue;
                    }

                    // Create ExtensionApi instance with automatic CSRF handling
                    const api = new ExtensionApi(ext.name);

                    // Create scoped router and registry using helper functions
                    const scopedRouter = createScopedRouter(router, prefix);
                    const scopedRegistry = createScopedRegistry(extensionRegistry, prefix);

                    // Resolve icon if provided
                    const resolvedIcon = await resolveExtensionIcon(ext.icon, kebabName);

                    // Extension metadata
                    const metadata = {
                        name: ext.name,
                        version: ext.version || 'unknown',
                        kebabName: kebabName,
                        icon: resolvedIcon
                    };

                    // Call setup function with enhanced API
                    await setup({
                        app,
                        router: scopedRouter,  // Scoped router for extension routes
                        mainRouter: router,     // Main platform router for navigation
                        store,
                        registry: scopedRegistry,
                        api,
                        utils: {
                            updateUserSetting,
                            loadSettingsFromStore,
                            keyValueToNested,
                            getNestedValue,
                            getCurrentPosition: () => geolocationManager.getCurrentPosition(),
                            checkGeolocationPermission: () => geolocationManager.checkPermission()
                        },
                        toast,
                        metadata
                    });

                    // Only add to successfully loaded if setup completed without errors
                    successfullyLoaded.push(ext.name);
                } catch (err) {
                    console.error(`Failed to load extension module ${ext.name}:`, err);
                }
            }
        }

        if (successfullyLoaded.length > 0) {
            console.log(`[Extensions] Successfully loaded ${successfullyLoaded.length} extensions: ${successfullyLoaded.join(', ')}`);
        } else {
            console.log('[Extensions] No extensions were enabled or loaded');
        }
    } catch (err) {
        console.error('Failed to fetch extensions metadata:', err);
    }
}

// Start app after loading extensions (extension routes are added during loadExtensions)
loadExtensions().then(() => {
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
    window.gv_core.store = store;
    window.store = window.gv_core.store;
});

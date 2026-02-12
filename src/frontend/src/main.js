import './assets/css/main.css'

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
import { ExtensionApi } from './utils/extensionApi.js';
import * as HeroiconsOutline from '@heroicons/vue/24/outline';
import * as HeroiconsSolid from '@heroicons/vue/24/solid';

// OpenLayers imports for shared use
import * as ol from 'ol';
import * as olSource from 'ol/source';
import * as olLayer from 'ol/layer';
import * as olProj from 'ol/proj';
import * as olGeom from 'ol/geom';
import * as olStyle from 'ol/style';
import Feature from 'ol/Feature';

// Inject utils into registry
extensionRegistry.utils.updateUserSetting = updateUserSetting;
extensionRegistry.utils.loadSettingsFromStore = loadSettingsFromStore;
extensionRegistry.utils.keyValueToNested = keyValueToNested;
extensionRegistry.utils.getNestedValue = getNestedValue;
extensionRegistry.toast = toast;

// Expose GeoVault global for extensions to access shared state and utils without bundling
window.GeoVault = {
    registry: extensionRegistry,
    utils: {
        updateUserSetting,
        loadSettingsFromStore,
        keyValueToNested,
        getNestedValue
    },
    toast
};

// Expose shared libraries for extensions
window.Vue = VueState;
window.VueRouter = VueRouterState;
window.Vuex = VuexState;
window.axios = axios;
window.HeroiconsOutline = HeroiconsOutline;
window.HeroiconsSolid = HeroiconsSolid;
window.ol = {
    ...ol,
    source: olSource,
    layer: olLayer,
    proj: olProj,
    geom: olGeom,
    style: olStyle,
    Feature: Feature
};
window.Loader = Loader;

import BaseButton from '@/components/parts/BaseButton.vue';
import ToggleButton from '@/components/parts/ToggleButton.vue';
import Loader from '@/components/parts/Loader.vue';
import SettingsInput from '@/components/settings/components/SettingsInput.vue';

const app = createApp(App);

// Register global components for extensions to use
app.component('BaseButton', BaseButton);
app.component('ToggleButton', ToggleButton);
app.component('Loader', Loader);
app.component('SettingsInput', SettingsInput);

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
            const relPath = route.path.startsWith('/') ? route.path : `/${route.path}`;
            route.path = `${prefix}${relPath}`;
            router.addRoute(route);
        },
        navigate: (path) => {
            const relPath = path.startsWith('/') ? path : `/${path}`;
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
            const relPath = link.path.startsWith('/') ? link.path : `/${link.path}`;
            link.fullPath = `${prefix}${relPath}`;
            registry.registerNavLink(link);
        },
        registerSettingsTab: (tab) => {
            registry.registerSettingsTab(tab);
        },
        registerTool: (tool) => {
            const relPath = tool.path.startsWith('/') ? tool.path : `/${tool.path}`;
            tool.fullPath = `${prefix}${relPath}`;
            registry.registerTool(tool);
        },
        registerRoutes: (routes) => {
            // Scope all route paths with extension prefix
            const scopedRoutes = routes.map(route => {
                const relPath = route.path.startsWith('/') ? route.path : `/${route.path}`;
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
        const heroiconOutline = window.HeroiconsOutline?.[icon];
        const heroiconSolid = window.HeroiconsSolid?.[icon];

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
                            getNestedValue
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

// Start app after loading extensions
loadExtensions().then(() => {
    app.use(router)
        .use(store)
        .mount('#app');
    window.store = store;
});

import './assets/css/main.css'

import { createApp } from 'vue'
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
 * Helper to create a scoped router wrapper with navigation helpers.
 * 
 * @param {any} router - Vue Router instance
 * @param {string} prefix - URL prefix for scoping (e.g., '/extensions/my-extension')
 * @returns {Object} Scoped router object with navigation methods
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
        },
        go: (n) => router.go(n),
        back: () => router.back(),
        forward: () => router.forward()
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

                    // Extension metadata
                    const metadata = {
                        name: ext.name,
                        version: ext.version || 'unknown',
                        description: ext.description || '',
                        kebabName: kebabName
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

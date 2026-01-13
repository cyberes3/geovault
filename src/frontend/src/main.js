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
 * Dynamically load and setup extensions.
 */
async function loadExtensions() {
    try {
        const response = await axios.get('/api/extensions/');
        const extensions = response.data;

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
                    let setup = module.setup;
                    if (!setup) {
                        const pascalName = ext.name.split(/[_-]/).map(part => part.charAt(0).toUpperCase() + part.slice(1)).join('');
                        if (window[pascalName] && typeof window[pascalName].setup === 'function') {
                            setup = window[pascalName].setup;
                        } else if (window[ext.name] && typeof window[ext.name].setup === 'function') {
                            setup = window[ext.name].setup;
                        }
                    }

                    const scopedRouter = {
                        addRoute: (route) => {
                            const relPath = route.path.startsWith('/') ? route.path : `/${route.path}`;
                            route.path = `${prefix}${relPath}`;
                            router.addRoute(route);
                        }
                    };

                    const scopedRegistry = {
                        ...extensionRegistry,
                        registerNavLink: (link) => {
                            const relPath = link.path.startsWith('/') ? link.path : `/${link.path}`;
                            link.fullPath = `${prefix}${relPath}`;
                            extensionRegistry.registerNavLink(link);
                        }
                    };

                    const api = {
                        url: (path) => {
                            const relPath = path.startsWith('/') ? path : `/${path}`;
                            return `/api/extensions/${kebabName}${relPath}`;
                        }
                    };

                    if (typeof setup === 'function') {
                        await setup({
                            app,
                            router: scopedRouter,
                            store,
                            registry: scopedRegistry,
                            api,
                            utils: {
                                updateUserSetting,
                                loadSettingsFromStore,
                                keyValueToNested,
                                getNestedValue
                            },
                            toast
                        });
                    } else {
                        console.error(`Extension ${ext.name} has no valid setup function.`);
                    }
                } catch (err) {
                    console.error(`Failed to load extension module ${ext.name}:`, err);
                }
            }
        }

        const loadedNames = extensions.filter(ext => ext.frontend_entry).map(ext => ext.name);
        if (loadedNames.length > 0) {
            console.log(`[Extensions] Successfully loaded ${loadedNames.length} extensions: ${loadedNames.join(', ')}`);
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

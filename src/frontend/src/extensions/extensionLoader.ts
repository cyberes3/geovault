/**
 * Discovers enabled extensions from the backend, loads their UMD bundles, and calls each one's
 * `setup()` with a scoped router/registry plus the shared platform contract (`ExtensionApi`,
 * `platformState`, `utils`, `toast`). This is the only place that talks to `listExtensions()`.
 */
import type { App } from 'vue';
import type { Router, RouteRecordRaw } from 'vue-router';
import type { Store } from 'vuex';
import { markRaw } from 'vue';
import { listExtensions } from '@/api/services/extensionsApi';
import { extensionRegistry } from '@/utils/extensionRegistry';
import { ExtensionApi } from '@/utils/extensionApi';
import { resolveExtensionIcon } from './resolveExtensionIcon';
import type { PlatformStateBridge } from './platformState';
import type { ExtensionSetupUtils, ExtensionSetupContext } from './extensionContractTypes';

interface DiscoveredExtension {
    name: string;
    version?: string;
    icon?: string | null;
    map_route?: boolean;
    public_share_route?: boolean;
    frontend_entry?: string;
    frontend_css?: string;
}

function toKebabCase(name: string): string {
    return name.replace(/_/g, '-');
}

/**
 * UMD bundles expose their setup function as a global named after the extension, e.g.
 * `live_track` -> `window.LiveTrackExtension`. Extensions must `export default setup`.
 */
function findSetupFunction(extensionName: string): ((ctx: ExtensionSetupContext) => Promise<void>) | null {
    const words = extensionName.split('_');
    const lastWord = words[words.length - 1].toLowerCase();
    const globalName = words
        .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
        .join('') + (lastWord === 'extension' ? '' : 'Extension');

    const setup = (window as unknown as Record<string, unknown>)[globalName];
    return typeof setup === 'function' ? (setup as (ctx: ExtensionSetupContext) => Promise<void>) : null;
}

function withPrefixedPath(prefix: string, rawPath: string): string {
    const relPath = rawPath === '' || rawPath === '/' ? '' : (rawPath.startsWith('/') ? rawPath : `/${rawPath}`);
    return `${prefix}${relPath}`;
}

export function createScopedRouter(router: Router, prefix: string) {
    return {
        addRoute: (route: RouteRecordRaw) => {
            router.addRoute({ ...route, path: withPrefixedPath(prefix, route.path) });
        },
        navigate: (path: string) => router.push(withPrefixedPath(prefix, path))
    };
}

export function createScopedRegistry(registry: typeof extensionRegistry, prefix: string) {
    return {
        registerNavLink: (link: { path: string; [key: string]: unknown }) => {
            registry.registerNavLink({ ...link, fullPath: withPrefixedPath(prefix, link.path) });
        },
        registerSettingsTab: (tab: { component: unknown; [key: string]: unknown }) => {
            registry.registerSettingsTab(tab);
        },
        registerTool: (tool: { path: string; [key: string]: unknown }) => {
            registry.registerTool({ ...tool, fullPath: withPrefixedPath(prefix, tool.path) });
        }
    };
}

export interface LoadExtensionsDeps {
    app: App;
    router: Router;
    store: Store<unknown>;
    platformState: PlatformStateBridge;
    utils: ExtensionSetupUtils;
    toast: ExtensionSetupContext['toast'];
}

export async function loadExtensions(deps: LoadExtensionsDeps): Promise<void> {
    const { app, router, store, platformState, utils, toast } = deps;

    try {
        const extensions = (await listExtensions()) as DiscoveredExtension[];
        const list = Array.isArray(extensions) ? extensions : [];

        await store.dispatch('extensionsRuntime/setMapRoutePrefixes',
            list.filter((ext) => ext.map_route).map((ext) => `/extensions/${toKebabCase(ext.name)}`));
        await store.dispatch('extensionsRuntime/setPublicShareRoutePrefixes',
            list.filter((ext) => ext.public_share_route).map((ext) => `/extensions/${toKebabCase(ext.name)}/share`));

        const successfullyLoaded: string[] = [];

        for (const ext of list) {
            if (!ext.frontend_entry) continue;

            try {
                await import(/* @vite-ignore */ ext.frontend_entry);

                if (ext.frontend_css) {
                    const link = document.createElement('link');
                    link.rel = 'stylesheet';
                    link.href = ext.frontend_css;
                    document.head.appendChild(link);
                }

                const kebabName = toKebabCase(ext.name);
                const prefix = `/extensions/${kebabName}`;
                const setup = findSetupFunction(ext.name);

                if (!setup) {
                    console.error(
                        `Extension ${ext.name} has no valid setup function.\n` +
                        `Expected: export default setup (where setup is an async function)`
                    );
                    continue;
                }

                const api = new ExtensionApi(ext.name);
                const resolvedIcon = await resolveExtensionIcon(ext.icon, kebabName);

                await setup({
                    app,
                    router: createScopedRouter(router, prefix),
                    mainRouter: router,
                    registry: createScopedRegistry(extensionRegistry, prefix),
                    api,
                    platformState,
                    utils,
                    toast,
                    metadata: {
                        name: ext.name,
                        version: ext.version ?? 'unknown',
                        kebabName,
                        icon: resolvedIcon ? markRaw(resolvedIcon) : null
                    }
                });

                successfullyLoaded.push(ext.name);
            } catch (err) {
                console.error(`Failed to load extension module ${ext.name}:`, err);
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

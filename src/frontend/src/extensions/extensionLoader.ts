/**
 * Discovers enabled extensions, loads their UMD bundles, and calls each setup().
 */
import type { App, Component } from 'vue';
import type { Router, RouteRecordRaw } from 'vue-router';
import type { Store } from 'vuex';
import { markRaw } from 'vue';
import { listExtensions } from '@/api/services/extensionsApi';
import { extensionRegistry } from '@/utils/extensionRegistry';
import { ExtensionApi } from '@/utils/extensionApi';
import { resolveExtensionIcon } from './resolveExtensionIcon';
import { loadMapEngine, resolveSetupFunction } from './bundle';
import { extensionRouteTable, wrapperName } from './routeTable';
import { scopePlatformStateToExtension } from './useExtensionSettings';
import type { PlatformStateBridge } from './platformState';
import type { ExtensionSetupUtils, ExtensionSetupContext, ScopedExtensionRegistry } from './extensionContractTypes';
import type { ExtensionListItem, MapEngine } from '../../../packages/extension-sdk/src/manifest';

function toKebabCase(name: string): string {
    return name.replace(/_/g, '-');
}

function withPrefixedPath(prefix: string, rawPath: string): string {
    const relPath = rawPath === '' || rawPath === '/' ? '' : (rawPath.startsWith('/') ? rawPath : `/${rawPath}`);
    return `${prefix}${relPath}`;
}

interface PrefetchedExtension {
    ext: ExtensionListItem;
    kebabName: string;
    module: PromiseSettledResult<unknown>;
    icon: PromiseSettledResult<Component | null>;
}

interface PrefetchExtensionsDeps {
    importModule: (entry: string, engine?: MapEngine) => Promise<unknown>;
    resolveIcon: (icon: string | null | undefined, kebabName: string) => Promise<Component | null>;
}

async function importExtensionModule(entry: string, engine?: MapEngine): Promise<unknown> {
    await loadMapEngine(engine);
    return import(/* @vite-ignore */ entry);
}

const defaultPrefetchDeps: PrefetchExtensionsDeps = {
    importModule: importExtensionModule,
    resolveIcon: resolveExtensionIcon
};

export function prefetchExtensions(
    list: ExtensionListItem[],
    deps: PrefetchExtensionsDeps = defaultPrefetchDeps
): Map<ExtensionListItem, Promise<PrefetchedExtension>> {
    const prefetches = new Map<ExtensionListItem, Promise<PrefetchedExtension>>();

    for (const ext of list) {
        if (!ext.frontend_entry) continue;

        const kebabName = toKebabCase(ext.name);
        const entry = ext.frontend_entry;
        const engine = ext.requires_map_engine;
        prefetches.set(ext, Promise.allSettled([
            deps.importModule(entry, engine),
            deps.resolveIcon(ext.icon, kebabName)
        ]).then(([module, icon]) => ({ ext, kebabName, module, icon })));
    }

    return prefetches;
}

export function createScopedRouter(router: Router, prefix: string, kebabName: string, mapLayout: boolean, publicShare: boolean) {
    return {
        addRoute: (route: RouteRecordRaw) => {
            const routeName = String(route.name ?? 'index');
            const keepAliveKey = wrapperName(kebabName, routeName);
            const fullPath = withPrefixedPath(prefix, String(route.path ?? ''));
            extensionRouteTable.record({
                path: fullPath,
                name: routeName,
                keepAliveKey,
                publicShare: publicShare && fullPath.endsWith('/share'),
                mapLayout,
                titleMode: 'document',
            });
            router.addRoute({ ...route, path: fullPath });
        },
        navigate: (path: string) => router.push(withPrefixedPath(prefix, path))
    };
}

export function createScopedRegistry(registry: typeof extensionRegistry, prefix: string, kebabName: string): ScopedExtensionRegistry {
    return {
        registerNavLink: (link) => {
            registry.registerNavLink({ ...link, fullPath: withPrefixedPath(prefix, link.path) });
        },
        registerSettingsTab: (tab) => {
            registry.registerSettingsTab({ ...tab, id: `${kebabName}:${tab.id}` });
        },
        registerTool: (tool) => {
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
        const extensions = await listExtensions();
        const list = Array.isArray(extensions) ? extensions : [];

        if (list.length > 0) {
            await store.dispatch('extensionsRuntime/setMapRoutePrefixes',
                list.filter((ext) => ext.map_route).map((ext) => `/extensions/${toKebabCase(ext.name)}`));
            await store.dispatch('extensionsRuntime/setPublicShareRoutePrefixes',
                list.filter((ext) => ext.public_share_route).map((ext) => `/extensions/${toKebabCase(ext.name)}/share`));
        }

        const successfullyLoaded: string[] = [];
        const prefetches = prefetchExtensions(list);

        for (const ext of list) {
            const prefetch = prefetches.get(ext);
            if (!prefetch) continue;

            try {
                const { kebabName, module, icon } = await prefetch;

                if (module.status === 'rejected') {
                    throw module.reason;
                }

                if (ext.frontend_css) {
                    const link = document.createElement('link');
                    link.rel = 'stylesheet';
                    link.href = ext.frontend_css;
                    document.head.appendChild(link);
                }

                const prefix = `/extensions/${kebabName}`;
                const setup = resolveSetupFunction(module.value, ext.umd_global);

                if (!setup) {
                    console.error(
                        `Extension ${ext.name} has no valid setup function.\n` +
                        `Expected: export default setup, or window[${JSON.stringify(ext.umd_global)}]`
                    );
                    continue;
                }

                const api = new ExtensionApi(ext.name);
                if (icon.status === 'rejected') {
                    console.error(`Failed to resolve icon for extension ${ext.name}:`, icon.reason);
                }
                const resolvedIcon = icon.status === 'fulfilled' ? icon.value : null;
                const scopedState = scopePlatformStateToExtension(platformState, ext.name);

                await setup({
                    app,
                    router: createScopedRouter(router, prefix, kebabName, !!ext.map_route, !!ext.public_share_route),
                    mainRouter: router,
                    registry: createScopedRegistry(extensionRegistry, prefix, kebabName),
                    api,
                    platformState: scopedState,
                    utils,
                    toast,
                    metadata: {
                        name: ext.name,
                        version: ext.version || 'unknown',
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

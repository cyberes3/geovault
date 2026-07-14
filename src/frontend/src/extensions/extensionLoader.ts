/**
 * Discovers enabled extensions from the backend, loads their UMD bundles, and calls each one's
 * `setup()` with a scoped router/registry plus the shared platform contract (`ExtensionApi`,
 * `platformState`, `utils`, `toast`). This is the only place that talks to `listExtensions()`.
 */
import type { App, Component } from 'vue';
import type { Router, RouteRecordRaw } from 'vue-router';
import type { Store } from 'vuex';
import { markRaw } from 'vue';
import { listExtensions } from '@/api/services/extensionsApi';
import { extensionRegistry } from '@/utils/extensionRegistry';
import { ExtensionApi } from '@/utils/extensionApi';
import { resolveExtensionIcon } from './resolveExtensionIcon';
import type { PlatformStateBridge } from './platformState';
import type { ExtensionSetupUtils, ExtensionSetupContext, ScopedExtensionRegistry } from './extensionContractTypes';

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

interface PrefetchedExtension {
    ext: DiscoveredExtension;
    kebabName: string;
    module: PromiseSettledResult<unknown>;
    icon: PromiseSettledResult<Component | null>;
}

interface PrefetchExtensionsDeps {
    importModule: (entry: string) => Promise<unknown>;
    resolveIcon: (icon: string | null | undefined, kebabName: string) => Promise<Component | null>;
}

/**
 * Extension UMD bundles are built against `window.ol`/`window.maplibregl` as externals (see
 * `vite.extension-shared.mjs`) so they share core's single instance instead of bundling their own
 * stale copy. An extension that statically `import`s from `'ol'`/`'maplibre-gl'` (e.g. exif_geotagger's
 * `import { Vector } from 'ol/source'`) dereferences those globals synchronously the moment its UMD
 * wrapper is evaluated - not lazily on first actual use - so if `loadOl()`/`loadMaplibreGl()` haven't
 * resolved yet, the extension's own module fails to load at all ("b.ol is undefined"). Only `ol` is
 * awaited here since no extension currently imports `maplibre-gl` as a build-time external (live_track/
 * places both read `window.gv_core.maplibre` at runtime instead), keeping MapLibre itself lazy.
 */
async function importExtensionModule(entry: string): Promise<unknown> {
    await window.gv_core.loadOl();
    return import(/* @vite-ignore */ entry);
}

const defaultPrefetchDeps: PrefetchExtensionsDeps = {
    importModule: importExtensionModule,
    resolveIcon: resolveExtensionIcon
};

/**
 * Fetches every extension's UMD bundle and icon concurrently instead of one at a time. Extensions
 * are independent, network-bound, unrelated bundles - fetching them sequentially (as a naive
 * `for` loop with `await` inside would) means each one waits for the previous one's fetch *and*
 * `setup()` call to finish before its own fetch even starts, which showed up as an almost fully
 * serialized waterfall in production (~1.75s for 4 small bundles that could load in well under a
 * second in parallel). `Promise.allSettled` per extension ensures a rejected fetch here can never
 * produce an unhandled-rejection warning even though we don't inspect the result until later.
 *
 * `deps` defaults to the real `import()`/`resolveExtensionIcon` and only exists so tests can
 * substitute stubs with controllable timing/rejection without mocking ESM dynamic import.
 */
export function prefetchExtensions(
    list: DiscoveredExtension[],
    deps: PrefetchExtensionsDeps = defaultPrefetchDeps
): Map<DiscoveredExtension, Promise<PrefetchedExtension>> {
    const prefetches = new Map<DiscoveredExtension, Promise<PrefetchedExtension>>();

    for (const ext of list) {
        if (!ext.frontend_entry) continue;

        const kebabName = toKebabCase(ext.name);
        const entry = ext.frontend_entry;
        prefetches.set(ext, Promise.allSettled([
            deps.importModule(entry),
            deps.resolveIcon(ext.icon, kebabName)
        ]).then(([module, icon]) => ({ ext, kebabName, module, icon })));
    }

    return prefetches;
}

export function createScopedRouter(router: Router, prefix: string) {
    return {
        addRoute: (route: RouteRecordRaw) => {
            router.addRoute({ ...route, path: withPrefixedPath(prefix, route.path) });
        },
        navigate: (path: string) => router.push(withPrefixedPath(prefix, path))
    };
}

export function createScopedRegistry(registry: typeof extensionRegistry, prefix: string): ScopedExtensionRegistry {
    return {
        registerNavLink: (link) => {
            registry.registerNavLink({ ...link, fullPath: withPrefixedPath(prefix, link.path) });
        },
        registerSettingsTab: (tab) => {
            registry.registerSettingsTab(tab);
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
        const extensions = (await listExtensions()) as DiscoveredExtension[];
        const list = Array.isArray(extensions) ? extensions : [];

        await store.dispatch('extensionsRuntime/setMapRoutePrefixes',
            list.filter((ext) => ext.map_route).map((ext) => `/extensions/${toKebabCase(ext.name)}`));
        await store.dispatch('extensionsRuntime/setPublicShareRoutePrefixes',
            list.filter((ext) => ext.public_share_route).map((ext) => `/extensions/${toKebabCase(ext.name)}/share`));

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
                const setup = findSetupFunction(ext.name);

                if (!setup) {
                    console.error(
                        `Extension ${ext.name} has no valid setup function.\n` +
                        `Expected: export default setup (where setup is an async function)`
                    );
                    continue;
                }

                const api = new ExtensionApi(ext.name);
                if (icon.status === 'rejected') {
                    console.error(`Failed to resolve icon for extension ${ext.name}:`, icon.reason);
                }
                const resolvedIcon = icon.status === 'fulfilled' ? icon.value : null;

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

/**
 * Wraps an extension-provided component so it gets its own `ExtensionApi`/`PlatformStateBridge`
 * via `inject()` (scoped to this component tree only, so multiple extensions never clobber each
 * other's app-level `provide()`), an error boundary that contains a crash to this extension
 * instead of taking down the whole app, and a stable `.gv-ext-<name>` CSS scoping root.
 *
 * Extensions call this for every route component and settings tab component they register:
 *   gv_core.createRouteWrapper(MyView, { api, platformState, router })
 */
import { h, onErrorCaptured, provide, ref, type Component } from 'vue';
import type { ExtensionApi } from '@/utils/extensionApi';
import type { PlatformStateBridge } from './platformState';

export interface RouteWrapperOptions {
    api: ExtensionApi;
    platformState?: PlatformStateBridge;
    router?: unknown;
    [extraProvideKey: string]: unknown;
}

export function createRouteWrapper(component: Component, options: RouteWrapperOptions) {
    const { api, router = null, platformState = null, ...rest } = options;
    const kebabName = api.kebabName;
    const scopeClass = `gv-ext gv-ext-${kebabName}`;

    return {
        name: `ExtensionBoundary_${kebabName}`,
        setup() {
            provide('extensionApi', api);
            provide('extensionRouter', router);
            provide('platformState', platformState);
            for (const [key, value] of Object.entries(rest)) {
                provide(key, value);
            }

            const caughtError = ref<Error | null>(null);
            onErrorCaptured((error) => {
                console.error(`[Extension ${kebabName}] Uncaught error:`, error);
                caughtError.value = error instanceof Error ? error : new Error(String(error));
                return false; // Stop propagation: contain the crash to this extension's subtree.
            });

            return () => {
                if (caughtError.value) {
                    return h('div', { class: `${scopeClass} gv-ext-error` }, [
                        h('p', { class: 'gv-ext-error__message' },
                            `The "${kebabName}" extension encountered an error and could not continue.`)
                    ]);
                }
                return h('div', { class: scopeClass }, [h(component)]);
            };
        }
    };
}

/**
 * Ambient declaration for the `window.gv_core` global the core app exposes to extensions at
 * runtime (see the core frontend's `src/types/geovault.d.ts` for the full surface). Only the
 * subset this extension actually reads is declared here, with locally-defined shapes rather than
 * importing core's real types, since this is a separate TypeScript project with no shared `@/`
 * path resolution back into core.
 */
import type { Component } from 'vue';

export interface ToastService {
    success(message: string): void;
    error(message: string): void;
    info(message: string): void;
    warning(message: string): void;
}

export interface ExtensionSetupUtils {
    loadSettingsFromValues: (
        config: Array<{ key: string; defaultValue: unknown }>,
        settings: Record<string, unknown> | null
    ) => Record<string, unknown>;
    keyValueToNested: (key: string, value: unknown) => unknown;
}

declare global {
    interface Window {
        gv_core: {
            GeoVault: {
                utils: ExtensionSetupUtils;
                toast: ToastService;
            };
            createRouteWrapper: (component: Component, options: Record<string, unknown>) => Component;
        };
    }
}

export {};

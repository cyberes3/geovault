/**
 * Local shape of the setup context the core app passes to this extension's `main.ts` default
 * export (see the core frontend's `src/extensions/extensionContractTypes.ts` for the real,
 * fully-typed contract). Declared locally rather than imported, since this extension is a
 * separate TypeScript project.
 */
import type { ExtensionApi } from './extension-api';

export interface RouterLike {
    addRoute(route: { path: string; name?: string; meta?: Record<string, unknown>; component: unknown }): void;
}

export interface ExtensionSetupContext {
    router: RouterLike;
    registry: {
        registerTool(tool: { label: string; path: string; icon?: unknown }): void;
    };
    api: ExtensionApi;
}

/**
 * Local shape of the setup context the core app passes to this extension's `main.ts` default
 * export (see the core frontend's `src/extensions/extensionContractTypes.ts` for the real,
 * fully-typed contract). Declared locally rather than imported, since this extension is a
 * separate TypeScript project.
 */
import type { Component } from 'vue';
import type { ExtensionApi } from './extension-api';
import type { PlatformStateBridge } from './platform-state';

export interface RouterLike {
    addRoute(route: { path: string; name?: string; meta?: Record<string, unknown>; component: unknown }): void;
    push(location: unknown): Promise<unknown>;
}

export interface ExtensionMetadata {
    name: string;
    version: string;
    kebabName: string;
    icon: Component | null;
}

export interface ExtensionSetupContext {
    router: RouterLike;
    mainRouter: RouterLike;
    registry: {
        registerNavLink(link: { label: string; path: string; icon?: unknown }): void;
        registerSettingsTab(tab: { id: string; label: string; component: unknown; icon?: unknown }): void;
        registerTool(tool: { label: string; path: string; icon?: unknown }): void;
    };
    api: ExtensionApi;
    platformState: PlatformStateBridge;
    metadata: ExtensionMetadata;
}

/**
 * Shared type definitions for the extension setup contract. `geovault.d.ts` re-exports the
 * `Window.gv_core` shape built from these so extension authors and core code see the same types.
 */
import type { App, Component } from 'vue';
import type { Router, RouteRecordRaw } from 'vue-router';
import type { ExtensionApi } from '@/utils/extensionApi';
import type { PlatformStateBridge } from './platformState';

export interface ExtensionMetadata {
    name: string;
    version: string;
    kebabName: string;
    icon: Component | null;
}

export interface ToastService {
    success(message: string): void;
    error(message: string): void;
    info(message: string): void;
    warning(message: string): void;
}

export interface ExtensionSetupUtils {
    updateUserSetting(settingsUpdate: Record<string, unknown>): Promise<{ success: true; settings: Record<string, unknown> }>;
    loadSettingsFromValues(
        config: Array<{ key: string; defaultValue: unknown }>,
        settings: Record<string, unknown> | null
    ): Record<string, unknown>;
    keyValueToNested(key: string, value: unknown): unknown;
    getNestedValue(obj: unknown, key: string): unknown;
    getCurrentPosition(): Promise<GeolocationPosition>;
    checkGeolocationPermission(): Promise<PermissionState | 'unknown'>;
    parseCoordinates(input: string): { lat: number; lon: number } | null;
    looksLikeCoordinates(input: string): boolean;
    validateCoordinates(lat: number, lon: number): boolean;
    searchGeocoding(query: string, options?: { signal?: AbortSignal }): Promise<{ ok: boolean; features: unknown[]; error?: string }>;
    getGeocodingResultCoordinates(result: unknown): { lon: number; lat: number } | null;
    getGeocodingResultLabel(result: unknown): string;
    listUsers(): Promise<Array<{ id: number; email: string }>>;
}

export interface ScopedExtensionRouter {
    addRoute(route: RouteRecordRaw): void;
    navigate(path: string): ReturnType<Router['push']>;
}

export interface ScopedExtensionRegistry {
    registerNavLink(link: { label: string; path: string; icon?: unknown }): void;
    registerSettingsTab(tab: { id: string; label: string; component: unknown; icon?: unknown }): void;
    registerTool(tool: { label: string; path: string; icon?: unknown }): void;
}

export interface ExtensionSetupContext {
    app: App;
    router: ScopedExtensionRouter;
    mainRouter: Router;
    registry: ScopedExtensionRegistry;
    api: ExtensionApi;
    platformState: PlatformStateBridge;
    utils: ExtensionSetupUtils;
    toast: ToastService;
    metadata: ExtensionMetadata;
}

/** The setup function that every extension must export as its default export. */
export type ExtensionSetup = (context: ExtensionSetupContext) => Promise<void>;

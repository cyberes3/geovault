import type { App, Component, ComputedRef } from 'vue';
import type { Router, RouteRecordRaw } from 'vue-router';

export interface ExtensionApiErrorInfo {
    message: string;
    status?: number;
    data?: unknown;
    error: unknown;
}

export interface ExtensionApi {
    readonly extensionName: string;
    readonly kebabName: string;
    readonly baseUrl: string;
    get(path: string, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    post(path: string, data?: unknown, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    put(path: string, data?: unknown, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    patch(path: string, data?: unknown, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    delete(path: string, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    handleError(error: unknown, fallback?: string): ExtensionApiErrorInfo;
    toastError(error: unknown, fallback?: string): void;
}

export interface PlatformStateBridge {
    readonly userSettings: ComputedRef<Record<string, unknown> | null>;
    readonly currentUser: ComputedRef<Record<string, unknown> | null>;
    fetchUserSettings(): Promise<void>;
    saveUserSetting(update: Record<string, unknown>): Promise<Record<string, unknown>>;
}

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
    parseCoordinates(input: string): { lat: number; lng: number } | null;
    looksLikeCoordinates(input: string): boolean;
    validateCoordinates(coordinates: unknown, geometryType: string | null | undefined): { valid: boolean; error: string | null };
    searchGeocoding(query: string, options?: { signal?: AbortSignal }): Promise<{ ok: boolean; features: unknown[]; error?: string }>;
    getGeocodingResultCoordinates(result: unknown): { lon: number; lat: number } | null;
    getGeocodingResultLabel(result: unknown): string;
    listUsers(): Promise<Array<{ id: number; email: string }>>;
}

export interface ScopedExtensionRouter {
    addRoute(route: RouteRecordRaw): void;
    navigate(path: string): ReturnType<Router['push']>;
}

export interface RouterLike {
    addRoute?(route: { path: string; name?: string; meta?: Record<string, unknown>; component: unknown }): void;
    push(location: unknown): Promise<unknown>;
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

export type ExtensionSetup = (context: ExtensionSetupContext) => Promise<void>;

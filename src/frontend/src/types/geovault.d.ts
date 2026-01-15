/**
 * GeoVault Extension System Type Definitions
 */

export interface ExtensionMetadata {
    name: string;
    version: string;
    description: string;
    kebabName: string;
    frontend_entry?: string;
    frontend_css?: string;
    settings_schema?: ExtensionSettingSchema[];
}

export interface ExtensionSettingSchema {
    key: string;
    type: 'string' | 'boolean' | 'number' | 'select';
    label: string;
    default?: any;
    description?: string;
    options?: { label: string; value: any }[]; // For 'select' type
    secret?: boolean; // If true, might need special handling (masked input)
}

export interface ToastService {
    success(message: string): void;
    error(message: string): void;
    info(message: string): void;
    warning(message: string): void;
}

export interface ExtensionApi {
    get(url: string, config?: any): Promise<any>;
    post(url: string, data?: any, config?: any): Promise<any>;
    put(url: string, data?: any, config?: any): Promise<any>;
    patch(url: string, data?: any, config?: any): Promise<any>;
    delete(url: string, config?: any): Promise<any>;
    handleError(error: any): { message: string, status?: number };
    url(path: string): string;
}

export interface ExtensionRegistry {
    registerNavLink(link: { label: string; path: string; icon?: any }): void;
    registerSettingsTab(tab: { id: string; label: string; component: any }): void;
    registerRoutes(routes: any[]): void;
}

export interface ExtensionUtils {
    updateUserSetting(key: string, value: any): Promise<void>;
    loadSettingsFromStore(): Promise<any>;
    keyValueToNested(key: string, value: any): any;
    getNestedValue(obj: any, key: string): any;
}

export interface ExtensionSetupContext {
    app: any; // Vue App instance
    router: any; // Vue Router instance
    store: any; // Vuex Store instance
    registry: ExtensionRegistry;
    api: ExtensionApi;
    utils: ExtensionUtils;
    toast: ToastService;
    metadata: ExtensionMetadata;
}

/**
 * The setup function that every extension must export.
 */
export type ExtensionSetup = (context: ExtensionSetupContext) => Promise<void>;

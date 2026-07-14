/**
 * Local shape of the `ExtensionApi` instance the core app injects into every extension (see the
 * core frontend's `src/utils/extensionApi.ts` for the real implementation). Declared locally
 * rather than imported, since this extension is a separate TypeScript project.
 */
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
    url(path: string): string;
    get(path: string, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    post(path: string, data?: unknown, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    put(path: string, data?: unknown, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    patch(path: string, data?: unknown, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    delete(path: string, config?: Record<string, unknown>): Promise<{ data: unknown }>;
    handleError(error: unknown, fallback?: string): ExtensionApiErrorInfo;
    toastError(error: unknown, fallback?: string): void;
}

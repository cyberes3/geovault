/**
 * Extension API helper class for making HTTP requests to extension endpoints.
 * Shares the same client factory (CSRF interceptor + ApiError-normalized rejections) as
 * the core `httpClient`, scoped to the extension's own `/api/extensions/<name>` baseURL,
 * so core and extensions get identical request/error-handling behavior.
 *
 * Example usage:
 *   const api = new ExtensionApi('my_extension');
 *   const data = await api.get('/items/');
 *   await api.post('/items/', { name: 'Test' });
 *   // On error: api.toastError(err, 'Failed to save item');
 */
import type { AxiosInstance, AxiosRequestConfig } from 'axios';
import { createHttpClient } from '@/api/httpClient';
import { getCookie } from '@/utils/cookies';
import { ApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast';

export class ExtensionApi {
    readonly extensionName: string;
    readonly kebabName: string;
    readonly baseUrl: string;
    private readonly client: AxiosInstance;

    constructor(extensionName: string) {
        this.extensionName = extensionName;
        this.kebabName = extensionName.replace(/_/g, '-');
        this.baseUrl = `/api/extensions/${this.kebabName}`;
        this.client = createHttpClient(this.baseUrl);
    }

    getCsrfToken(): string | null {
        return getCookie('csrftoken');
    }

    url(path: string): string {
        const cleanPath = path.startsWith('/') ? path : `/${path}`;
        return `${this.baseUrl}${cleanPath}`;
    }

    async get(path: string, config: AxiosRequestConfig = {}) {
        return this.client.get(path, config);
    }

    async post(path: string, data: unknown = {}, config: AxiosRequestConfig = {}) {
        return this.client.post(path, data, config);
    }

    async put(path: string, data: unknown = {}, config: AxiosRequestConfig = {}) {
        return this.client.put(path, data, config);
    }

    async patch(path: string, data: unknown = {}, config: AxiosRequestConfig = {}) {
        return this.client.patch(path, data, config);
    }

    async delete(path: string, config: AxiosRequestConfig = {}) {
        return this.client.delete(path, config);
    }

    /**
     * Extract standardized error information from a request failure.
     */
    handleError(error: unknown, fallback = 'An error occurred') {
        const apiError = ApiError.from(error, fallback);
        console.error(`[Extension ${this.extensionName}] API Error:`, apiError.message, error);
        return {
            message: apiError.message,
            status: apiError.status,
            data: apiError.data,
            error,
        };
    }

    /**
     * Show a toast with the server error message.
     */
    toastError(error: unknown, fallback = 'An error occurred'): void {
        toast.error(this.handleError(error, fallback).message);
    }
}

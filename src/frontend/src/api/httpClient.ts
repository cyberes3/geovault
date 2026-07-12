import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { getCookie } from '@/utils/cookies';
import { ApiError } from '@/utils/apiError';

/**
 * Attaches the CSRF cookie to every mutating request. Shared by the core client and
 * `ExtensionApi` so core and extensions send identical headers.
 */
function attachCsrfInterceptor(client: AxiosInstance): void {
    client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
        const csrfToken = getCookie('csrftoken');
        if (csrfToken) {
            config.headers.set('X-CSRFToken', csrfToken);
        }
        return config;
    });
}

/**
 * Normalizes every rejected response/network failure into an `ApiError` so callers
 * never need to branch on axios error shapes themselves.
 */
function attachErrorNormalizationInterceptor(client: AxiosInstance): void {
    client.interceptors.response.use(
        (response: AxiosResponse) => response,
        (error: unknown) => Promise.reject(ApiError.from(error)),
    );
}

/**
 * Creates a pre-configured axios instance: JSON content type, CSRF header injection,
 * and ApiError-normalized rejections. Used directly for the core API (relative
 * `baseURL`) and by `ExtensionApi` (per-extension `baseURL`) so both get identical
 * request/error handling behavior.
 */
export function createHttpClient(baseURL = '', config: AxiosRequestConfig = {}): AxiosInstance {
    const client = axios.create({
        baseURL,
        headers: {
            'Content-Type': 'application/json',
        },
        ...config,
    });

    attachCsrfInterceptor(client);
    attachErrorNormalizationInterceptor(client);

    return client;
}

/** Shared client for first-party core API calls. */
export const httpClient: AxiosInstance = createHttpClient();

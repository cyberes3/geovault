/**
 * Extension API helper class for making HTTP requests to extension endpoints.
 *
 * Example usage:
 *   const api = new ExtensionApi('my_extension');
 *   const data = await api.get('/items/');
 *   await api.post('/items/', { name: 'Test' });
 *   // On error: api.toastError(err, 'Failed to save item');
 */
import axios from 'axios';
import { getCookie } from '@/assets/js/auth.js';
import { ApiError } from '@/utils/apiError.js';
import { toast } from '@/utils/toast';

export class ExtensionApi {
    /**
     * @param {string} extensionName - Extension name in snake_case
     */
    constructor(extensionName) {
        this.extensionName = extensionName;
        this.kebabName = extensionName.replace(/_/g, '-');
        this.baseUrl = `/api/extensions/${this.kebabName}`;

        this.axios = axios.create({
            baseURL: this.baseUrl,
            headers: {
                'Content-Type': 'application/json',
            },
        });

        this.axios.interceptors.request.use(
            (config) => {
                const csrfToken = getCookie('csrftoken');
                if (csrfToken) {
                    config.headers['X-CSRFToken'] = csrfToken;
                }
                return config;
            },
            (error) => Promise.reject(error),
        );
    }

    getCsrfToken() {
        return getCookie('csrftoken');
    }

    url(path) {
        const cleanPath = path.startsWith('/') ? path : `/${path}`;
        return `${this.baseUrl}${cleanPath}`;
    }

    async get(path, config = {}) {
        return this.axios.get(path, config);
    }

    async post(path, data = {}, config = {}) {
        return this.axios.post(path, data, config);
    }

    async put(path, data = {}, config = {}) {
        return this.axios.put(path, data, config);
    }

    async patch(path, data = {}, config = {}) {
        return this.axios.patch(path, data, config);
    }

    async delete(path, config = {}) {
        return this.axios.delete(path, config);
    }

    /**
     * Extract standardized error information from an axios error.
     * @param {unknown} error
     * @param {string} [fallback='An error occurred']
     */
    handleError(error, fallback = 'An error occurred') {
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
     * @param {unknown} error
     * @param {string} [fallback='An error occurred']
     */
    toastError(error, fallback = 'An error occurred') {
        toast.error(this.handleError(error, fallback).message);
    }
}

/**
 * Extension API helper class for making HTTP requests to extension endpoints.
 * 
 * This class wraps axios with automatic CSRF token handling, URL scoping,
 * and better error handling for extensions.
 * 
 * Example usage:
 *   const api = new ExtensionApi('my_extension');
 *   const data = await api.get('/items/');
 *   await api.post('/items/', { name: 'Test' });
 */
import axios from 'axios';
import { getCookie } from '@/assets/js/auth.js';
import { toast } from '@/utils/toast';

export class ExtensionApi {
    /**
     * Create a new ExtensionApi instance.
     * 
     * @param {string} extensionName - Extension name in snake_case
     * @param {Object} options - Configuration options
     * @param {boolean} options.autoToastErrors - Automatically show toast on errors (default: false)
     */
    constructor(extensionName, options = {}) {
        this.extensionName = extensionName;
        this.kebabName = extensionName.replace(/_/g, '-');
        this.baseUrl = `/api/extensions/${this.kebabName}`;
        this.autoToastErrors = options.autoToastErrors === true; // Default to false
        
        // Create axios instance with default config
        this.axios = axios.create({
            baseURL: this.baseUrl,
            headers: {
                'Content-Type': 'application/json',
            },
        });
        
        // Add request interceptor for CSRF token
        this.axios.interceptors.request.use(
            (config) => {
                const csrfToken = getCookie('csrftoken');
                if (csrfToken) {
                    config.headers['X-CSRFToken'] = csrfToken;
                }
                return config;
            },
            (error) => {
                return Promise.reject(error);
            }
        );
        
        // Note: We don't add automatic error toast interceptor by default
        // Developers should handle errors explicitly in their code
    }
    
    /**
     * Get CSRF token from cookies.
     * 
     * @returns {string|null} CSRF token or null if not found
     */
    getCsrfToken() {
        return getCookie('csrftoken');
    }
    
    /**
     * Build full URL for an extension endpoint.
     * 
     * @param {string} path - Relative path (e.g., '/items/' or 'items/')
     * @returns {string} Full URL
     */
    url(path) {
        const cleanPath = path.startsWith('/') ? path : `/${path}`;
        return `${this.baseUrl}${cleanPath}`;
    }
    
    /**
     * Make a GET request.
     * 
     * @param {string} path - Relative path
     * @param {Object} config - Axios config
     * @returns {Promise} Axios response
     */
    async get(path, config = {}) {
        return this.axios.get(path, config);
    }
    
    /**
     * Make a POST request.
     * 
     * @param {string} path - Relative path
     * @param {Object} data - Request body
     * @param {Object} config - Axios config
     * @returns {Promise} Axios response
     */
    async post(path, data = {}, config = {}) {
        return this.axios.post(path, data, config);
    }
    
    /**
     * Make a PUT request.
     * 
     * @param {string} path - Relative path
     * @param {Object} data - Request body
     * @param {Object} config - Axios config
     * @returns {Promise} Axios response
     */
    async put(path, data = {}, config = {}) {
        return this.axios.put(path, data, config);
    }
    
    /**
     * Make a PATCH request.
     * 
     * @param {string} path - Relative path
     * @param {Object} data - Request body
     * @param {Object} config - Axios config
     * @returns {Promise} Axios response
     */
    async patch(path, data = {}, config = {}) {
        return this.axios.patch(path, data, config);
    }
    
    /**
     * Make a DELETE request.
     * 
     * @param {string} path - Relative path
     * @param {Object} config - Axios config
     * @returns {Promise} Axios response
     */
    async delete(path, config = {}) {
        return this.axios.delete(path, config);
    }
    
    /**
     * Handle API errors with standardized error messages.
     * 
     * This method extracts error information but does NOT show toast notifications.
     * Developers should call this method and handle errors explicitly, including
     * showing toast notifications if desired.
     * 
     * @param {Error} error - Axios error object
     * @returns {Object} Error information with message, status, data, and original error
     * 
     * @example
     * try {
     *   await api.get('/items/');
     * } catch (error) {
     *   const errorInfo = api.handleError(error);
     *   toast.error(errorInfo.message);
     * }
     */
    handleError(error) {
        let message = 'An error occurred';
        
        if (error.response) {
            // Server responded with error status
            const status = error.response.status;
            const data = error.response.data;
            
            if (data && data.error) {
                message = data.error;
            } else if (data && data.message) {
                message = data.message;
            } else if (data && typeof data === 'string') {
                message = data;
            } else {
                switch (status) {
                    case 400:
                        message = 'Bad request';
                        break;
                    case 401:
                        message = 'Unauthorized';
                        break;
                    case 403:
                        message = 'Forbidden';
                        break;
                    case 404:
                        message = 'Not found';
                        break;
                    case 500:
                        message = 'Server error';
                        break;
                    default:
                        message = `Error ${status}`;
                }
            }
        } else if (error.request) {
            // Request made but no response received
            message = 'Network error - no response from server';
        } else {
            // Error setting up request
            message = error.message || 'Request setup error';
        }
        
        console.error(`[Extension ${this.extensionName}] API Error:`, message, error);
        
        return {
            message,
            status: error.response?.status,
            data: error.response?.data,
            error
        };
    }
}

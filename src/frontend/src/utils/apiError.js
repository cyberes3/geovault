import { toast } from './toast.js';

const STATUS_MESSAGES = {
    400: 'Bad request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not found',
    500: 'Server error',
};

function messageFromResponseData(data, status, fallback) {
    if (data && typeof data.error === 'string' && data.error.trim()) {
        return data.error;
    }
    if (data && typeof data.message === 'string' && data.message.trim()) {
        return data.message;
    }
    if (typeof data === 'string' && data.trim()) {
        return data;
    }
    if (status != null && STATUS_MESSAGES[status]) {
        return STATUS_MESSAGES[status];
    }
    if (status != null) {
        return `Error ${status}`;
    }
    return fallback;
}

/**
 * Standardized API error extracted from axios/fetch failures.
 */
export class ApiError {
    constructor(message, { status, data, cause } = {}) {
        this._message = message;
        this.status = status;
        this.data = data;
        this.cause = cause;
    }

    get message() {
        return this._message;
    }

    /**
     * @param {unknown} error
     * @param {string} [fallback='An error occurred']
     * @returns {ApiError}
     */
    static from(error, fallback = 'An error occurred') {
        if (error instanceof ApiError) {
            return error;
        }

        if (error && typeof error === 'object' && error.response) {
            const { status, data } = error.response;
            return new ApiError(messageFromResponseData(data, status, fallback), {
                status,
                data,
                cause: error,
            });
        }

        if (error && typeof error === 'object' && error.request) {
            return new ApiError('Network error - no response from server', { cause: error });
        }

        const message = (error && typeof error.message === 'string' && error.message.trim())
            ? error.message
            : fallback;
        return new ApiError(message, { cause: error });
    }
}

/**
 * @param {unknown} error
 * @param {string} [fallback='An error occurred']
 * @returns {string}
 */
export function getApiErrorMessage(error, fallback = 'An error occurred') {
    return ApiError.from(error, fallback).message;
}

/**
 * Import upload API uses legacy `{ msg }` responses.
 * @param {unknown} error
 * @param {string} [fallback='Upload failed']
 * @returns {string}
 */
export function getImportApiErrorMessage(error, fallback = 'Upload failed') {
    if (error && typeof error === 'object' && error.response) {
        const { status, data } = error.response;
        if (data && typeof data.msg === 'string' && data.msg.trim()) {
            return data.msg;
        }
        return messageFromResponseData(data, status, fallback);
    }
    return getApiErrorMessage(error, fallback);
}

/**
 * @param {unknown} error
 * @param {string} [fallback='An error occurred']
 */
export function toastApiError(error, fallback = 'An error occurred') {
    toast.error(getApiErrorMessage(error, fallback));
}

/**
 * @param {unknown} error
 * @param {string} [fallback='Upload failed']
 */
export function toastImportApiError(error, fallback = 'Upload failed') {
    toast.error(getImportApiErrorMessage(error, fallback));
}

/**
 * @param {number} status
 * @param {unknown} data
 * @param {string} [fallback='An error occurred']
 * @returns {string}
 */
export function getResponseErrorMessage(status, data, fallback = 'An error occurred') {
    return messageFromResponseData(data, status, fallback);
}

/**
 * @param {Response} response
 * @param {unknown} [data]
 * @param {string} [fallback='An error occurred']
 */
export function toastResponseError(response, data, fallback = 'An error occurred') {
    toast.error(getResponseErrorMessage(response.status, data, fallback));
}

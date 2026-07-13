import { toast } from './toast.js';

const STATUS_MESSAGES: Record<number, string> = {
    400: 'Bad request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not found',
    500: 'Server error',
};

interface AxiosLikeResponse {
    status?: number;
    data?: unknown;
}

interface AxiosLikeError {
    response?: AxiosLikeResponse;
    request?: unknown;
    message?: string;
}

/**
 * True for `AbortController.abort()`/axios-canceled requests (e.g. a viewport bbox fetch
 * superseded by a newer one). Checks `cause` too, since `ApiError.from` preserves the
 * original error there even after normalizing an error's `name`/`code` away.
 */
export function isAbortError(error: unknown): boolean {
    if (!(error instanceof Error)) return false;
    if (error.name === 'AbortError' || error.name === 'CanceledError') return true;
    const { code, cause } = error as { code?: string; cause?: unknown };
    if (code === 'ERR_CANCELED') return true;
    return cause !== undefined && (cause as unknown) !== error && isAbortError(cause);
}

/** Response body field names that different backend endpoints use for their error string. */
const MESSAGE_FIELDS = ['error', 'message', 'msg'] as const;

function messageFromResponseData(data: unknown, status: number | undefined, fallback: string): string {
    if (data && typeof data === 'object') {
        for (const field of MESSAGE_FIELDS) {
            const value = (data as Record<string, unknown>)[field];
            if (typeof value === 'string' && value.trim()) {
                return value;
            }
        }
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
 * Standardized API error extracted from axios/fetch failures. Every failure that
 * passes through `httpClient` (see `api/httpClient.ts`) is normalized into this type,
 * so callers never need to branch on axios vs. fetch error shapes.
 */
export class ApiError extends Error {
    status?: number;
    data?: unknown;
    cause?: unknown;

    constructor(message: string, { status, data, cause }: { status?: number; data?: unknown; cause?: unknown } = {}) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
        this.data = data;
        this.cause = cause;
    }

    static from(error: unknown, fallback = 'An error occurred'): ApiError {
        if (error instanceof ApiError) {
            return error;
        }

        if (isAbortError(error)) {
            const apiError = new ApiError('canceled', { cause: error });
            apiError.name = 'CanceledError';
            return apiError;
        }

        const axiosLike = error as AxiosLikeError;

        if (axiosLike && typeof axiosLike === 'object' && axiosLike.response) {
            const { status, data } = axiosLike.response;
            return new ApiError(messageFromResponseData(data, status, fallback), {
                status,
                data,
                cause: error,
            });
        }

        if (axiosLike && typeof axiosLike === 'object' && axiosLike.request) {
            return new ApiError('Network error - no response from server', { cause: error });
        }

        const message = (axiosLike && typeof axiosLike.message === 'string' && axiosLike.message.trim())
            ? axiosLike.message
            : fallback;
        return new ApiError(message, { cause: error });
    }
}

export function getApiErrorMessage(error: unknown, fallback = 'An error occurred'): string {
    return ApiError.from(error, fallback).message;
}

export function toastApiError(error: unknown, fallback = 'An error occurred'): void {
    toast.error(getApiErrorMessage(error, fallback));
}

export function getResponseErrorMessage(status: number, data: unknown, fallback = 'An error occurred'): string {
    return messageFromResponseData(data, status, fallback);
}

export function toastResponseError(response: { status: number }, data: unknown, fallback = 'An error occurred'): void {
    toast.error(getResponseErrorMessage(response.status, data, fallback));
}

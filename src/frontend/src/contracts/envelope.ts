export interface ErrorEnvelope {
    error: string;
    code: number;
    details?: Record<string, unknown>;
}

export interface ListPage<T> {
    items: T[];
    page: number;
    page_size: number;
    total_items: number;
    total_pages: number;
}

export function isErrorEnvelope(value: unknown): value is ErrorEnvelope {
    if (!value || typeof value !== 'object') {
        return false;
    }
    const record = value as Record<string, unknown>;
    return typeof record.error === 'string' && typeof record.code === 'number';
}

export function errorMessageFromEnvelope(value: unknown, fallback: string): string {
    if (isErrorEnvelope(value) && value.error.trim()) {
        return value.error;
    }
    return fallback;
}

export function itemsFromListPage<T>(value: unknown): T[] {
    if (!value || typeof value !== 'object') {
        return [];
    }
    const items = (value as ListPage<T>).items;
    return Array.isArray(items) ? items : [];
}

export const SHARE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function parseShareId(value: string | null | undefined): string | null {
    if (!value || typeof value !== 'string') {
        return null;
    }
    const normalized = value.trim().toLowerCase();
    return SHARE_ID_PATTERN.test(normalized) ? normalized : null;
}

export function isShareId(value: string | null | undefined): boolean {
    return parseShareId(value) !== null;
}

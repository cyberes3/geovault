/**
 * Reads a single cookie value by name from `document.cookie`.
 * Canonical home for cookie access; the CSRF interceptor in `api/httpClient.ts` and any
 * code that needs the raw CSRF token (e.g. non-JSON form submissions) import from here.
 */
export function getCookie(name: string): string | null {
    if (!document.cookie) {
        return null;
    }
    const cookies = document.cookie.split(';');
    for (const rawCookie of cookies) {
        const cookie = rawCookie.trim();
        if (cookie.substring(0, name.length + 1) === `${name}=`) {
            return decodeURIComponent(cookie.substring(name.length + 1));
        }
    }
    return null;
}

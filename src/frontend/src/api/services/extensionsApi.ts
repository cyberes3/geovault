import { httpClient } from '../httpClient';

export interface ExtensionMetadata {
    name: string;
    display_name?: string;
    icon?: string;
    map_route?: string;
    [key: string]: unknown;
}

let cachedExtensions: ExtensionMetadata[] | null = null;
let extensionsPromise: Promise<ExtensionMetadata[]> | null = null;

/**
 * GET /api/extensions/ - metadata for every enabled extension, cached for the lifetime of the
 * page. `extensionLoader.ts` and `DashboardPage.vue` both need this list independently; without
 * caching, every page load fired the request twice for no reason.
 */
export async function listExtensions(): Promise<ExtensionMetadata[]> {
    if (cachedExtensions) {
        return cachedExtensions;
    }
    if (extensionsPromise) {
        return extensionsPromise;
    }

    extensionsPromise = httpClient
        .get('/api/extensions/')
        .then((response) => {
            cachedExtensions = Array.isArray(response.data) ? response.data : [];
            return cachedExtensions;
        })
        .catch((error) => {
            console.error('Failed to fetch extensions list:', error);
            cachedExtensions = [];
            return cachedExtensions;
        })
        .finally(() => {
            extensionsPromise = null;
        });

    return extensionsPromise;
}

/** Clear cached extensions list (useful for testing or forced refresh). */
export function clearExtensionsCache(): void {
    cachedExtensions = null;
    extensionsPromise = null;
}

/** GET /api/apps/releases/ - release/download info for the companion mobile apps. */
export async function getAppReleases() {
    const response = await httpClient.get('/api/apps/releases/');
    return response.data;
}

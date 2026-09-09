import { itemsFromListPage } from '@/contracts/envelope';
import { httpClient } from '../httpClient';
import type { ExtensionListItem } from '../../../packages/extension-sdk/src/manifest';

export type ExtensionMetadata = ExtensionListItem;

let cachedExtensions: ExtensionMetadata[] | null = null;
let extensionsPromise: Promise<ExtensionMetadata[]> | null = null;

/**
 * GET /api/extensions/ — enabled-extension catalog.
 * Never caches an empty list: a failed fetch must not lock guests out of baked share routes.
 */
export async function listExtensions(): Promise<ExtensionMetadata[]> {
    if (cachedExtensions && cachedExtensions.length > 0) {
        return cachedExtensions;
    }
    if (extensionsPromise) {
        return extensionsPromise;
    }

    extensionsPromise = httpClient
        .get('/api/extensions/')
        .then((response) => {
            const list = itemsFromListPage<ExtensionMetadata>(response.data);
            if (list.length > 0) {
                cachedExtensions = list;
            }
            return list;
        })
        .catch((error) => {
            console.error('Failed to fetch extensions list:', error);
            return cachedExtensions ?? [];
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

export interface AppReleasesResponse {
    uploader_url: string | null;
    places_url: string | null;
    tracker_url: string | null;
    releases_page_url: string;
}

/** GET /api/apps/releases/ - release/download info for the companion mobile apps. */
export async function getAppReleases(): Promise<AppReleasesResponse> {
    const response = await httpClient.get<AppReleasesResponse>('/api/apps/releases/');
    return response.data;
}

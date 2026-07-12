import { httpClient } from '../httpClient';

export interface ExtensionMetadata {
    name: string;
    display_name?: string;
    icon?: string;
    map_route?: string;
    [key: string]: unknown;
}

/** GET /api/extensions/ - metadata for every enabled extension. */
export async function listExtensions(): Promise<ExtensionMetadata[]> {
    const response = await httpClient.get('/api/extensions/');
    return Array.isArray(response.data) ? response.data : [];
}

/** GET /api/apps/releases/ - release/download info for the companion mobile apps. */
export async function getAppReleases() {
    const response = await httpClient.get('/api/apps/releases/');
    return response.data;
}

import { httpClient } from '../httpClient';

export type ShareType = 'tag' | 'collection' | 'feature';

export interface ShareRecord {
    share_id: string;
    share_type: ShareType;
    url: string;
    tag?: string;
    collection_id?: string;
    collection_name?: string;
    allow_downloads?: boolean;
    include_tags?: boolean;
    created_at: string;
    access_count?: number;
}

export interface CreateSharePayload {
    share_type: ShareType;
    tag?: string;
    collection_id?: string;
    feature_id?: string | number;
    include_tags: boolean;
    allow_downloads: boolean;
}

/** GET /api/sharing/list/ - every tag/collection share owned by the current user. */
export async function listShares(): Promise<ShareRecord[]> {
    const response = await httpClient.get('/api/sharing/list/');
    return response.data.shares || [];
}

/** GET /api/sharing/features/:featureId/ - the single share for a feature, if any. */
export async function getFeatureShare(featureId: string | number): Promise<ShareRecord> {
    const response = await httpClient.get(`/api/sharing/features/${featureId}/`);
    return response.data;
}

/** GET /api/sharing/public/info/:shareId/ - public (unauthenticated) share metadata. */
export async function getPublicShareInfo(shareId: string, signal?: AbortSignal) {
    const response = await httpClient.get(`/api/sharing/public/info/${shareId}/`, { signal });
    return response.data;
}

/** POST /api/sharing/create/ */
export async function createShare(payload: CreateSharePayload): Promise<ShareRecord> {
    const response = await httpClient.post('/api/sharing/create/', payload);
    return response.data;
}

/** DELETE /api/sharing/:shareId/ - handles both tag and collection shares. */
export async function deleteShare(shareId: string): Promise<void> {
    await httpClient.delete(`/api/sharing/${shareId}/`);
}

/** PATCH /api/sharing/features/:featureId/update/ - toggle allow_downloads/include_tags. */
export async function updateFeatureShare(featureId: string | number, fields: Partial<Pick<ShareRecord, 'allow_downloads' | 'include_tags'>>) {
    const response = await httpClient.patch(`/api/sharing/features/${featureId}/update/`, fields);
    return response.data as ShareRecord;
}

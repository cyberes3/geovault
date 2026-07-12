import { AxiosHeaders } from 'axios';
import { httpClient } from '../httpClient';
import { normalizeBboxError, parseBboxAxiosResponse, type BboxResponseData } from '@/utils/format/geobuf';
import type { GeoJsonFeatureCollection } from '@/types/geospatial';

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

export interface PublicShareInfoResponse {
    share_type: ShareType;
    tag?: string | null;
    collection_name?: string | null;
    collection_id?: string | null;
    feature_name?: string | null;
    feature_id?: string | null;
    include_tags?: boolean;
    allow_downloads?: boolean;
}

/** GET /api/sharing/public/info/:shareId/ - public (unauthenticated) share metadata. */
export async function getPublicShareInfo(shareId: string, signal?: AbortSignal): Promise<PublicShareInfoResponse> {
    const response = await httpClient.get<PublicShareInfoResponse>(`/api/sharing/public/info/${shareId}/`, { signal });
    return response.data;
}

async function getShareBboxFeatures(url: string, bboxString: string, zoom: number, signal?: AbortSignal): Promise<BboxResponseData> {
    try {
        const response = await httpClient.get<ArrayBuffer>(`${url}?bbox=${bboxString}&zoom=${Math.round(zoom)}&format=protobuf`, {
            responseType: 'arraybuffer',
            signal,
        });
        const headerRecord = (response.headers as AxiosHeaders).toJSON(true);
        return parseBboxAxiosResponse(headerRecord['content-type'] ?? '', headerRecord, response.data);
    } catch (error) {
        throw normalizeBboxError(error);
    }
}

/** GET /api/sharing/public/:shareId/ - bbox-scoped feature query for a public tag share. */
export async function getPublicShareTagFeatures(shareId: string, bboxString: string, zoom: number, signal?: AbortSignal): Promise<BboxResponseData> {
    return getShareBboxFeatures(`/api/sharing/public/${shareId}/`, bboxString, zoom, signal);
}

/** GET /api/sharing/public/collection/:shareId/ - bbox-scoped feature query for a public collection share. */
export async function getPublicShareCollectionFeatures(shareId: string, bboxString: string, zoom: number, signal?: AbortSignal): Promise<BboxResponseData> {
    return getShareBboxFeatures(`/api/sharing/public/collection/${shareId}/`, bboxString, zoom, signal);
}

/** GET /api/sharing/public/feature/:shareId/ - single shared feature (loaded once, no bbox semantics). */
export async function getPublicShareFeature(shareId: string, signal?: AbortSignal): Promise<{ features: GeoJsonFeatureCollection['features'] }> {
    const response = await httpClient.get(`/api/sharing/public/feature/${shareId}/`, { signal });
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

/** GET /api/sharing/public/feature/:shareId/elevations/internal/ - GPS elevations for a publicly shared feature. */
export async function getPublicFeatureElevations(shareId: string): Promise<{ coordinates?: number[][] } | null> {
    const response = await httpClient.get(`/api/sharing/public/feature/${shareId}/elevations/internal/`);
    return response.data as { coordinates?: number[][] } | null;
}

import { AxiosHeaders } from 'axios';
import { httpClient } from '../httpClient';
import { normalizeBboxError, parseBboxAxiosResponse, type BboxResponseData } from '@/utils/format/geobuf';

export interface FeatureMetadataUpdate {
    feature_id: number | string;
    tags: string[];
    [key: string]: unknown;
}

export interface BboxFeaturesParams {
    /** `minLon,minLat,maxLon,maxLat` */
    bbox: string;
    zoom: number;
    collection?: string | null;
    tags?: string[] | null;
    matchMode?: 'AND' | 'OR';
    signal?: AbortSignal;
}

/** GET /api/features/by-tag/ - all features grouped by tag; accepts optional search params. */
export async function getFeaturesByTag(params?: Record<string, string>): Promise<unknown> {
    const response = await httpClient.get<unknown>('/api/features/by-tag/', { params });
    return response.data;
}

/** GET /api/features/all/ */
export async function getAllFeatures(): Promise<unknown> {
    const response = await httpClient.get<unknown>('/api/features/all/');
    return response.data;
}

/** GET /api/features/user-tags/ */
export async function getUserTags(): Promise<string[]> {
    const response = await httpClient.get<string[]>('/api/features/user-tags/');
    return response.data;
}

/** GET /api/features/filter-by-tags/ */
export async function filterFeaturesByTags(tags: string[], matchMode: 'AND' | 'OR' = 'AND'): Promise<unknown> {
    const params = new URLSearchParams();
    tags.forEach((tag) => { params.append('tags', tag); });
    params.append('match_mode', matchMode);
    const response = await httpClient.get<unknown>(`/api/features/filter-by-tags/?${params.toString()}`);
    return response.data;
}

/** GET /api/feature/:id/ */
export async function getFeature(featureId: string | number): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/feature/${featureId}/`);
    return response.data;
}

/** PUT /api/feature/:id/update-metadata/ */
export async function updateFeatureMetadata(featureId: string | number, data: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.put<unknown>(`/api/feature/${featureId}/update-metadata/`, data);
    return response.data;
}

/** DELETE /api/feature/:id/delete/ */
export async function deleteFeature(featureId: string | number): Promise<void> {
    await httpClient.delete(`/api/feature/${featureId}/delete/`);
}

/** POST /api/features/bulk-update-metadata/ */
export async function bulkUpdateFeatureMetadata(updates: FeatureMetadataUpdate[]): Promise<unknown> {
    const response = await httpClient.post<unknown>('/api/features/bulk-update-metadata/', { updates });
    return response.data;
}

/** POST /api/features/bulk-delete-by-tag/ */
export async function bulkDeleteFeaturesByTag(tag: string): Promise<unknown> {
    const response = await httpClient.post<unknown>('/api/features/bulk-delete-by-tag/', { tag });
    return response.data;
}

/** POST /api/features/bulk-operations/by-tag/:tag/ */
export async function applyBulkOperationsToTag(tag: string, bulkOperations: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.post<unknown>(`/api/features/bulk-operations/by-tag/${encodeURIComponent(tag)}/`, bulkOperations);
    return response.data;
}

/** POST /api/features/quick-point/create/ */
export async function createQuickPointFeature(payload: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.post<unknown>('/api/features/quick-point/create/', payload);
    return response.data;
}

/** GET /api/feature/:id/elevations/(external|internal)/ */
export async function getFeatureElevations(featureId: string | number, source: 'external' | 'internal'): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/feature/${featureId}/elevations/${source}/`);
    return response.data;
}

/** POST /api/feature/:id/apply-replacement/ */
export async function applyFeatureReplacement(featureId: string | number, payload: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.post<unknown>(`/api/feature/${featureId}/apply-replacement/`, payload);
    return response.data;
}

/** GET /api/export-kmz - downloads the KMZ export as a Blob. */
export async function exportFeaturesKmz(all = true): Promise<{ blob: Blob; filename: string | null }> {
    const response = await httpClient.get<Blob>('/api/export-kmz', {
        params: { all },
        responseType: 'blob',
    });
    const contentDisposition = response.headers['content-disposition'] as string | undefined;
    const filenameMatch = contentDisposition ? /filename="?([^"]+)"?/.exec(contentDisposition) : null;
    return {
        blob: response.data,
        filename: filenameMatch ? filenameMatch[1] : null,
    };
}

export interface ExtentHintResponse {
    bbox: [number, number, number, number] | null;
}

/** GET /api/geojson/extent-hint/ - cheap pre-check used before fitting to the user's data extent. */
export async function getExtentHint(): Promise<ExtentHintResponse> {
    const response = await httpClient.get<ExtentHintResponse>('/api/geojson/extent-hint/');
    return response.data;
}

/**
 * GET /api/geojson/ - viewport (bbox) feature query used by the main map. Requests protobuf/geobuf
 * (falls back to JSON if the server ever responds with it) and decodes via `parseBboxAxiosResponse`.
 */
export async function getFeaturesInBbox(params: BboxFeaturesParams): Promise<BboxResponseData> {
    const query: Record<string, string> = {
        bbox: params.bbox,
        zoom: String(Math.round(params.zoom)),
        format: 'protobuf',
    };
    if (params.collection) {
        query.collection = params.collection;
    }

    const searchParams = new URLSearchParams(query);
    if (params.tags && params.tags.length > 0) {
        params.tags.forEach((tag) => { searchParams.append('tags', tag); });
        searchParams.append('match_mode', params.matchMode ?? 'AND');
    }

    try {
        const response = await httpClient.get<ArrayBuffer>(`/api/geojson/?${searchParams.toString()}`, {
            responseType: 'arraybuffer',
            signal: params.signal,
        });
        const headerRecord = (response.headers as AxiosHeaders).toJSON(true);
        return parseBboxAxiosResponse(headerRecord['content-type'] ?? '', headerRecord, response.data);
    } catch (error) {
        throw normalizeBboxError(error);
    }
}

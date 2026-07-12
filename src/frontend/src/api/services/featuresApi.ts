import { httpClient } from '../httpClient';

export interface FeatureMetadataUpdate {
    feature_id: number | string;
    tags: string[];
    [key: string]: unknown;
}

/** GET /api/features/by-tag/ - all features grouped by tag; accepts optional search params. */
export async function getFeaturesByTag(params?: Record<string, string>) {
    const response = await httpClient.get('/api/features/by-tag/', { params });
    return response.data;
}

/** GET /api/features/all/ */
export async function getAllFeatures() {
    const response = await httpClient.get('/api/features/all/');
    return response.data;
}

/** GET /api/features/user-tags/ */
export async function getUserTags() {
    const response = await httpClient.get('/api/features/user-tags/');
    return response.data;
}

/** GET /api/features/filter-by-tags/ */
export async function filterFeaturesByTags(tags: string[], matchMode: 'AND' | 'OR' = 'AND') {
    const params = new URLSearchParams();
    tags.forEach((tag) => params.append('tags', tag));
    params.append('match_mode', matchMode);
    const response = await httpClient.get(`/api/features/filter-by-tags/?${params.toString()}`);
    return response.data;
}

/** GET /api/feature/:id/ */
export async function getFeature(featureId: string | number) {
    const response = await httpClient.get(`/api/feature/${featureId}/`);
    return response.data;
}

/** PUT /api/feature/:id/update-metadata/ */
export async function updateFeatureMetadata(featureId: string | number, data: Record<string, unknown>) {
    const response = await httpClient.put(`/api/feature/${featureId}/update-metadata/`, data);
    return response.data;
}

/** DELETE /api/feature/:id/delete/ */
export async function deleteFeature(featureId: string | number): Promise<void> {
    await httpClient.delete(`/api/feature/${featureId}/delete/`);
}

/** POST /api/features/bulk-update-metadata/ */
export async function bulkUpdateFeatureMetadata(updates: FeatureMetadataUpdate[]) {
    const response = await httpClient.post('/api/features/bulk-update-metadata/', { updates });
    return response.data;
}

/** POST /api/features/bulk-delete-by-tag/ */
export async function bulkDeleteFeaturesByTag(tag: string) {
    const response = await httpClient.post('/api/features/bulk-delete-by-tag/', { tag });
    return response.data;
}

/** POST /api/features/bulk-operations/by-tag/:tag/ */
export async function applyBulkOperationsToTag(tag: string, bulkOperations: Record<string, unknown>) {
    const response = await httpClient.post(`/api/features/bulk-operations/by-tag/${encodeURIComponent(tag)}/`, bulkOperations);
    return response.data;
}

/** POST /api/features/quick-point/create/ */
export async function createQuickPointFeature(payload: Record<string, unknown>) {
    const response = await httpClient.post('/api/features/quick-point/create/', payload);
    return response.data;
}

/** GET /api/feature/:id/elevations/(external|internal)/ */
export async function getFeatureElevations(featureId: string | number, source: 'external' | 'internal') {
    const response = await httpClient.get(`/api/feature/${featureId}/elevations/${source}/`);
    return response.data;
}

/** POST /api/feature/:id/apply-replacement/ */
export async function applyFeatureReplacement(featureId: string | number, payload: Record<string, unknown>) {
    const response = await httpClient.post(`/api/feature/${featureId}/apply-replacement/`, payload);
    return response.data;
}

/** GET /api/export-kmz - downloads the KMZ export as a Blob. */
export async function exportFeaturesKmz(all = true): Promise<{ blob: Blob; filename: string | null }> {
    const response = await httpClient.get('/api/export-kmz', {
        params: { all },
        responseType: 'blob',
    });
    const contentDisposition = response.headers['content-disposition'];
    const filenameMatch = contentDisposition ? contentDisposition.match(/filename="?([^"]+)"?/) : null;
    return {
        blob: response.data,
        filename: filenameMatch ? filenameMatch[1] : null,
    };
}

/** GET /api/geojson/extent-hint/ - cheap pre-check used before fitting to the user's data extent. */
export async function getExtentHint() {
    const response = await httpClient.get('/api/geojson/extent-hint/');
    return response.data;
}

import { httpClient } from '../httpClient';

export interface CollectionPayload {
    name: string;
    description: string | null;
    tags: string[];
    feature_ids: number[];
}

/** GET /api/collections/ */
export async function listCollections() {
    const response = await httpClient.get('/api/collections/');
    return response.data;
}

/** GET /api/collections/:id/ */
export async function getCollection(collectionId: string | number) {
    const response = await httpClient.get(`/api/collections/${collectionId}/`);
    return response.data;
}

/** GET /api/collections/:id/features/ */
export async function getCollectionFeatures(collectionId: string | number) {
    const response = await httpClient.get(`/api/collections/${collectionId}/features/`);
    return response.data;
}

/** POST /api/collections/create/ or PUT /api/collections/:id/update/ */
export async function saveCollection(payload: CollectionPayload, existingCollectionId?: string | number) {
    const response = existingCollectionId
        ? await httpClient.put(`/api/collections/${existingCollectionId}/update/`, payload)
        : await httpClient.post('/api/collections/create/', payload);
    return response.data;
}

/** DELETE /api/collections/:id/delete/ */
export async function deleteCollection(collectionId: string | number): Promise<void> {
    await httpClient.delete(`/api/collections/${collectionId}/delete/`);
}

/** POST /api/collections/:id/bulk-operations/ */
export async function applyBulkOperationsToCollection(collectionId: string | number, bulkOperations: Record<string, unknown>) {
    const response = await httpClient.post(`/api/collections/${collectionId}/bulk-operations/`, bulkOperations);
    return response.data;
}

import { httpClient } from '../httpClient';

export interface CollectionPayload {
    name: string;
    description: string | null;
    tags: string[];
    feature_ids: number[];
}

/** A saved collection, as returned by the collections API. */
export interface Collection {
    id: number;
    name: string;
    description: string | null;
    tags: string[];
    feature_ids: (string | number)[];
    feature_count: number;
}

export interface ListCollectionsResponse {
    collections: Collection[];
}

/** GET /api/collections/ */
export async function listCollections(): Promise<ListCollectionsResponse> {
    const response = await httpClient.get<ListCollectionsResponse>('/api/collections/');
    return response.data;
}

/** GET /api/collections/:id/ */
export async function getCollection(collectionId: string | number): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/collections/${collectionId}/`);
    return response.data;
}

/** GET /api/collections/:id/features/ */
export async function getCollectionFeatures(collectionId: string | number): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/collections/${collectionId}/features/`);
    return response.data;
}

/** POST /api/collections/create/ or PUT /api/collections/:id/update/ */
export async function saveCollection(payload: CollectionPayload, existingCollectionId?: string | number): Promise<unknown> {
    const response = existingCollectionId
        ? await httpClient.put<unknown>(`/api/collections/${existingCollectionId}/update/`, payload)
        : await httpClient.post<unknown>('/api/collections/create/', payload);
    return response.data;
}

/** DELETE /api/collections/:id/delete/ */
export async function deleteCollection(collectionId: string | number): Promise<void> {
    await httpClient.delete(`/api/collections/${collectionId}/delete/`);
}

/** POST /api/collections/:id/bulk-operations/ */
export async function applyBulkOperationsToCollection(collectionId: string | number, bulkOperations: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.post<unknown>(`/api/collections/${collectionId}/bulk-operations/`, bulkOperations);
    return response.data;
}

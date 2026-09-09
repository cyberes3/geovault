import { httpClient } from '../httpClient';
import type { ListPage } from '@/contracts/envelope';

export interface CollectionPayload {
    name: string;
    description: string | null;
    tags: string[];
    feature_ids: number[];
}

/** A saved collection, as returned by the collections API. */
export interface Collection {
    id: string;
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
    const response = await httpClient.get<ListPage<Collection>>('/api/collections/', {
        params: { page_size: 100 },
    });
    return { collections: response.data.items };
}

/** GET /api/collections/:id/ */
export async function getCollection(collectionId: string | number): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/collections/${collectionId}/`);
    return response.data;
}

/** POST /api/collections/ or PATCH /api/collections/:id/ */
export async function saveCollection(payload: CollectionPayload, existingCollectionId?: string | number): Promise<unknown> {
    const response = existingCollectionId
        ? await httpClient.patch<unknown>(`/api/collections/${existingCollectionId}/`, payload)
        : await httpClient.post<unknown>('/api/collections/', payload);
    return response.data;
}

/** DELETE /api/collections/:id/ */
export async function deleteCollection(collectionId: string | number): Promise<void> {
    await httpClient.delete(`/api/collections/${collectionId}/`);
}

/** POST /api/collections/:id/bulk-operations/ */
export async function applyBulkOperationsToCollection(collectionId: string | number, bulkOperations: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.post<unknown>(`/api/collections/${collectionId}/bulk-operations/`, { bulk_operations: bulkOperations });
    return response.data;
}

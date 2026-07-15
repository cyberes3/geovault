import type { AxiosProgressEvent } from 'axios';
import { httpClient } from '../httpClient';

/** GET /api/item/import/get - paginated import queue listing. */
export async function getImportQueue(page = 1, pageSize = 10): Promise<unknown> {
    const response = await httpClient.get<unknown>('/api/item/import/get', { params: { page, page_size: pageSize } });
    return response.data;
}

/** GET /api/item/import/get/:id - a single queued import's items, paginated. */
export async function getImportQueueItem(importId: number | string, page = 1, pageSize = 10): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/item/import/get/${importId}`, { params: { page, page_size: pageSize } });
    return response.data;
}

/** GET /api/item/import/get/features/:importQueueId - all features for a queued import (no pagination). */
export async function getImportQueueFeatures(importQueueId: number | string): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/item/import/get/features/${importQueueId}`);
    return response.data;
}

/** GET /api/item/import/history - paginated history of completed/discarded imports. */
export async function getImportHistory(page = 1, pageSize = 10): Promise<unknown> {
    const response = await httpClient.get<unknown>('/api/item/import/history', { params: { page, 'page-size': pageSize } });
    return response.data;
}

/** GET /api/item/import/logs/:id */
export async function getImportLogs(importId: number | string): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/item/import/logs/${importId}`);
    return response.data;
}

/** GET /api/item/import/status/:jobId - lightweight polling endpoint for an in-flight upload job. */
export async function getImportJobStatus(jobId: string): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/item/import/status/${jobId}`);
    return response.data;
}

/** PUT /api/item/import/skip-state/:id */
export async function updateImportSkipState(importId: number | string, skippedFeatureIds: string[]): Promise<unknown> {
    const response = await httpClient.put<unknown>(`/api/item/import/skip-state/${importId}`, { skipped_feature_ids: skippedFeatureIds });
    return response.data;
}

/** PUT /api/item/import/update/:id - saves edited features for a queued import. */
export async function updateImportFeatures(importId: number | string, features: unknown[]): Promise<unknown> {
    const response = await httpClient.put<unknown>(`/api/item/import/update/${importId}`, { features });
    return response.data;
}

/** POST /api/item/import/perform/:id - commits a queued import into the feature store. */
export async function performImport(importId: number | string, payload: { import_custom_icons?: boolean; skipped_feature_ids?: string[] } = {}): Promise<unknown> {
    const response = await httpClient.post<unknown>(`/api/item/import/perform/${importId}`, payload);
    return response.data;
}

/** POST /api/item/import/recheck-duplicates/:id */
export async function recheckImportDuplicates(importId: number | string): Promise<unknown> {
    const response = await httpClient.post<unknown>(`/api/item/import/recheck-duplicates/${importId}`, {});
    return response.data;
}

/** GET /api/item/import/bulk-operations/:id/get */
export async function getImportBulkOperations(importId: number | string): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/item/import/bulk-operations/${importId}/get`);
    return response.data;
}

/** PUT /api/item/import/bulk-operations/:id */
export async function updateImportBulkOperations(importId: number | string, bulkOperations: Record<string, unknown>): Promise<unknown> {
    const response = await httpClient.put<unknown>(`/api/item/import/bulk-operations/${importId}`, { bulk_operations: bulkOperations });
    return response.data;
}

/** GET /api/item/import/search/:id */
export async function searchImportItems(importId: number | string, query: string): Promise<unknown> {
    const response = await httpClient.get<unknown>(`/api/item/import/search/${importId}`, { params: { query } });
    return response.data;
}

/** DELETE /api/item/import/delete/:id - starts an async delete job and returns its job id. */
export async function deleteImportItem(importId: number | string): Promise<{ msg: string; job_id?: string }> {
    const response = await httpClient.delete<{ msg: string; job_id?: string }>(`/api/item/import/delete/${importId}`);
    return response.data;
}

/**
 * POST /api/item/import/upload - multipart file upload. Returns `{msg, job_id}`; real-time
 * progress after upload is reported over the import WebSocket, not this call.
 */
export async function uploadImportFile(file: File, options: { replacementFeatureId?: string | number; onUploadProgress?: (event: AxiosProgressEvent) => void } = {}): Promise<{ msg: string; job_id?: string }> {
    const formData = new FormData();
    formData.append('file', file);
    if (options.replacementFeatureId != null) {
        formData.append('replacement', String(options.replacementFeatureId));
    }
    const response = await httpClient.post<{ msg: string; job_id?: string }>('/api/item/import/upload', formData, {
        onUploadProgress: options.onUploadProgress,
    });
    return response.data;
}

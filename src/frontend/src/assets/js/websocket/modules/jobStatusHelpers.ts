/**
 * Shared pure helpers for turning a backend job-status string into the partial
 * `ImportTableItem` patch that should be dispatched to `importQueue/updateImportTableItem`.
 * Used by every WebSocket module that reports processing progress for an import queue item
 * (ProcessJobModule, BulkImportJobModule) so the "what does this status mean for the row" logic
 * lives in exactly one place instead of being re-derived per module.
 */

import type { ImportTableItem } from '../../types/import-types';

export type JobStatus = string;

/**
 * Builds the item patch for a status, EXCLUDING the terminal 'completed' case: a completed job
 * needs the server-computed feature count/duplicate status that isn't in the status payload, so
 * callers must request a refresh (or a targeted re-fetch) themselves rather than guess a value here.
 */
export function buildStatusUpdateFields(status: JobStatus): Partial<ImportTableItem> {
    const updates: Partial<ImportTableItem> = {
        processing: status === 'processing',
        processing_failed: status === 'failed',
    };

    if (status === 'processing') {
        // Sentinel the store/UI already understands: "still working, real count not known yet".
        updates.feature_count = -1;
    }

    if (status === 'queued') {
        updates.queued = true;
    } else {
        updates.queued = false;
    }

    return updates;
}

export function isTerminalStatus(status: JobStatus): boolean {
    return status === 'completed' || status === 'failed';
}

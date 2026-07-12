/**
 * Bulk Import Job WebSocket module.
 * Handles bulk-import progress events, started by `importQueue/startBulkImport`.
 */

import { BaseModule } from './BaseModule';

export class BulkImportJobModule extends BaseModule {
    readonly moduleName = 'bulk_import_job';

    initialize(): void {
        super.initialize();

        this.subscribe('job_started', (data: { job_id: string }) => {
            void this.store.dispatch('importQueue/bulkImportJobStarted', data);
        });

        this.subscribe('status_updated', (data: { current_item_id?: number }) => {
            void this.store.dispatch('importQueue/bulkImportStatusUpdated', data);
        });

        this.subscribe('completed', (data: {
            job_id?: string;
            item_ids?: number[];
            failed_count?: number;
            failed_items?: { filename: string; error: string }[];
        }) => {
            void this.store.dispatch('importQueue/bulkImportCompleted', data);
        });

        this.subscribe('failed', (data: { item_ids?: number[]; error_message?: string }) => {
            void this.store.dispatch('importQueue/bulkImportFailed', data);
        });

        this.subscribe('error', (data: { message?: string }) => {
            void this.store.dispatch('importQueue/bulkImportFailed', { error_message: data.message });
        });
    }
}

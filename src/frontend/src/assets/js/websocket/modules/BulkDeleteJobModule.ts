/**
 * Bulk Delete Job WebSocket module.
 * Handles bulk-delete progress events, started by `importQueue/startBulkDelete`.
 */

import { BaseModule } from './BaseModule';

export class BulkDeleteJobModule extends BaseModule {
    readonly moduleName = 'bulk_delete_job';

    initialize(): void {
        super.initialize();

        this.subscribe('job_started', (data: { job_id: string }) => {
            void this.store.dispatch('importQueue/bulkDeleteJobStarted', data);
        });

        this.subscribe('status_updated', (data: { current_item_id?: number }) => {
            void this.store.dispatch('importQueue/bulkDeleteStatusUpdated', data);
        });

        this.subscribe('completed', (data: { job_id?: string; item_ids?: number[] }) => {
            void this.store.dispatch('importQueue/bulkDeleteCompleted', data);
        });

        this.subscribe('failed', (data: { item_ids?: number[]; error_message?: string }) => {
            void this.store.dispatch('importQueue/bulkDeleteFailed', data);
        });

        this.subscribe('error', (data: { message?: string }) => {
            void this.store.dispatch('importQueue/bulkDeleteFailed', { error_message: data.message });
        });
    }
}

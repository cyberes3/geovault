/**
 * Process Job WebSocket module.
 * Handles single-item processing progress events for the import queue table.
 */

import { BaseModule } from './BaseModule';
import { buildStatusUpdateFields, type JobStatus } from './jobStatusHelpers';

export class ProcessJobModule extends BaseModule {
    readonly moduleName = 'process_job';

    initialize(): void {
        super.initialize();

        this.subscribe('status_updated', (data: { import_queue_id: number; status: JobStatus }) => {
            this.handleStatusUpdate(data);
        });

        this.subscribe('completed', () => {
            // The completed feature count/duplicate status is computed server-side and isn't in
            // this event, so a targeted patch isn't possible -- request the queue module's data.
            this.socket?.requestRefresh('import_queue');
        });

        this.subscribe('failed', () => {
            this.socket?.requestRefresh('import_queue');
        });
    }

    private handleStatusUpdate(data: { import_queue_id: number; status: JobStatus }): void {
        if (data.status === 'completed' || data.status === 'failed') {
            this.socket?.requestRefresh('import_queue');
            return;
        }

        void this.store.dispatch('importQueue/updateImportTableItem', {
            id: data.import_queue_id,
            updates: buildStatusUpdateFields(data.status),
        });
    }
}

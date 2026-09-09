/**
 * Process Job WebSocket module.
 * Handles single-item processing progress events for the import queue table.
 */

import { BaseModule } from './BaseModule';
import { buildStatusUpdateFields, isTerminalStatus, type JobStatus } from './jobStatusHelpers';

export class ProcessJobModule extends BaseModule {
    readonly moduleName = 'process_job';

    protected onInitialize(): void {
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
        if (isTerminalStatus(data.status)) {
            this.socket?.requestRefresh('import_queue');
            return;
        }

        void this.store.dispatch('importQueue/updateImportTableItem', {
            id: data.import_queue_id,
            updates: buildStatusUpdateFields(data.status),
        });
    }
}

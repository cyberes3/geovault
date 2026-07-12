/**
 * Delete Job WebSocket module.
 * Handles single-item delete job progress events for the import queue table.
 */

import { BaseModule } from './BaseModule';

export class DeleteJobModule extends BaseModule {
    readonly moduleName = 'delete_job';

    initialize(): void {
        super.initialize();

        this.subscribe('started', (data: { item_id: number }) => {
            void this.store.dispatch('importQueue/updateImportTableItem', {
                id: data.item_id,
                updates: { deleting: true },
            });
        });

        this.subscribe('status_updated', (data: { item_id: number; progress?: number }) => {
            void this.store.dispatch('importQueue/updateImportTableItem', {
                id: data.item_id,
                updates: { deleting: true, deleteProgress: data.progress },
            });
        });

        this.subscribe('completed', (data: { item_id: number }) => {
            void this.store.dispatch('importQueue/removeImportTableItem', data.item_id);
        });

        this.subscribe('failed', (data: { item_id: number; error?: string }) => {
            void this.store.dispatch('importQueue/updateImportTableItem', {
                id: data.item_id,
                updates: { deleting: false, deleteError: data.error },
            });
        });
    }
}

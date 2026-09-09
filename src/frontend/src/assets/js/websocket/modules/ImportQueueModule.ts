/**
 * Import Queue WebSocket module.
 * Handles all import queue table related realtime events.
 */

import { BaseModule } from './BaseModule';
import type { ImportTableItem } from '../../types/import-types';
import { buildStatusUpdateFields, type JobStatus } from './jobStatusHelpers';

export interface ImportQueueStatusCounts {
    feature_count: number;
}

export interface ImportQueueStatusDelta {
    item_id: number;
    status?: JobStatus | null;
    counts?: ImportQueueStatusCounts;
}

export class ImportQueueModule extends BaseModule {
    readonly moduleName = 'import_queue';

    protected onInitialize(): void {
        this.subscribe('initial_state', (data: ImportTableItem[]) => {
            void this.store.dispatch('importQueue/setImportTable', data);
        });

        this.subscribe('item_added', () => {
            // The server doesn't include the new item's computed fields (feature_count,
            // duplicate status) in this event, so a refresh is required to render it correctly.
            this.requestRefresh();
        });

        this.subscribe('item_deleted', (data: { id: number }) => {
            void this.store.dispatch('importQueue/removeImportTableItem', data.id);
        });

        this.subscribe('items_deleted', (data: { ids: number[] }) => {
            void this.store.dispatch('importQueue/removeImportTableItems', data.ids);
        });

        this.subscribe('item_imported', (data: { id: number }) => {
            void this.store.dispatch('importQueue/updateImportTableItem', {
                id: data.id,
                updates: { imported: true },
            });
        });

        this.subscribe('status_updated', (data: ImportQueueStatusDelta) => {
            const updates: Partial<ImportTableItem> = data.status
                ? buildStatusUpdateFields(data.status)
                : {};
            if (data.counts?.feature_count != null) {
                updates.feature_count = data.counts.feature_count;
            }
            void this.store.dispatch('importQueue/updateImportTableItem', {
                id: data.item_id,
                updates,
            });
        });
    }
}

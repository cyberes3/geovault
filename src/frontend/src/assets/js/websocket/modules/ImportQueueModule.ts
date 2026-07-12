/**
 * Import Queue WebSocket module.
 * Handles all import queue table related realtime events.
 */

import { BaseModule } from './BaseModule';
import type { ImportTableItem } from '../../types/import-types';

export class ImportQueueModule extends BaseModule {
    readonly moduleName = 'import_queue';

    initialize(): void {
        super.initialize();

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

        // Note: the backend's `import_queue` module never sends a `status_updated` message to
        // the client -- its server-side handler always responds with a fresh `initial_state`
        // instead (see `ImportQueueModule.status_updated` in `geo_lib/websocket/modules`), since
        // a status change can affect other queued items' duplicate status too. `ProcessJobModule`
        // handles the single-item `status_updated` message that the process-job channel sends.
    }
}

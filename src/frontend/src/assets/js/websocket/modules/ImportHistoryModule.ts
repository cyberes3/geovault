/**
 * Import History WebSocket module.
 * Handles all import history table related realtime events.
 */

import { BaseModule } from './BaseModule';
import type { BackendImportHistoryPagination, ImportHistoryItem } from '../../store/modules/importQueue';

export class ImportHistoryModule extends BaseModule {
    readonly moduleName = 'import_history';

    initialize(): void {
        super.initialize();

        this.subscribe('initial_state', (data: { items: ImportHistoryItem[]; pagination: BackendImportHistoryPagination }) => {
            void this.store.dispatch('importQueue/setImportHistory', data);
            void this.store.dispatch('importQueue/setImportHistoryLoaded', true);
        });

        this.subscribe('item_added', (data: ImportHistoryItem & { page?: number }) => {
            // Always update the store regardless of current page; when the user navigates to a
            // page we fetch fresh data via REST anyway, so this only needs to be "close enough".
            const page = data.page ?? 1;
            void this.store.dispatch('importQueue/addImportHistoryItem', { item: data, page });
        });
    }
}

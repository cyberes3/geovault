/**
 * Import History WebSocket module.
 * Handles all import history table related realtime events.
 */

import {BaseModule} from './BaseModule.js';

export class ImportHistoryModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'import_history';
    }

    /**
     * Initialize the import history module
     */
    initialize() {
        super.initialize();

        // Handle initial state
        this.subscribe('initial_state', (data) => {
            // Data structure: {items: [...], pagination: {...}}
            this.store.dispatch('websocket/setModuleData', {module: 'importHistory', data});
            // Update import history with paginated data
            this.store.dispatch('importQueue/setImportHistory', data);
            // Mark as initially loaded
            this.store.dispatch('importQueue/setImportHistoryLoaded', true);
        });

        // Handle new item added to history
        this.subscribe('item_added', (data) => {
            // Data structure: {id, original_filename, timestamp, page}
            // Always update the store regardless of current page
            // When user navigates to a page, we'll fetch fresh data via REST API anyway
            const itemPage = data.page !== undefined ? data.page : 1;
            
            // Add item with page information
            this.store.dispatch('importQueue/addImportHistoryItem', {
                item: data,
                page: itemPage
            });
        });
    }

    /**
     * Cleanup the import history module
     */
    cleanup() {
        super.cleanup();
    }
}

/**
 * Import Table WebSocket module.
 * Handles all import table related realtime events.
 */

import {BaseModule} from './BaseModule.js';

export class ImportTableModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'import_queue';
    }

    /**
     * Initialize the import table module
     */
    initialize() {
        super.initialize();

        // Handle initial state
        this.subscribe('initial_state', (data) => {
            this.store.dispatch('setRealtimeModuleData', {module: 'importTable', data});
            // Also update the importTable state
            this.store.commit('setImportTable', data);
        });

        // Handle new item added
        this.subscribe('item_added', (data) => {
            // Request refresh to get updated data
            this.requestRefresh();
        });

        // Handle item deleted
        this.subscribe('item_deleted', (data) => {
            this.store.dispatch('removeImportTableItem', data.id);
        });

        // Handle items deleted (bulk)
        this.subscribe('items_deleted', (data) => {
            this.store.dispatch('removeImportTableItems', data.ids);
        });

        // Handle item imported
        this.subscribe('item_imported', (data) => {
            this.store.dispatch('updateImportTableItem', {
                id: data.id,
                updates: {imported: true}
            });
        });

        // Handle status updates (processing -> completed)
        this.subscribe('status_updated', (data) => {
            let updates = {
                processing: data.status === 'processing',
                processing_failed: data.status === 'failed'
            };

            // Handle completed status - need to get the actual feature count from the server
            if (data.status === 'completed') {
                updates.processing = false;
                updates.processing_failed = false;
                // Request a refresh of import table to get the updated item with correct feature count
                this.requestRefresh();
                return;
            }

            // For processing status, set feature_count to -1 to indicate processing
            if (data.status === 'processing') {
                updates.feature_count = -1;
            }

            // Update the specific item in the store using id
            this.store.dispatch('updateImportTableItem', {
                id: data.id,
                updates: updates
            });
        });
    }

    /**
     * Cleanup the import table module
     */
    cleanup() {
        super.cleanup();
    }
}

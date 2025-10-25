/**
 * Import Queue WebSocket module.
 * Handles all import queue table related realtime events.
 */

import {BaseModule} from './BaseModule.js';

export class ImportQueueModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'import_queue';
    }

    /**
     * Initialize the import queue module
     */
    initialize() {
        super.initialize();

        // Handle initial state
        this.subscribe('initial_state', (data) => {
            console.log('Received import queue initial state:', data);
            this.store.dispatch('setRealtimeModuleData', {module: 'importQueue', data});
            // Also update the legacy importQueue state for backward compatibility
            this.store.commit('setImportQueue', data);
        });

        // Handle new item added
        this.subscribe('item_added', (data) => {
            console.log('Import queue item added:', data);
            // Request refresh to get updated data
            this.requestRefresh();
        });

        // Handle item deleted
        this.subscribe('item_deleted', (data) => {
            console.log('Import queue item deleted:', data);
            this.store.dispatch('removeImportQueueItem', data.id.toString());
        });

        // Handle items deleted (bulk)
        this.subscribe('items_deleted', (data) => {
            console.log('Import queue items deleted:', data);
            this.store.dispatch('removeImportQueueItems', data.ids.map(id => id.toString()));
        });

        // Handle item imported
        this.subscribe('item_imported', (data) => {
            console.log('Import queue item imported:', data);
            this.store.dispatch('updateImportQueueItem', {
                id: data.id.toString(),
                updates: {imported: true}
            });
        });
    }

    /**
     * Cleanup the import queue module
     */
    cleanup() {
        super.cleanup();
        console.log('Import queue module cleaned up');
    }
}
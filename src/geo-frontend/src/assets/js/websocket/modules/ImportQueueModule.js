/**
 * Import Queue WebSocket module.
 * Handles all import queue related realtime events.
 */

import { BaseModule } from './BaseModule.js';

export class ImportQueueModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'import_job';
    }

    /**
     * Initialize the import queue module
     */
    initialize() {
        super.initialize();

        // Handle initial state
        this.subscribe('initial_state', (data) => {
            console.log('Received import queue initial state:', data);
            this.store.dispatch('setRealtimeModuleData', { module: 'importQueue', data });
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

        // Handle status updates
        this.subscribe('status_updated', (data) => {
            console.log('Import queue status updated:', data);
            this.handleStatusUpdate(data);
        });

        // Handle item imported
        this.subscribe('item_imported', (data) => {
            console.log('Import queue item imported:', data);
            this.store.dispatch('updateImportQueueItem', {
                id: data.id.toString(),
                updates: { imported: true }
            });
        });

        // Handle import job completion
        this.subscribe('completed', (data) => {
            console.log('Import job completed:', data);
            // Request refresh to get updated data
            this.requestRefresh();
        });

        // Handle import job failure
        this.subscribe('failed', (data) => {
            console.log('Import job failed:', data);
            // Request refresh to get updated data
            this.requestRefresh();
        });
    }

    /**
     * Handle status update events
     * @param {Object} data - The status update data
     */
    handleStatusUpdate(data) {
        let updates = {
            processing: data.status === 'processing',
            processing_failed: data.status === 'failed'
        };

        // Handle completed status - need to get the actual feature count from the server
        if (data.status === 'completed') {
            updates.processing = false;
            updates.processing_failed = false;
            // Request a refresh to get the updated item with correct feature count
            this.requestRefresh();
            return;
        }

        // For processing status, set feature_count to -1 to indicate processing
        if (data.status === 'processing') {
            updates.feature_count = -1;
        }

        // Update the specific item in the store
        this.store.dispatch('updateImportQueueItem', {
            id: data.import_queue_id.toString(),
            updates: updates
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

/**
 * Process Job WebSocket module.
 * Handles all process job related realtime events for the import table.
 */

import {BaseModule} from './BaseModule.js';

export class ProcessJobModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'process_job';
    }

    /**
     * Initialize the process job module
     */
    initialize() {
        super.initialize();

        // Handle status updates
        this.subscribe('status_updated', (data) => {
            this.handleStatusUpdate(data);
        });

        // Handle process job completion
        this.subscribe('completed', (data) => {
            // Request refresh of import table to get updated data
            this.socket.requestRefresh('import_queue');
        });

        // Handle process job failure
        this.subscribe('failed', (data) => {
            // Request refresh of import table to get updated data
            this.socket.requestRefresh('import_queue');
        });
    }

    /**
     * Handle status update events
     * @param {Object} data - The status update data
     */
    handleStatusUpdate(data) {
        let updates = {
            processing: data.status === 'processing',
            processing_failed: data.status === 'failed',
            queued: data.status === 'queued'
        };

        // Handle completed status - need to get the actual feature count from the server
        if (data.status === 'completed') {
            updates.processing = false;
            updates.processing_failed = false;
            updates.queued = false;
            // Request a refresh of import table to get the updated item with correct feature count
            this.socket.requestRefresh('import_queue');
            return;
        }

        // For processing status, set feature_count to -1 to indicate processing
        if (data.status === 'processing') {
            updates.feature_count = -1;
            updates.queued = false;
        }

        // For queued status, clear processing flag
        if (data.status === 'queued') {
            updates.processing = false;
        }

        // Update the specific item in the store using import_queue_id
        this.store.dispatch('updateImportTableItem', {
            id: data.import_queue_id,
            updates: updates
        });
    }

    /**
     * Cleanup the process job module
     */
    cleanup() {
        super.cleanup();
    }
}

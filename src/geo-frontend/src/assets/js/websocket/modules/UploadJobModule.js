/**
 * Upload Job WebSocket module.
 * Handles all upload job related realtime events for the import queue.
 */

import {BaseModule} from './BaseModule.js';

export class UploadJobModule extends BaseModule {
    constructor(store) {
        super(store);
        this.moduleName = 'upload_job';
    }

    /**
     * Initialize the upload job module
     */
    initialize() {
        super.initialize();

        // Handle status updates
        this.subscribe('status_updated', (data) => {
            console.log('Upload job status updated:', data);
            this.handleStatusUpdate(data);
        });

        // Handle upload job completion
        this.subscribe('completed', (data) => {
            console.log('Upload job completed:', data);
            // Request refresh of import queue to get updated data
            this.socket.requestRefresh('import_queue');
        });

        // Handle upload job failure
        this.subscribe('failed', (data) => {
            console.log('Upload job failed:', data);
            // Request refresh of import queue to get updated data
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
            processing_failed: data.status === 'failed'
        };

        // Handle completed status - need to get the actual feature count from the server
        if (data.status === 'completed') {
            updates.processing = false;
            updates.processing_failed = false;
            // Request a refresh of import queue to get the updated item with correct feature count
            this.socket.requestRefresh('import_queue');
            return;
        }

        // For processing status, set feature_count to -1 to indicate processing
        if (data.status === 'processing') {
            updates.feature_count = -1;
        }

        // Update the specific item in the store using import_queue_id
        this.store.dispatch('updateImportQueueItem', {
            id: data.import_queue_id,
            updates: updates
        });
    }

    /**
     * Cleanup the upload job module
     */
    cleanup() {
        super.cleanup();
        console.log('Upload job module cleaned up');
    }
}

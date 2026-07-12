import { bulkUpdateHiddenFeatures } from '@/api/services/userApi';

/**
 * Manages debounced bulk updates for hidden features.
 */
class HiddenFeaturesManager {
    private pendingAdd = new Set<string>();
    private pendingRemove = new Set<string>();
    private debounceTimer: ReturnType<typeof setTimeout> | null = null;
    private readonly debounceDelay = 500;
    private isProcessing = false;

    /**
     * Add a feature ID to be hidden (optimistic).
     */
    addHidden(featureId: string | number, optimisticCallback?: () => void): void {
        const id = String(featureId);
        this.pendingRemove.delete(id);
        this.pendingAdd.add(id);
        optimisticCallback?.();
        this.scheduleBulkUpdate();
    }

    /**
     * Remove a feature ID from hidden (optimistic).
     */
    removeHidden(featureId: string | number, optimisticCallback?: () => void): void {
        const id = String(featureId);
        this.pendingAdd.delete(id);
        this.pendingRemove.add(id);
        optimisticCallback?.();
        this.scheduleBulkUpdate();
    }

    private scheduleBulkUpdate(): void {
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
        }
        this.debounceTimer = setTimeout(() => {
            this.flushPendingUpdates();
        }, this.debounceDelay);
    }

    /**
     * Immediately flush all pending updates to the server.
     */
    async flushPendingUpdates(): Promise<void> {
        if (this.isProcessing || (this.pendingAdd.size === 0 && this.pendingRemove.size === 0)) {
            return;
        }

        const addIds = Array.from(this.pendingAdd);
        const removeIds = Array.from(this.pendingRemove);
        this.pendingAdd.clear();
        this.pendingRemove.clear();

        this.isProcessing = true;

        try {
            await bulkUpdateHiddenFeatures(addIds, removeIds);
        } catch (error) {
            console.error('Error in bulk hidden features update:', error);
            addIds.forEach((id) => this.pendingAdd.add(id));
            removeIds.forEach((id) => this.pendingRemove.add(id));
            this.scheduleBulkUpdate();
            throw error;
        } finally {
            this.isProcessing = false;
        }
    }

    /**
     * Force immediate flush of all pending updates.
     */
    async forceFlush(): Promise<void> {
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
            this.debounceTimer = null;
        }
        return this.flushPendingUpdates();
    }

    /**
     * Check if there are pending updates.
     */
    hasPending(): boolean {
        return this.pendingAdd.size > 0 || this.pendingRemove.size > 0;
    }
}

const hiddenFeaturesManager = new HiddenFeaturesManager();

export default hiddenFeaturesManager;

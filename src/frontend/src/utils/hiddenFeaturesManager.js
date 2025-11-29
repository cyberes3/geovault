import axios from 'axios'
import { getCookie } from '@/assets/js/auth.js'

/**
 * Manages debounced bulk updates for hidden features
 */
class HiddenFeaturesManager {
  constructor() {
    this.pendingAdd = new Set()
    this.pendingRemove = new Set()
    this.debounceTimer = null
    this.debounceDelay = 500 // ms
    this.isProcessing = false
  }

  /**
   * Add a feature ID to be hidden (optimistic)
   * @param {string|number} featureId
   * @param {Function} optimisticCallback - Called immediately for UI update
   */
  addHidden(featureId, optimisticCallback) {
    const id = String(featureId)
    
    // Remove from pending removes if it was there
    this.pendingRemove.delete(id)
    
    // Add to pending adds
    this.pendingAdd.add(id)
    
    // Call optimistic update immediately
    if (optimisticCallback) {
      optimisticCallback()
    }
    
    // Schedule the debounced network call
    this.scheduleBulkUpdate()
  }

  /**
   * Remove a feature ID from hidden (optimistic)
   * @param {string|number} featureId
   * @param {Function} optimisticCallback - Called immediately for UI update
   */
  removeHidden(featureId, optimisticCallback) {
    const id = String(featureId)
    
    // Remove from pending adds if it was there
    this.pendingAdd.delete(id)
    
    // Add to pending removes
    this.pendingRemove.add(id)
    
    // Call optimistic update immediately
    if (optimisticCallback) {
      optimisticCallback()
    }
    
    // Schedule the debounced network call
    this.scheduleBulkUpdate()
  }

  /**
   * Schedule a debounced bulk update
   */
  scheduleBulkUpdate() {
    // Clear any existing timer
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
    }
    
    // Set new timer
    this.debounceTimer = setTimeout(() => {
      this.flushPendingUpdates()
    }, this.debounceDelay)
  }

  /**
   * Immediately flush all pending updates to the server
   */
  async flushPendingUpdates() {
    // If already processing or nothing to do, return
    if (this.isProcessing || (this.pendingAdd.size === 0 && this.pendingRemove.size === 0)) {
      return
    }

    // Copy pending sets and clear them
    const addIds = Array.from(this.pendingAdd)
    const removeIds = Array.from(this.pendingRemove)
    this.pendingAdd.clear()
    this.pendingRemove.clear()

    this.isProcessing = true

    try {
      const response = await axios.post(
        '/api/user/settings/hidden-features/bulk/',
        {
          add: addIds,
          remove: removeIds
        },
        {
          headers: {
            'X-CSRFToken': getCookie('csrftoken'),
            'Content-Type': 'application/json',
          },
        }
      )

      // Success is indicated purely by a 2xx status; the frontend keeps a local cache.
      if (response.status >= 200 && response.status < 300) {
        return
      }
      throw new Error(response.data?.error || 'Failed to update hidden features.')
    } catch (error) {
      console.error('Error in bulk hidden features update:', error)
      // Re-add failed operations back to pending
      addIds.forEach(id => this.pendingAdd.add(id))
      removeIds.forEach(id => this.pendingRemove.add(id))
      // Retry after a delay
      this.scheduleBulkUpdate()
      throw error
    } finally {
      this.isProcessing = false
    }
  }

  /**
   * Force immediate flush of all pending updates
   * @returns {Promise}
   */
  async forceFlush() {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = null
    }
    return this.flushPendingUpdates()
  }

  /**
   * Check if there are pending updates
   * @returns {boolean}
   */
  hasPending() {
    return this.pendingAdd.size > 0 || this.pendingRemove.size > 0
  }
}

// Create a singleton instance
const hiddenFeaturesManager = new HiddenFeaturesManager()

export default hiddenFeaturesManager


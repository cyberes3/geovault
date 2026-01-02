/**
 * Universal Toast Notification System
 * 
 * Usage:
 *   import { toast } from '@/utils/toast'
 *   toast.success('Operation completed')
 *   toast.error('Something went wrong')
 *   toast.info('Information message')
 *   toast.warning('Warning message')
 */

class ToastManager {
  constructor() {
    this.listeners = []
    this.nextId = 0
  }

  /**
   * Subscribe to toast events
   * @param {Function} callback - Function to call when toast is shown
   * @returns {Function} Unsubscribe function
   */
  subscribe(callback) {
    this.listeners.push(callback)
    return () => {
      const index = this.listeners.indexOf(callback)
      if (index > -1) {
        this.listeners.splice(index, 1)
      }
    }
  }

  /**
   * Show a toast notification
   * @param {string} message - Toast message
   * @param {string} type - Toast type: 'success', 'error', 'warning', 'info'
   * @param {Object} options - Additional options
   * @returns {number} Toast ID
   */
  show(message, type = 'info', options = {}) {
    const toast = {
      id: this.nextId++,
      message,
      type,
      duration: options.duration ?? (type === 'error' ? null : 3000),
      dismissible: options.dismissible ?? (type === 'error'),
      ...options
    }

    this.listeners.forEach(callback => callback(toast))
    return toast.id
  }

  /**
   * Show success toast
   */
  success(message, options = {}) {
    return this.show(message, 'success', { duration: 3000, dismissible: false, ...options })
  }

  /**
   * Show error toast
   */
  error(message, options = {}) {
    return this.show(message, 'error', { duration: null, dismissible: true, ...options })
  }

  /**
   * Show warning toast
   */
  warning(message, options = {}) {
    return this.show(message, 'warning', { duration: 4000, dismissible: true, ...options })
  }

  /**
   * Show info toast
   */
  info(message, options = {}) {
    return this.show(message, 'info', { duration: 3000, dismissible: false, ...options })
  }
}

// Create singleton instance
const toast = new ToastManager()

export { toast, ToastManager }


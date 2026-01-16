/**
 * Universal Toast Notification System
 * 
 * Usage:
 *   import { toast } from '@/utils/toast'
 *   toast.success('Operation completed')
 *   toast.error('Something went wrong')
 *   toast.info('Information message')
 *   toast.warning('Warning message')
 * 
 * Custom options:
 *   toast.show('Message', 'info', { 
 *     plain: true,              // Use plain white styling
 *     icon: ClipboardDocumentIcon, // Custom icon component
 *     duration: 5000,           // Auto-dismiss duration (ms)
 *     dismissible: true,        // Show close button
 *     html: '<b>HTML</b>'       // HTML content instead of message
 *   })
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
   * @param {number|null} options.duration - Auto-dismiss duration in milliseconds (null for no auto-dismiss)
   * @param {boolean} options.dismissible - Whether to show a close button
   * @param {string} options.html - HTML content to display instead of message
   * @param {boolean} options.plain - Use plain white styling instead of type-based colors
   * @param {Component} options.icon - Custom icon component to display (e.g., ClipboardDocumentIcon)
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
   * @param {string} message - Toast message
   * @param {Object} options - Additional options (see show() method for available options)
   * @returns {number} Toast ID
   */
  success(message, options = {}) {
    return this.show(message, 'success', { duration: 3000, dismissible: false, ...options })
  }

  /**
   * Show error toast
   * @param {string} message - Toast message
   * @param {Object} options - Additional options (see show() method for available options)
   * @returns {number} Toast ID
   */
  error(message, options = {}) {
    return this.show(message, 'error', { duration: null, dismissible: true, ...options })
  }

  /**
   * Show warning toast
   * @param {string} message - Toast message
   * @param {Object} options - Additional options (see show() method for available options)
   * @returns {number} Toast ID
   */
  warning(message, options = {}) {
    return this.show(message, 'warning', { duration: 4000, dismissible: true, ...options })
  }

  /**
   * Show info toast
   * @param {string} message - Toast message
   * @param {Object} options - Additional options (see show() method for available options)
   * @returns {number} Toast ID
   */
  info(message, options = {}) {
    return this.show(message, 'info', { duration: 3000, dismissible: false, ...options })
  }
}

// Create singleton instance
const toast = new ToastManager()

export { toast, ToastManager }


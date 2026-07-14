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
 *     html: '<b>HTML</b>',      // HTML content instead of message
 *     replaceKey: 'my-slot'     // Replace any existing toast with this key (one visible per key)
 *   })
 */
import type { Component } from 'vue'

export type ToastType = 'success' | 'error' | 'warning' | 'info'

export interface ToastOptions {
  /** Auto-dismiss duration in milliseconds (null for no auto-dismiss). */
  duration?: number | null
  /** Whether to show a close button. */
  dismissible?: boolean
  /** HTML content to display instead of message. */
  html?: string
  /** Use plain white styling instead of type-based colors. */
  plain?: boolean
  /** Custom icon component to display (e.g., ClipboardDocumentIcon). */
  icon?: Component | null
  /** If set, removes any existing toast with the same key before showing (one visible toast per key). */
  replaceKey?: string
  [key: string]: unknown
}

export interface ToastPayload extends ToastOptions {
  id: number
  message: string
  type: ToastType
}

export type ToastEvent = ToastPayload | { action: 'clearAll' }

type ToastListener = (event: ToastEvent) => void

class ToastManager {
  private listeners: ToastListener[] = []
  private nextId = 0

  /**
   * Subscribe to toast events
   * @returns Unsubscribe function
   */
  subscribe(callback: ToastListener): () => void {
    this.listeners.push(callback)
    return () => {
      const index = this.listeners.indexOf(callback)
      if (index > -1) {
        this.listeners.splice(index, 1)
      }
    }
  }

  /** Show a toast notification. */
  show(message: string, type: ToastType = 'info', options: ToastOptions = {}): number {
    const toast: ToastPayload = {
      id: this.nextId++,
      message,
      type,
      duration: options.duration ?? (type === 'error' ? null : 3000),
      dismissible: options.dismissible ?? (type === 'error'),
      ...options
    }

    this.listeners.forEach(callback => { callback(toast) })
    return toast.id
  }

  /** Show success toast. */
  success(message: string, options: ToastOptions = {}): number {
    return this.show(message, 'success', { duration: 3000, dismissible: false, ...options })
  }

  /** Show error toast. */
  error(message: string, options: ToastOptions = {}): number {
    return this.show(message, 'error', { duration: null, dismissible: true, ...options })
  }

  /** Show warning toast. */
  warning(message: string, options: ToastOptions = {}): number {
    return this.show(message, 'warning', { duration: 4000, dismissible: true, ...options })
  }

  /** Show info toast. */
  info(message: string, options: ToastOptions = {}): number {
    return this.show(message, 'info', { duration: 3000, dismissible: false, ...options })
  }

  /** Clear all active toasts. */
  clearAll(): void {
    this.listeners.forEach(callback => { callback({ action: 'clearAll' }) })
  }
}

// Create singleton instance
const toast = new ToastManager()

export { toast, ToastManager }

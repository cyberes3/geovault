/**
 * Base module class for WebSocket realtime functionality.
 * All WebSocket modules should extend this class.
 */

export class BaseModule {
    constructor(store) {
        this.store = store;
        this.moduleName = null; // Must be overridden by subclasses
        this.socket = null; // Will be set by the socket service
    }

    /**
     * Initialize module (called when socket connects)
     * Override this method in subclasses to set up event handlers
     */
    initialize() {
        console.log(`Initializing module: ${this.moduleName}`);
        // Override in subclasses
    }

    /**
     * Cleanup module (called when socket disconnects)
     * Override this method in subclasses for custom cleanup
     */
    cleanup() {
        console.log(`Cleaning up module: ${this.moduleName}`);
        // Override in subclasses if needed
    }

    /**
     * Subscribe to a WebSocket event
     * @param {string} event - The event name to subscribe to
     * @param {Function} handler - The event handler function
     */
    subscribe(event, handler) {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.subscribe(this.moduleName, event, handler);
    }

    /**
     * Send a message to the server
     * @param {string} type - The message type
     * @param {Object} data - The message data
     */
    send(type, data = {}) {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.send(this.moduleName, type, data);
    }

    /**
     * Request a refresh of module data
     */
    requestRefresh() {
        if (!this.socket) {
            throw new Error('Socket not available - module not properly initialized');
        }
        this.socket.requestRefresh(this.moduleName);
    }
}

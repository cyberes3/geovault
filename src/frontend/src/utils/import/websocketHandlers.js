/**
 * WebSocket connection and message handling utilities for import processing
 */

/**
 * Create WebSocket connection URL
 * @param {string} currentId - Upload item ID
 * @returns {string} WebSocket URL
 */
export function createWebSocketUrl(currentId) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/upload/status/${currentId}/`;
}

/**
 * Connect to WebSocket with callbacks
 * @param {string} currentId - Upload item ID
 * @param {Object} callbacks - Event callbacks
 * @param {Function} callbacks.onOpen - WebSocket open handler
 * @param {Function} callbacks.onMessage - WebSocket message handler
 * @param {Function} callbacks.onClose - WebSocket close handler
 * @param {Function} callbacks.onError - WebSocket error handler
 * @returns {WebSocket} WebSocket instance
 */
export function connectWebSocket(currentId, callbacks) {
  if (!currentId) {
    console.warn('Cannot connect WebSocket: currentId is null');
    return null;
  }

  const wsUrl = createWebSocketUrl(currentId);
  const ws = new WebSocket(wsUrl);
  
  ws.onopen = callbacks.onOpen;
  ws.onmessage = callbacks.onMessage;
  ws.onclose = callbacks.onClose;
  ws.onerror = callbacks.onError;
  
  return ws;
}

/**
 * Send message through WebSocket
 * @param {WebSocket} ws - WebSocket instance
 * @param {boolean} wsConnected - Connection state
 * @param {string} type - Message type
 * @param {Object} data - Message data
 */
export function sendWebSocketMessage(ws, wsConnected, type, data) {
  if (ws && wsConnected) {
    ws.send(JSON.stringify({ type, data }));
  }
}

/**
 * Parse WebSocket message
 * @param {MessageEvent} event - WebSocket message event
 * @returns {Object} Parsed message
 */
export function parseWebSocketMessage(event) {
  return JSON.parse(event.data);
}

/**
 * Determine if WebSocket should reconnect after close
 * @param {CloseEvent} event - WebSocket close event
 * @param {string} currentId - Upload item ID
 * @param {number} wsReconnectAttempts - Current reconnect attempts
 * @param {number} maxReconnectAttempts - Maximum reconnect attempts
 * @returns {boolean} True if should reconnect
 */
export function shouldReconnect(event, currentId, wsReconnectAttempts, maxReconnectAttempts) {
  // Handle 404 - item not found (don't reconnect)
  if (event.code === 4004) {
    return false;
  }

  // Don't attempt reconnect if currentId is null (component being destroyed/navigating away)
  if (!currentId) {
    return false;
  }

  // Attempt to reconnect if not a normal closure and we haven't exceeded max attempts
  return event.code !== 1000 && wsReconnectAttempts < maxReconnectAttempts;
}

/**
 * Get reconnect delay in milliseconds
 * @returns {number} Delay in milliseconds
 */
export function getReconnectDelay() {
  return 2000; // 2 seconds
}


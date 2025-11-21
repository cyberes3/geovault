// API host is dynamically determined from current window location
// This allows the app to work on any domain/port without hardcoding
export const APIHOST = window.location.origin

// WebSocket configuration
export const WEBSOCKET_CONFIG = {
  // WebSocket URL will be automatically determined based on current protocol
  // ws:// for development, wss:// for production
  HOST: window.location.host,
  PROTOCOL: window.location.protocol === 'https:' ? 'wss:' : 'ws:',
}

// Security configuration
export const SECURITY_CONFIG = {
  // Set to false to disable frontend file validation (for testing backend validation)
  ENABLE_FRONTEND_VALIDATION: false,
}

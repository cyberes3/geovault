export const APIHOST = "http://127.0.0.1:8000"

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

/**
 * WebSocket client for trackers-live endpoint (ws/extensions/live-track/trackers-live/).
 * Used by LiveTrackView for live track_updated events only. Same-origin; cookie/session auth.
 */

import { WebSocketHeartbeat } from 'platform/assets/js/websocket/WebSocketHeartbeat.js';

class TrackersLiveSocket {
  constructor() {
    this.socket = null;
    this.handlers = new Map(); // type -> Set(handler)
    this.reconnectAttempts = 0;
    this.reconnectBaseDelayMs = 2000;
    this.reconnectMaxDelayMs = 30000;
    this.reconnectTimeoutId = null;
    this.shouldConnect = false;
    this.onReconnect = null;
    this.heartbeat = new WebSocketHeartbeat({
      sendPing: () => {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify({ module: 'live_track', type: 'ping' }));
        }
      },
      onTimeout: () => {
        console.warn('TrackersLiveSocket ping timeout, forcing reconnect');
        if (this.socket) {
          this.socket.close(1006, 'Ping timeout'); // Triggers onclose and reconnection
        }
      }
    });
  }

  getWsUrl() {
    if (typeof window === 'undefined') return '';
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    return `${protocol}//${host}/ws/extensions/live-track/trackers-live/`;
  }

  connect() {
    this.shouldConnect = true;
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const url = this.getWsUrl();
    if (!url) return;
    try {
      this.socket = new WebSocket(url);
      this.socket.onopen = () => {
        const wasReconnect = this.reconnectAttempts > 0;
        this.reconnectAttempts = 0;
        this.heartbeat.start();
        if (wasReconnect && typeof this.onReconnect === 'function') {
          try {
            this.onReconnect();
          } catch (err) {
            console.error('TrackersLiveSocket onReconnect error:', err);
          }
        }
      };
      this.socket.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          const type = msg && msg.type;
          const data = msg && msg.data;
          if (type === 'pong') {
            this.heartbeat.onPong();
            return;
          }
          if (type && this.handlers.has(type)) {
            this.handlers.get(type).forEach((fn) => {
              try {
                fn(data);
              } catch (err) {
                console.error('TrackersLiveSocket handler error:', err);
              }
            });
          }
        } catch (err) {
          console.error('TrackersLiveSocket parse error:', err);
        }
      };
      this.socket.onclose = () => {
        this.socket = null;
        this.heartbeat.stop();
        if (this.shouldConnect) {
          const delay = Math.min(
            this.reconnectBaseDelayMs * Math.pow(2, Math.min(this.reconnectAttempts, 4)),
            this.reconnectMaxDelayMs
          );
          this.reconnectAttempts += 1;
          this.reconnectTimeoutId = setTimeout(() => {
            this.reconnectTimeoutId = null;
            if (this.shouldConnect) this.connect();
          }, delay);
        }
      };
      this.socket.onerror = () => {};
    } catch (err) {
      console.error('TrackersLiveSocket connect error:', err);
      this.socket = null;
    }
  }

  disconnect() {
    this.shouldConnect = false;
    this.onReconnect = null;
    this.heartbeat.stop();
    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId);
      this.reconnectTimeoutId = null;
    }
    if (this.socket) {
      this.socket.close(1000, null);
      this.socket = null;
    }
    this.reconnectAttempts = 0;
  }

  subscribe(type, handler) {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type).add(handler);
  }

  unsubscribe(type, handler) {
    if (this.handlers.has(type)) {
      this.handlers.get(type).delete(handler);
    }
  }
}

export const trackersLiveSocket = new TrackersLiveSocket();
export default trackersLiveSocket;

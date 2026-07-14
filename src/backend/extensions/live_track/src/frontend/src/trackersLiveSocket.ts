/**
 * WebSocket client for trackers-live endpoint (ws/extensions/live-track/trackers-live/).
 * Used by LiveTrackView for live track_updated events only. Same-origin; cookie/session auth.
 */
import type { WebSocketHeartbeatInstance } from './types/gv-core';

const WebSocketHeartbeat = window.gv_core.WebSocketHeartbeat;

export type TrackersLiveSocketHandler = (data: unknown) => void;

class TrackersLiveSocket {
  private socket: WebSocket | null = null;
  private readonly handlers = new Map<string, Set<TrackersLiveSocketHandler>>();
  private reconnectAttempts = 0;
  private readonly reconnectBaseDelayMs = 2000;
  private readonly reconnectMaxDelayMs = 30000;
  private reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private shouldConnect = false;
  onReconnect: (() => void) | null = null;
  private readonly heartbeat: WebSocketHeartbeatInstance;

  constructor() {
    this.heartbeat = new WebSocketHeartbeat({
      sendPing: () => {
        if (this.socket?.readyState === WebSocket.OPEN) {
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

  getWsUrl(): string {
    if (typeof window === 'undefined') return '';
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    return `${protocol}//${host}/ws/extensions/live-track/trackers-live/`;
  }

  connect(): void {
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
      this.socket.onmessage = (event: MessageEvent<string>) => {
        try {
          const msg = JSON.parse(event.data) as { type?: string; data?: unknown } | null;
          const type = msg?.type;
          const data = msg?.data;
          if (type === 'pong') {
            this.heartbeat.onPong();
            return;
          }
          if (type && this.handlers.has(type)) {
            this.handlers.get(type)?.forEach((fn) => {
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

  disconnect(): void {
    this.shouldConnect = false;
    this.onReconnect = null;
    this.heartbeat.stop();
    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId);
      this.reconnectTimeoutId = null;
    }
    if (this.socket) {
      this.socket.close(1000);
      this.socket = null;
    }
    this.reconnectAttempts = 0;
  }

  subscribe(type: string, handler: TrackersLiveSocketHandler): void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)?.add(handler);
  }

  unsubscribe(type: string, handler: TrackersLiveSocketHandler): void {
    if (this.handlers.has(type)) {
      this.handlers.get(type)?.delete(handler);
    }
  }
}

export const trackersLiveSocket = new TrackersLiveSocket();
export default trackersLiveSocket;

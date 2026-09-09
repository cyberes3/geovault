/**
 * App-lifetime realtime connection to `/ws/realtime/`.
 *
 * Transport (heartbeat, reconnect, handler fan-out) is `GeoVaultSocket`.
 * This class only owns the module registry and the `{ module, type, data }` envelope.
 */

import type { Store } from 'vuex';
import { GeoVaultSocket, sameOriginWebSocketUrl } from './GeoVaultSocket';
import type { SocketHandler } from './GeoVaultSocket';
import type { BaseModule } from './modules/BaseModule';
import type { RootState } from '../store';

function realtimeMessageKey(message: Record<string, unknown>): string | null {
    if (typeof message.module === 'string' && typeof message.type === 'string') {
        return `${message.module}:${message.type}`;
    }
    return typeof message.type === 'string' ? message.type : null;
}

function moduleEventKey(module: string, event: string): string {
    return `${module}:${event}`;
}

class RealtimeSocket {
    private readonly transport: GeoVaultSocket;
    private readonly modules = new Map<string, BaseModule>();

    constructor() {
        this.transport = new GeoVaultSocket({
            url: () => sameOriginWebSocketUrl('/ws/realtime/'),
            pingPayload: { module: 'ping', type: 'ping', data: {} },
            messageKey: realtimeMessageKey,
        });
    }

    get isConnected(): boolean {
        return this.transport.isConnected;
    }

    connect(): void {
        this.transport.connect();
    }

    /** No-op: component cleanup must not drop the app-lifetime realtime connection. */
    disconnect(): void {
    }

    forceDisconnect(): void {
        this.cleanupModules();
        this.transport.disconnect();
    }

    send(module: string, type: string, data: Record<string, any> = {}): void {
        this.transport.send({ module, type, data });
    }

    subscribe(module: string, event: string, handler: SocketHandler): void {
        this.transport.on(moduleEventKey(module, event), handler);
    }

    unsubscribe(module: string, event: string, handler: SocketHandler): void {
        this.transport.off(moduleEventKey(module, event), handler);
    }

    on(event: string, handler: SocketHandler): void {
        this.transport.on(event, handler);
    }

    off(event: string, handler: SocketHandler): void {
        this.transport.off(event, handler);
    }

    requestRefresh(module: string): void {
        this.send(module, 'refresh');
    }

    async loadAllModules(store: Store<RootState>): Promise<void> {
        try {
            const { loadAllModules } = await import('./modules/ModuleRegistry');
            for (const module of loadAllModules(store)) {
                this.registerModule(module);
            }
        } catch (error) {
            console.error('Failed to load modules from registry:', error);
        }
    }

    registerModule(module: BaseModule): void {
        if (!module.moduleName) {
            throw new Error('Module must have a moduleName property');
        }
        if (this.modules.has(module.moduleName)) {
            return;
        }

        module.socket = this;
        this.modules.set(module.moduleName, module);
        module.initialize();
    }

    unregisterModule(moduleName: string): void {
        const module = this.modules.get(moduleName);
        if (!module) {
            return;
        }
        module.cleanup();
        this.modules.delete(moduleName);
    }

    private cleanupModules(): void {
        this.modules.forEach((module) => {
            module.cleanup();
        });
        this.modules.clear();
    }

    getConnectionStatus() {
        return {
            isConnected: this.transport.isConnected,
            reconnectAttempts: this.transport.reconnectAttempts,
            shouldStayConnected: this.transport.isConnected || this.transport.reconnectAttempts > 0,
        };
    }
}

export const realtimeSocket = new RealtimeSocket();
export default realtimeSocket;

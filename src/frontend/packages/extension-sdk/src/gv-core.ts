import type { Component } from 'vue';
import type { MapEngine } from './manifest';
import type { ExtensionApi, PlatformStateBridge, ToastService } from './setup';

export interface TileSourceClientConfig {
    type?: string;
    style_url?: string;
    url?: string;
    tileSubdomains?: string[];
    tileSize?: number;
    attribution?: string;
    minzoom?: number;
    maxzoom?: number;
    [key: string]: unknown;
}

export interface TileSource {
    id: string;
    name: string;
    type: string;
    requires_proxy?: boolean;
    hidden?: boolean;
    exaggeration?: number;
    opacity?: number;
    client_config?: TileSourceClientConfig;
    [key: string]: unknown;
}

export interface TileSourceCatalog {
    load(): Promise<TileSource[]>;
    resolveSource?: (sources: TileSource[], preferredId?: string) => TileSource;
}

export interface GeocodingResult {
    id?: string | number;
    text?: string;
    place_name?: string;
    [key: string]: unknown;
}

export interface LocationMarkerCoords {
    latitude: number;
    longitude: number;
}

export interface GeoVaultSocketOptions {
    url: string | (() => string);
    pingPayload?: Record<string, unknown>;
    terminalCloseCodes?: readonly number[];
    maxReconnectAttempts?: number;
    reconnectBaseDelayMs?: number;
    reconnectMaxDelayMs?: number;
    reconnectJitterRatio?: number;
}

export interface GeoVaultSocketInstance {
    readonly isConnected: boolean;
    readonly reconnectAttempts: number;
    connect(): void;
    disconnect(): void;
    send(payload: unknown): void;
    on(key: string, handler: (data?: unknown) => void): () => void;
    off(key: string, handler: (data?: unknown) => void): void;
}

export interface PointPickerMap {
    flyTo: (lng: number, lat: number, zoom?: number) => void;
    setMarker: (lng: number, lat: number) => void;
    destroy: () => void;
}

export interface GvCoreMap {
    loadEngine(engine: MapEngine): Promise<unknown>;
    loadMaplibreGl: () => Promise<unknown>;
    maplibre: unknown;
    useUserLocationMarker: (map: unknown, coords: LocationMarkerCoords | null | undefined) => Promise<unknown>;
    createPointPickerMap: (container: HTMLElement, onPick: (lng: number, lat: number) => void) => Promise<PointPickerMap>;
    createGeoJsonPreviewMap: (container: HTMLElement) => Promise<unknown>;
}

export interface GvCoreUi {
    toast: ToastService;
    useDocumentTitle: (titleSource: unknown) => void;
    copyToClipboard: (text: string) => Promise<void>;
    hexToRgb: (hex: string) => [number, number, number] | null;
}

export interface GvCoreNet {
    coreApi: unknown;
    listUsers: () => Promise<Array<{ id: number; email: string }>>;
    connectExtensionSocket: (options: unknown) => unknown;
    downloadBlob: (url: string, filename: string) => Promise<void>;
}

export interface GvCoreSettings {
    awaitUserSettings: () => Promise<void>;
    status: () => 'idle' | 'loading' | 'ready' | 'error';
    getUnitPreference: () => string;
    getExtensionSetting: (extensionName: string, key: string) => unknown;
    useExtensionSettings: (extensionName: string) => {
        get(key: string): unknown;
        save(key: string, value: unknown): Promise<Record<string, unknown>>;
    };
}

export interface PublicShareSessionInstance {
    status: 'idle' | 'loading' | 'ready' | 'invalid';
    info: unknown;
    error: string | null;
    shareId: string | null;
    readonly allowDownloads: boolean;
    readonly includeTags: boolean;
    resetForShareIdChange(shareId?: string | null): void;
    ensureInfo(signal?: AbortSignal): Promise<boolean>;
    toMapShareInfo(): unknown;
}

export interface GvCoreSharing {
    absoluteUrl: (path: string) => string;
    PublicShareSession: new () => PublicShareSessionInstance;
}

export interface GvCoreWindow {
    map: GvCoreMap;
    ui: GvCoreUi;
    net: GvCoreNet;
    settings: GvCoreSettings;
    sharing: GvCoreSharing;
    createRouteWrapper: (
        component: Component,
        options: { api: ExtensionApi; platformState?: PlatformStateBridge; router?: unknown; routeName?: string; [key: string]: unknown }
    ) => Component;
}

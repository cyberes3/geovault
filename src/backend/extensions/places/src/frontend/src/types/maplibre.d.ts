/**
 * Minimal local shape of the subset of the MapLibre GL JS runtime API this extension actually
 * uses. The `maplibre-gl` package is never a real dependency here - core loads it lazily and
 * exposes the module on `window.gv_core.maplibre`/`window.maplibregl` (see
 * `src/frontend/src/utils/map/maplibre/lazyMaplibreGl.js`) - so there's no real npm package to
 * import types from in this extension's own project.
 */
export interface MaplibreLngLat {
    lng: number;
    lat: number;
}

export interface MaplibrePoint {
    x: number;
    y: number;
}

export interface MaplibreMapMouseEvent {
    point: MaplibrePoint;
    lngLat: MaplibreLngLat;
}

export interface MaplibreCameraOptions {
    center?: [number, number] | MaplibreLngLat;
    zoom?: number;
    bearing?: number;
    pitch?: number;
    duration?: number;
}

export interface MaplibreGeoJSONFeature {
    type: 'Feature';
    geometry: { type: string; coordinates: number[] };
    properties: Record<string, unknown>;
}

export interface MaplibreLngLatBoundsLike {
    extend(coordinates: [number, number]): MaplibreLngLatBoundsLike;
}

export interface MaplibreCameraForBoundsOptions {
    padding?: { top: number; right: number; bottom: number; left: number };
    maxZoom?: number;
}

export interface MaplibreMapOptions {
    container: HTMLElement;
    style: string | Record<string, unknown>;
    center: [number, number];
    zoom: number;
    minZoom?: number;
    maxZoom?: number;
    dragRotate?: boolean;
    pitchWithRotate?: boolean;
    attributionControl?: boolean;
    cooperativeGestures?: boolean;
}

export interface MaplibreGeoJSONSource {
    setData(data: { type: 'FeatureCollection'; features: MaplibreGeoJSONFeature[] }): void;
}

export interface MaplibreCooperativeGesturesHandler {
    enable(): void;
    disable(): void;
}

export interface MaplibreMap {
    readonly cooperativeGestures?: MaplibreCooperativeGesturesHandler;
    on(event: string, handler: (event: MaplibreMapMouseEvent) => void): void;
    off(event: string, handler: (event: MaplibreMapMouseEvent) => void): void;
    once(event: string, handler: (event?: MaplibreMapMouseEvent) => void): void;
    remove(): void;
    resize(): void;
    jumpTo(options: MaplibreCameraOptions): void;
    easeTo(options: MaplibreCameraOptions): void;
    setStyle(style: string | Record<string, unknown>): void;
    setMaxZoom(zoom: number): void;
    setBearing(bearing: number): void;
    getLayer(id: string): unknown;
    removeLayer(id: string): void;
    getSource(id: string): MaplibreGeoJSONSource | undefined;
    removeSource(id: string): void;
    addSource(id: string, spec: Record<string, unknown>): void;
    addLayer(layer: Record<string, unknown>): void;
    moveLayer(id: string): void;
    queryRenderedFeatures(point: MaplibrePoint, options?: { layers?: string[] }): MaplibreGeoJSONFeature[];
    getCenter(): MaplibreLngLat;
    getZoom(): number;
    getBearing(): number;
    getPitch(): number;
    cameraForBounds(bounds: MaplibreLngLatBoundsLike, options?: MaplibreCameraForBoundsOptions): MaplibreCameraOptions | null;
}

export interface MaplibreGlNamespace {
    Map: new (options: MaplibreMapOptions) => MaplibreMap;
    LngLatBounds: new (sw: [number, number], ne: [number, number]) => MaplibreLngLatBoundsLike;
}

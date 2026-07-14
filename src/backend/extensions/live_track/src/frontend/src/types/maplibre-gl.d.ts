/**
 * Minimal ambient types for `maplibre-gl`. This extension does not depend on the real `maplibre-gl`
 * package (it's loaded lazily as a shared runtime global via `window.gv_core.maplibre`/
 * `window.maplibregl` - see `gv-core.d.ts`), so there is no bundled/`@types` package to import
 * directly. Only the subset of the `Map`/`Marker`/`NavigationControl` API this extension actually
 * calls is declared here.
 */
declare module 'maplibre-gl' {
    export interface LngLat {
        lng: number;
        lat: number;
    }

    export type LngLatLike = [number, number] | { lng: number; lat: number };

    export type PointLike = { x: number; y: number } | [number, number];

    export interface PaddingOptions {
        top?: number;
        bottom?: number;
        left?: number;
        right?: number;
    }

    export interface MapGeoJSONFeature {
        properties: Record<string, unknown> | null;
        [key: string]: unknown;
    }

    export interface MapMouseEvent {
        point: { x: number; y: number };
        lngLat: LngLat;
        originalEvent?: Event;
    }

    export interface MapErrorEvent {
        error?: Error;
    }

    interface MapEventMap {
        load: () => void;
        styledata: () => void;
        error: (event: MapErrorEvent) => void;
        dragstart: () => void;
        wheel: () => void;
        dblclick: () => void;
        zoomstart: (event: MapMouseEvent) => void;
        click: (event: MapMouseEvent) => void;
        mousemove: (event: MapMouseEvent) => void;
        mouseout: () => void;
    }

    export interface GeoJSONSource {
        setData(data: unknown): void;
    }

    export interface MapOptions {
        container: HTMLElement | string;
        style: string | Record<string, unknown>;
        center?: [number, number];
        zoom?: number;
        bearing?: number;
        minZoom?: number;
        maxZoom?: number;
        maxPitch?: number;
        attributionControl?: boolean;
    }

    export interface FitBoundsOptions {
        padding?: number | PaddingOptions;
        maxZoom?: number;
        duration?: number;
    }

    export interface CameraOptions {
        center?: [number, number];
        zoom?: number;
        bearing?: number;
        duration?: number;
        padding?: number | PaddingOptions;
    }

    export class Map {
        constructor(options: MapOptions);
        on<K extends keyof MapEventMap>(type: K, listener: MapEventMap[K]): this;
        once<K extends keyof MapEventMap>(type: K, listener: MapEventMap[K]): this;
        getSource(id: string): GeoJSONSource | undefined;
        getLayer(id: string): unknown;
        addSource(id: string, source: Record<string, unknown>): void;
        addLayer(layer: Record<string, unknown>, beforeId?: string): void;
        removeLayer(id: string): void;
        removeSource(id: string): void;
        setStyle(style: string | Record<string, unknown>): this;
        getStyle(): Record<string, unknown> | undefined;
        addImage(
            id: string,
            image: { width: number; height: number; data: Uint8Array | Uint8ClampedArray },
            options?: { pixelRatio?: number }
        ): void;
        hasImage(id: string): boolean;
        addControl(control: unknown, position?: string): this;
        resize(): this;
        remove(): void;
        getZoom(): number;
        getCenter(): LngLat;
        getBearing(): number;
        getCanvas(): HTMLCanvasElement;
        fitBounds(bounds: [[number, number], [number, number]], options?: FitBoundsOptions): this;
        jumpTo(options: CameraOptions): this;
        easeTo(options: CameraOptions): this;
        queryRenderedFeatures(geometry?: PointLike | [PointLike, PointLike], options?: { layers?: string[] }): MapGeoJSONFeature[];
        dragRotate: { disable(): void };
        touchZoomRotate: { disableRotation(): void };
        keyboard: { disableRotation(): void };
    }

    export interface MarkerOptions {
        element?: HTMLElement;
        pitchAlignment?: string;
        rotationAlignment?: string;
    }

    export class Marker {
        constructor(options?: MarkerOptions);
        setLngLat(lngLat: LngLatLike): this;
        addTo(map: Map): this;
        remove(): this;
    }

    export interface NavigationControlOptions {
        showCompass?: boolean;
        showZoom?: boolean;
    }

    export class NavigationControl {
        constructor(options?: NavigationControlOptions);
    }
}

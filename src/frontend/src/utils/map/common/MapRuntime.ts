import type { Map as MapLibreMap, MapMouseEvent, StyleSpecification } from 'maplibre-gl';
import { initializeMap, setupGeoJsonSource, MAX_ZOOM_LEVEL, DEFAULT_GLYPHS_URL } from '@/utils/map/maplibre/mapInitialization.js';
import { getLoadedMaplibreGl } from '@/utils/map/maplibre/lazyMaplibreGl.js';
import { setupCopyMapCoordinatesOnContextMenu } from '@/utils/map/copyMapCoordinatesOnContextMenu.js';
import { setupUserGestureTrackingUnlock } from '@/utils/map/maplibre/trackingLock.js';
import { toast } from '@/utils/toast';
import type { CameraSnapshot, MapStyleInput } from './types';

export interface MapRuntimeCallbacks {
    onMoveOrZoomStart: () => void;
    onMoveEnd: () => void;
    onZoomEnd: (zoom: number) => void;
    onZoomFrame: () => void;
    onClick: (event: MapMouseEvent) => void;
    onMouseMove: (event: MapMouseEvent) => void;
    onMouseOut: () => void;
    isTrackingLocked: () => boolean;
    onTrackingUnlock: () => void;
    onWebGlLost?: () => void;
    onStyleImageMissing?: (iconId: string) => void;
}

export interface MapRuntimeCreateConfig {
    center: [number, number];
    zoom: number;
    pitch?: number;
    bearing?: number;
    style?: MapStyleInput;
    antialias?: boolean;
}

/**
 * Owns the MapLibre instance, style epoch, and overlay lifecycle.
 * Layer switches always go through `setStyle`.
 */
export class MapRuntime {
    map: MapLibreMap | null = null;
    styleEpoch = 0;
    private teardown: (() => void) | null = null;
    private zoomFrame: number | null = null;

    constructor(private readonly callbacks: MapRuntimeCallbacks) {}

    get hasMap(): boolean {
        return !!this.map;
    }

    async create(container: HTMLElement, config: MapRuntimeCreateConfig): Promise<MapLibreMap> {
        this.destroy();
        this.map = await initializeMap(container, {
            center: config.center,
            zoom: config.zoom,
            pitch: config.pitch ?? 0,
            bearing: config.bearing ?? 0,
            glyphsUrl: DEFAULT_GLYPHS_URL,
            antialias: config.antialias ?? false,
            style: config.style,
        });

        const maplibregl = getLoadedMaplibreGl();
        this.map.addControl(
            new maplibregl.NavigationControl({
                visualizePitch: true,
                showCompass: true,
                showZoom: true,
            }),
            'top-left',
        );

        setupGeoJsonSource(this.map);
        this.bindEvents();
        this.bindContextLoss();
        return this.map;
    }

    async setStyle(style: string | StyleSpecification): Promise<void> {
        if (!this.map) throw new Error('Map is not created');
        this.styleEpoch += 1;
        const epoch = this.styleEpoch;
        this.map.setStyle(style);
        await this.waitForIdle();
        if (epoch !== this.styleEpoch || !this.map) return;
        this.ensureGeoJsonSource();
        this.map.setMaxZoom(MAX_ZOOM_LEVEL);
    }

    ensureGeoJsonSource(): void {
        if (!this.map || this.map.getSource('geojson-data')) return;
        this.map.addSource('geojson-data', {
            type: 'geojson',
            data: { type: 'FeatureCollection', features: [] },
        });
    }

    waitForIdle(timeoutMs = 15000): Promise<void> {
        const map = this.map;
        if (!map) return Promise.reject(new Error('Map is not created'));
        return new Promise((resolve, reject) => {
            const onIdle = () => {
                clearTimeout(timer);
                resolve();
            };
            const timer = setTimeout(() => {
                map.off('idle', onIdle);
                reject(new Error('Timed out waiting for map idle'));
            }, timeoutMs);
            map.once('idle', onIdle);
        });
    }

    waitUntilSourceReady(timeoutMs = 5000): Promise<void> {
        const start = Date.now();
        return new Promise((resolve, reject) => {
            const check = () => {
                if (this.map?.getSource('geojson-data')) {
                    resolve();
                    return;
                }
                if (Date.now() - start > timeoutMs) {
                    reject(new Error('Timed out waiting for geojson-data source'));
                    return;
                }
                setTimeout(check, 50);
            };
            check();
        });
    }

    snapshotCamera(): CameraSnapshot | null {
        if (!this.map) return null;
        const center = this.map.getCenter();
        return {
            center: [center.lng, center.lat],
            zoom: this.map.getZoom(),
            pitch: this.map.getPitch(),
            bearing: this.map.getBearing(),
        };
    }

    resize(): void {
        this.map?.resize();
    }

    updateLayerMaxZoom(minMaxZoom: number = MAX_ZOOM_LEVEL + 1): void {
        if (!this.map) return;
        const mapInstance = this.map;
        try {
            const style = mapInstance.getStyle();
            style.layers.forEach((layer) => {
                try {
                    const currentMaxZoom = layer.maxzoom;
                    const currentMinZoom = layer.minzoom ?? 0;
                    if (currentMaxZoom === undefined || currentMaxZoom < minMaxZoom) {
                        mapInstance.setLayerZoomRange(layer.id, currentMinZoom, minMaxZoom);
                    }
                } catch (error) {
                    console.debug(`Could not update maxzoom for layer ${layer.id}:`, error);
                }
            });
        } catch (error) {
            console.warn('Error updating layer maxzoom:', error);
        }
    }

    destroy(): void {
        if (this.zoomFrame != null) {
            cancelAnimationFrame(this.zoomFrame);
            this.zoomFrame = null;
        }
        this.teardown?.();
        this.teardown = null;
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
    }

    private bindEvents(): void {
        const map = this.map;
        if (!map) return;
        this.teardown?.();

        const onMove = () => { this.callbacks.onMoveOrZoomStart(); };
        const onZoom = () => {
            if (map.getZoom() > MAX_ZOOM_LEVEL) {
                map.setZoom(MAX_ZOOM_LEVEL);
            }
            this.callbacks.onMoveOrZoomStart();
        };
        const onZoomFrame = () => {
            if (this.zoomFrame != null) cancelAnimationFrame(this.zoomFrame);
            this.zoomFrame = requestAnimationFrame(() => {
                this.callbacks.onZoomFrame();
                this.zoomFrame = null;
            });
        };
        const onMoveEnd = () => { this.callbacks.onMoveEnd(); };
        const onZoomEnd = () => {
            const currentZoom = map.getZoom();
            if (currentZoom > MAX_ZOOM_LEVEL) {
                map.setZoom(MAX_ZOOM_LEVEL);
            }
            if (currentZoom >= MAX_ZOOM_LEVEL - 0.5) {
                this.updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);
            }
            this.callbacks.onZoomEnd(map.getZoom());
        };
        const onStyleImageMissing = (event: { id: string }) => {
            this.callbacks.onStyleImageMissing?.(event.id);
        };
        const onClick = (event: MapMouseEvent) => { this.callbacks.onClick(event); };
        let lastMouse = 0;
        const onMouseMove = (event: MapMouseEvent) => {
            const now = Date.now();
            if (now - lastMouse < 100) return;
            lastMouse = now;
            this.callbacks.onMouseMove(event);
        };
        const onMouseOut = () => { this.callbacks.onMouseOut(); };

        map.on('move', onMove);
        map.on('zoom', onZoom);
        map.on('zoom', onZoomFrame);
        map.on('moveend', onMoveEnd);
        map.on('zoomend', onZoomEnd);
        map.on('styleimagemissing', onStyleImageMissing);
        map.on('click', onClick);
        map.on('mousemove', onMouseMove);
        map.on('mouseout', onMouseOut);

        const unlock = setupUserGestureTrackingUnlock(map, {
            isLocked: this.callbacks.isTrackingLocked,
            onUnlock: this.callbacks.onTrackingUnlock,
        }) as () => void;
        const copyCoords = setupCopyMapCoordinatesOnContextMenu(map, { toast }) as () => void;

        this.teardown = () => {
            map.off('move', onMove);
            map.off('zoom', onZoom);
            map.off('zoom', onZoomFrame);
            map.off('moveend', onMoveEnd);
            map.off('zoomend', onZoomEnd);
            map.off('styleimagemissing', onStyleImageMissing);
            map.off('click', onClick);
            map.off('mousemove', onMouseMove);
            map.off('mouseout', onMouseOut);
            unlock();
            copyCoords();
        };
    }

    private bindContextLoss(): void {
        const canvas = this.map?.getCanvas();
        if (!canvas) return;
        canvas.addEventListener('webglcontextlost', (event) => {
            event.preventDefault();
            this.callbacks.onWebGlLost?.();
        });
    }
}

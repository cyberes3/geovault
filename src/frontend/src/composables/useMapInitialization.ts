/**
 * Vue shell around MapRuntime: container ref, label markers, and wait helpers.
 */
import { markRaw, ref, shallowRef, type Ref, type ShallowRef } from 'vue';
import type { Map as MapLibreMap, MapEventType, StyleSpecification } from 'maplibre-gl';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import type { LabelMarkerManager } from '@/utils/map/maplibre/labelMarkers.js';
import type { MapSession } from '@/utils/map/session/MapSession';

export interface MapConfigInit {
    center: [number, number];
    zoom: number;
    pitch?: number;
    bearing?: number;
    style?: StyleSpecification | string;
}

export interface UseMapInitializationDeps {
    session: MapSession;
    getEnableAntialias: () => boolean;
}

export function useMapInitialization(deps: UseMapInitializationDeps) {
    const mapContainer = ref<HTMLElement | null>(null);
    const map: ShallowRef<MapLibreMap | null> = shallowRef(null);
    const labelMarkerManager: ShallowRef<LabelMarkerManager | null> = shallowRef(null);
    const showAllLabels = ref(true);
    const isMapInitializing = ref(false);
    const mapWasDestroyed = ref(false);

    function syncMapRef(): void {
        map.value = deps.session.runtime.map ? markRaw(deps.session.runtime.map) : null;
    }

    async function createMapInstance(mapConfig: MapConfigInit): Promise<void> {
        if (!mapContainer.value || !(mapContainer.value instanceof HTMLElement)) {
            throw new Error('Map container is not available');
        }

        deps.session.labels?.clear();
        await deps.session.runtime.create(mapContainer.value, {
            center: mapConfig.center,
            zoom: mapConfig.zoom,
            pitch: mapConfig.pitch ?? 0,
            bearing: mapConfig.bearing ?? 0,
            antialias: deps.getEnableAntialias(),
            style: mapConfig.style,
        });
        deps.session.attachLabels();
        labelMarkerManager.value = deps.session.labels;
        syncMapRef();
    }

    function destroyMap(): void {
        deps.session.labels?.clear();
        deps.session.labels = null;
        labelMarkerManager.value = null;
        deps.session.runtime.destroy();
        syncMapRef();
    }

    function performMapDestruction(): void {
        deps.session.camera.save(deps.session.runtime.map);
        destroyMap();
        mapWasDestroyed.value = true;
    }

    function ensureMapResize(): void {
        if (!map.value) return;
        if (map.value.loaded()) {
            map.value.resize();
        } else {
            void map.value.once('load', () => {
                map.value?.resize();
            });
        }
    }

    function waitForElement(elRef: Ref<HTMLElement | null>, timeout = 2000): Promise<HTMLElement> {
        if (elRef.value instanceof HTMLElement) {
            return Promise.resolve(elRef.value);
        }
        return new Promise((resolve, reject) => {
            const start = Date.now();
            const check = () => {
                if (elRef.value instanceof HTMLElement) {
                    resolve(elRef.value);
                    return;
                }
                if (Date.now() - start > timeout) {
                    reject(new Error(`Element not found within ${timeout}ms`));
                    return;
                }
                requestAnimationFrame(check);
            };
            check();
        });
    }

    function waitForMap(): Promise<void> {
        return deps.session.runtime.waitUntilSourceReady();
    }

    function waitForMapEvent(eventName: string, timeout = 30000): Promise<void> {
        if (eventName === 'idle') {
            return deps.session.runtime.waitForIdle(timeout);
        }
        if (!map.value) {
            return Promise.reject(new Error('Map is not created'));
        }
        const mapInstance = map.value;
        return new Promise((resolve, reject) => {
            if (eventName === 'load' && mapInstance.loaded()) {
                resolve();
                return;
            }
            const onEvent = () => {
                clearTimeout(timeoutId);
                resolve();
            };
            const event = eventName as keyof MapEventType;
            const timeoutId = setTimeout(() => {
                mapInstance.off(event, onEvent);
                reject(new Error(`Timed out waiting for ${eventName} event`));
            }, timeout);
            void mapInstance.once(event, onEvent);
        });
    }

    function updateLayerMaxZoom(minMaxZoom: number = MAX_ZOOM_LEVEL + 1): void {
        deps.session.runtime.updateLayerMaxZoom(minMaxZoom);
    }

    return {
        mapContainer,
        map,
        labelMarkerManager,
        showAllLabels,
        isMapInitializing,
        mapWasDestroyed,
        createMapInstance,
        destroyMap,
        performMapDestruction,
        ensureMapResize,
        waitForElement,
        waitForMap,
        waitForMapEvent,
        updateLayerMaxZoom,
    };
}

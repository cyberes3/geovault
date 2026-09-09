/**
 * Vue shell around MapRuntime: container ref, label markers, saved camera, and wait helpers.
 * MapLibre construction and event wiring live on MapRuntime.
 */
import { markRaw, ref, shallowRef, type Ref, type ShallowRef } from 'vue';
import type { LngLat, Map as MapLibreMap, StyleSpecification } from 'maplibre-gl';
import { LabelMarkerManager } from '@/utils/map/maplibre';
import { MAX_ZOOM_LEVEL } from '@/utils/map/maplibre/mapInitialization.js';
import type { MapRuntime } from '@/utils/map/common/MapRuntime';

export interface MapConfigInit {
    center: [number, number];
    zoom: number;
    pitch?: number;
    bearing?: number;
    style?: StyleSpecification | string;
}

export interface UseMapInitializationDeps {
    runtime: MapRuntime;
    getEnableAntialias: () => boolean;
}

export function useMapInitialization(deps: UseMapInitializationDeps) {
    const mapContainer = ref<HTMLElement | null>(null);
    const map: ShallowRef<MapLibreMap | null> = shallowRef(null);
    const labelMarkerManager: ShallowRef<LabelMarkerManager | null> = shallowRef(null);
    const showAllLabels = ref(true);
    const isMapInitializing = ref(false);
    const mapWasDestroyed = ref(false);

    const savedMapCenter: Ref<LngLat | null> = ref(null);
    const savedMapZoom: Ref<number | null> = ref(null);
    const savedMapPitch: Ref<number | null> = ref(null);
    const savedMapBearing: Ref<number | null> = ref(null);

    function syncMapRef(): void {
        map.value = deps.runtime.map ? markRaw(deps.runtime.map) : null;
    }

    function attachLabelManager(): void {
        if (!map.value) return;
        labelMarkerManager.value = new LabelMarkerManager(map.value);
        labelMarkerManager.value.setVisibility(showAllLabels.value);
    }

    function detachLabelManager(): void {
        if (labelMarkerManager.value) {
            labelMarkerManager.value.clearAllMarkers();
            labelMarkerManager.value = null;
        }
    }

    async function createMapInstance(mapConfig: MapConfigInit): Promise<void> {
        if (!mapContainer.value || !(mapContainer.value instanceof HTMLElement)) {
            throw new Error('Map container is not available');
        }

        detachLabelManager();
        await deps.runtime.create(mapContainer.value, {
            center: mapConfig.center,
            zoom: mapConfig.zoom,
            pitch: mapConfig.pitch ?? 0,
            bearing: mapConfig.bearing ?? 0,
            antialias: deps.getEnableAntialias(),
            style: mapConfig.style,
        });
        syncMapRef();
        attachLabelManager();
    }

    function destroyMap(): void {
        detachLabelManager();
        deps.runtime.destroy();
        syncMapRef();
    }

    function performMapDestruction(): void {
        const live = map.value ?? deps.runtime.map;
        if (live) {
            savedMapCenter.value = live.getCenter();
            savedMapZoom.value = live.getZoom();
            savedMapPitch.value = live.getPitch();
            savedMapBearing.value = live.getBearing();
        }
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
        return deps.runtime.waitUntilSourceReady();
    }

    function waitForMapEvent(eventName: string, timeout = 30000): Promise<void> {
        if (eventName === 'idle') {
            return deps.runtime.waitForIdle(timeout);
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
            const timeoutId = setTimeout(() => {
                mapInstance.off(eventName, onEvent);
                reject(new Error(`Timed out waiting for ${eventName} event`));
            }, timeout);
            void mapInstance.once(eventName, onEvent);
        });
    }

    function updateLayerMaxZoom(minMaxZoom: number = MAX_ZOOM_LEVEL + 1): void {
        deps.runtime.updateLayerMaxZoom(minMaxZoom);
    }

    return {
        mapContainer,
        map,
        labelMarkerManager,
        showAllLabels,
        isMapInitializing,
        mapWasDestroyed,
        savedMapCenter,
        savedMapZoom,
        savedMapPitch,
        savedMapBearing,
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

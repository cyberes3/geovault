/**
 * MapLibre map instance lifecycle: creation, teardown, and the raw event-wiring plumbing
 * (move/zoom/click/hover/style-image-missing listeners). Business logic for what happens on
 * those events (feature loading, selection, highlighting) is injected as callbacks so this
 * composable only owns the map instance itself, its label marker manager, and saved
 * center/zoom/pitch/bearing used to restore the map after `keep-alive` deactivation.
 */
import { markRaw, ref, shallowRef, type Ref, type ShallowRef } from 'vue';
import maplibregl, { type LngLat, type Map as MapLibreMap, type MapMouseEvent, type StyleSpecification } from 'maplibre-gl';
import {
    initializeMap,
    setupGeoJsonSource,
    setupMapEventListeners,
    getFeatureIconUrl,
    getIconSourceUrl,
    loadIconImage,
    LabelMarkerManager,
} from '@/utils/map/maplibre';
import { MAX_ZOOM_LEVEL, DEFAULT_GLYPHS_URL } from '@/utils/map/maplibre/mapInitialization.js';
import { setupCopyMapCoordinatesOnContextMenu } from '@/utils/map/copyMapCoordinatesOnContextMenu.js';
import { setupUserGestureTrackingUnlock } from '@/utils/map/maplibre/trackingLock.js';
import { toast } from '@/utils/toast';

export interface MapConfigInit {
    center: [number, number];
    zoom: number;
    pitch?: number;
    bearing?: number;
    /** Initial style URL or style spec (defaults to a blank style) - pass the resolved basemap style to avoid a flash. */
    style?: StyleSpecification | string;
}

export interface MapEventCallbacks {
    /** Fired on raw `move`/`zoom` (not `moveend`/`zoomend`) to cancel in-flight bbox queries before they go stale. */
    onMoveOrZoomStart: () => void;
    onMoveEnd: () => void;
    onZoomEnd: (currentZoom: number) => void;
    /** Called via requestAnimationFrame batching while zooming (label/icon visibility upkeep). */
    onZoomFrame: () => void;
    onClick: (e: MapMouseEvent) => void;
    onMouseMove: (e: MapMouseEvent) => void;
    onMouseOut: () => void;
    /** Location-tracking lock state; a user gesture (drag/wheel/dblclick/touch-zoom) unlocks it. */
    isTrackingLocked: () => boolean;
    onTrackingUnlock: () => void;
}

export interface UseMapInitializationDeps {
    /** Anti-aliasing preference, read fresh at map-creation time. */
    getEnableAntialias: () => boolean;
    callbacks: MapEventCallbacks;
}

export function useMapInitialization(deps: UseMapInitializationDeps) {
    const mapContainer = ref<HTMLElement | null>(null);
    const map: ShallowRef<MapLibreMap | null> = shallowRef(null);
    const labelMarkerManager: ShallowRef<LabelMarkerManager | null> = shallowRef(null);
    const showAllLabels = ref(true);
    const isMapInitializing = ref(false);
    const mapWasDestroyed = ref(false);
    const isMapMoving = ref(false);

    const savedMapCenter: Ref<LngLat | null> = ref(null);
    const savedMapZoom: Ref<number | null> = ref(null);
    const savedMapPitch: Ref<number | null> = ref(null);
    const savedMapBearing: Ref<number | null> = ref(null);

    interface MapInteractionHandlers {
        onMove: () => void;
        onZoom: () => void;
        onZoomFrame: () => void;
        onStyleImageMissing: (e: { id: string }) => void;
        onMouseMove: (e: MapMouseEvent) => void;
        onMouseOut: () => void;
        teardownCopyCoords: () => void;
        teardownMapEventListeners: () => void;
    }
    let mapInteractionHandlers: MapInteractionHandlers | null = null;
    let teardownTrackingUnlockHandlers: (() => void) | null = null;
    let movementTimeout: ReturnType<typeof setTimeout> | null = null;
    let zoomUpdateFrame: number | null = null;
    let lastMouseMoveTime = 0;

    function markMovementStarted(): void {
        isMapMoving.value = true;
        if (movementTimeout) clearTimeout(movementTimeout);
        movementTimeout = setTimeout(() => {
            isMapMoving.value = false;
        }, 150);
    }

    /**
     * Remove every listener `setupMapEventHandlers` registered, on whichever map instance they
     * were actually registered on (captured via closure in `handlers`, not the live `map.value` -
     * important because this must also run against an about-to-be-removed instance during
     * `performMapDestruction`, after which `map.value` no longer points at it).
     */
    function teardownMapInteractionHandlers(): void {
        if (mapInteractionHandlers) {
            const handlers = mapInteractionHandlers;
            handlers.teardownMapEventListeners();
            handlers.teardownCopyCoords();
            mapInteractionHandlers = null;
        }
        if (teardownTrackingUnlockHandlers) {
            teardownTrackingUnlockHandlers();
            teardownTrackingUnlockHandlers = null;
        }
    }

    /** Resolve an icon URL from the currently-cached source features for a missing style image. */
    function handleStyleImageMissing(iconId: string): void {
        if (!iconId.startsWith('icon-') || !map.value) return;

        const source = map.value.getSource('geojson-data');
        if (!source) return;

        const serialized = source.serialize() as { data?: { features?: Array<{ properties?: Record<string, unknown> }> } };
        const features = serialized.data?.features ?? [];

        for (const feature of features) {
            if (feature.properties?.['_icon-id'] === iconId) {
                const iconUrl = getFeatureIconUrl(feature.properties);
                if (iconUrl) {
                    const resolvedUrl = getIconSourceUrl(iconUrl, feature.properties);
                    loadIconImage(map.value, iconId, resolvedUrl).catch((err: unknown) => {
                        console.warn(`Failed to load missing icon ${iconId}:`, err);
                    });
                    return;
                }
            }
        }
        console.warn(`Could not find feature for missing icon: ${iconId}`);
    }

    function setupMapEventHandlers(): void {
        if (!map.value) return;
        const mapInstance = map.value;

        teardownMapInteractionHandlers();

        const onMove = () => {
            deps.callbacks.onMoveOrZoomStart();
            markMovementStarted();
        };
        mapInstance.on('move', onMove);

        const onZoom = () => {
            const currentZoom = mapInstance.getZoom();
            if (currentZoom > MAX_ZOOM_LEVEL) {
                mapInstance.setZoom(MAX_ZOOM_LEVEL);
            }
            deps.callbacks.onMoveOrZoomStart();
            markMovementStarted();
        };
        mapInstance.on('zoom', onZoom);

        const onZoomFrame = () => {
            if (zoomUpdateFrame) {
                cancelAnimationFrame(zoomUpdateFrame);
            }
            zoomUpdateFrame = requestAnimationFrame(() => {
                deps.callbacks.onZoomFrame();
                zoomUpdateFrame = null;
            });
        };
        mapInstance.on('zoom', onZoomFrame);

        const onStyleImageMissing = (e: { id: string }) => { handleStyleImageMissing(e.id); };
        mapInstance.on('styleimagemissing', onStyleImageMissing);

        const MOUSE_MOVE_THROTTLE = 100;
        const onMouseMove = (e: MapMouseEvent) => {
            const now = Date.now();
            if (now - lastMouseMoveTime < MOUSE_MOVE_THROTTLE) return;
            lastMouseMoveTime = now;
            deps.callbacks.onMouseMove(e);
        };
        mapInstance.on('mousemove', onMouseMove);

        const onMouseOut = () => { deps.callbacks.onMouseOut(); };
        mapInstance.on('mouseout', onMouseOut);

        teardownTrackingUnlockHandlers = setupUserGestureTrackingUnlock(mapInstance, {
            isLocked: deps.callbacks.isTrackingLocked,
            onUnlock: deps.callbacks.onTrackingUnlock,
        }) as () => void;

        const teardownMapEventListeners = setupMapEventListeners(mapInstance, {
            onMoveEnd: () => {
                isMapMoving.value = false;
                if (movementTimeout) {
                    clearTimeout(movementTimeout);
                    movementTimeout = null;
                }
                deps.callbacks.onMoveEnd();
            },
            onZoomEnd: () => {
                const currentZoom = mapInstance.getZoom();
                if (currentZoom > MAX_ZOOM_LEVEL) {
                    mapInstance.setZoom(MAX_ZOOM_LEVEL);
                }

                if (currentZoom >= MAX_ZOOM_LEVEL - 0.5) {
                    updateLayerMaxZoom(MAX_ZOOM_LEVEL + 1);
                }

                isMapMoving.value = false;
                if (movementTimeout) {
                    clearTimeout(movementTimeout);
                    movementTimeout = null;
                }
                deps.callbacks.onZoomEnd(currentZoom);
            },
            onClick: (e: MapMouseEvent) => { deps.callbacks.onClick(e); },
        }) as () => void;

        mapInteractionHandlers = {
            onMove,
            onZoom,
            onZoomFrame,
            onStyleImageMissing,
            onMouseMove,
            onMouseOut,
            teardownCopyCoords: setupCopyMapCoordinatesOnContextMenu(mapInstance, { toast }) as () => void,
            teardownMapEventListeners: () => {
                mapInstance.off('move', onMove);
                mapInstance.off('zoom', onZoom);
                mapInstance.off('zoom', onZoomFrame);
                mapInstance.off('styleimagemissing', onStyleImageMissing);
                mapInstance.off('mousemove', onMouseMove);
                mapInstance.off('mouseout', onMouseOut);
                teardownMapEventListeners();
            },
        };
    }

    function createMapInstance(mapConfig: MapConfigInit): void {
        if (!mapContainer.value || !(mapContainer.value instanceof HTMLElement)) {
            throw new Error('Map container is not available');
        }

        map.value = markRaw(
            initializeMap(mapContainer.value, {
                center: mapConfig.center,
                zoom: mapConfig.zoom,
                pitch: mapConfig.pitch ?? 0,
                bearing: mapConfig.bearing ?? 0,
                glyphsUrl: DEFAULT_GLYPHS_URL,
                antialias: deps.getEnableAntialias(),
                style: mapConfig.style,
            }),
        );

        map.value.addControl(
            new maplibregl.NavigationControl({
                visualizePitch: true,
                showCompass: true,
                showZoom: true,
            }),
            'top-left',
        );

        labelMarkerManager.value = new LabelMarkerManager(map.value);
        labelMarkerManager.value.setVisibility(showAllLabels.value);

        setupGeoJsonSource(map.value, () => {
            // Map source loaded.
        });

        setupMapEventHandlers();
    }

    /** Simple map teardown (used mid-session, e.g. before recreating for a raster layer switch). */
    function destroyMap(): void {
        teardownMapInteractionHandlers();
        if (labelMarkerManager.value) {
            labelMarkerManager.value.clearAllMarkers();
            labelMarkerManager.value = null;
        }
        if (map.value) {
            map.value.remove();
            map.value = null;
        }
    }

    /** Full destruction used when navigating away (keep-alive `deactivated`): saves camera state for restore. */
    function performMapDestruction(): void {
        if (zoomUpdateFrame) {
            cancelAnimationFrame(zoomUpdateFrame);
            zoomUpdateFrame = null;
        }

        if (map.value && !savedMapCenter.value) {
            savedMapCenter.value = map.value.getCenter();
            savedMapZoom.value = map.value.getZoom();
            savedMapPitch.value = map.value.getPitch();
            savedMapBearing.value = map.value.getBearing();
        }

        // Detach every listener registered by `setupMapEventHandlers` before `remove()`: `.off()`
        // needs the map instance the listeners were actually registered on, so this must run
        // while `map.value` still points at it.
        teardownMapInteractionHandlers();

        if (labelMarkerManager.value) {
            labelMarkerManager.value.clear();
            labelMarkerManager.value = null;
        }

        if (map.value) {
            map.value.remove();
            map.value = null;
        }

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

    /** Wait for the map instance (and its GeoJSON source) to exist; handles keep-alive restore races. */
    function waitForMap(): Promise<void> {
        const maxWait = 5000;
        const checkInterval = 50;
        const startTime = Date.now();

        return new Promise((resolve) => {
            const check = () => {
                if (map.value?.getSource('geojson-data')) {
                    resolve();
                    return;
                }
                if (Date.now() - startTime > maxWait) {
                    console.error('Timeout waiting for map to be ready');
                    resolve();
                    return;
                }
                setTimeout(check, checkInterval);
            };
            check();
        });
    }

    function waitForMapEvent(eventName: string, timeout = 30000): Promise<void> {
        if (!map.value) {
            return Promise.resolve();
        }
        const mapInstance = map.value;

        return new Promise((resolve) => {
            if (eventName === 'load' && mapInstance.loaded()) {
                resolve();
                return;
            }

            // Named so the timeout branch can `.off()` it below - `Evented.off()` removes from
            // both regular and one-time listener maps, so this cancels the pending `once()`
            // registration instead of leaving it to fire (harmlessly, but not for free) later.
            const onEvent = () => {
                clearTimeout(timeoutId);
                resolve();
            };

            const timeoutId = setTimeout(() => {
                console.warn(`Timeout waiting for ${eventName} event`);
                mapInstance.off(eventName, onEvent);
                resolve();
            }, timeout);

            void mapInstance.once(eventName, onEvent);
        });
    }

    /**
     * Update all layers in the style to have a minimum maxzoom value (MapLibre's maxzoom is
     * exclusive, so MAX_ZOOM_LEVEL + 1 is required for layers to render at MAX_ZOOM_LEVEL).
     */
    function updateLayerMaxZoom(minMaxZoom: number = MAX_ZOOM_LEVEL + 1): void {
        if (!map.value) return;
        const mapInstance = map.value;

        try {
            const style = mapInstance.getStyle();

            style.layers.forEach((layer) => {
                try {
                    const currentMaxZoom = layer.maxzoom;
                    const currentMinZoom = layer.minzoom ?? 0;

                    if (currentMaxZoom !== undefined && currentMaxZoom < minMaxZoom) {
                        mapInstance.setLayerZoomRange(layer.id, currentMinZoom, minMaxZoom);
                    } else if (currentMaxZoom === undefined) {
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

    return {
        mapContainer,
        map,
        labelMarkerManager,
        showAllLabels,
        isMapInitializing,
        mapWasDestroyed,
        isMapMoving,
        savedMapCenter,
        savedMapZoom,
        savedMapPitch,
        savedMapBearing,
        createMapInstance,
        destroyMap,
        performMapDestruction,
        teardownMapInteractionHandlers,
        ensureMapResize,
        waitForElement,
        waitForMap,
        waitForMapEvent,
        updateLayerMaxZoom,
    };
}

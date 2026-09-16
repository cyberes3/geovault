import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { buildMapConstructorOptions, remapMapInitError, createTransformRequest, waitForStyleLoaded, waitForMapIdle, isMapIdle, resolveMapStyle } = await import('./mapInitialization.ts');

test('buildMapConstructorOptions sets style, transform, and map options', () => {
    const container = /** @type {HTMLElement} */ ({});
    const options = buildMapConstructorOptions(container, {
        center: [12, 34],
        zoom: 6,
        pitch: 10,
        bearing: 20,
        antialias: true,
        style: { version: 8, sources: {}, layers: [] },
    });
    assert.equal(options.attributionControl, false);
    assert.equal(options.maxZoom, 18);
    assert.equal(options.maxPitch, 85);
    assert.deepEqual(options.canvasContextAttributes, { antialias: true });
    assert.equal(typeof options.transformRequest, 'function');
    assert.deepEqual(options.center, [12, 34]);
    assert.equal(options.zoom, 6);
});

test('buildMapConstructorOptions can enable attribution', () => {
    const container = /** @type {HTMLElement} */ ({});
    const options = buildMapConstructorOptions(container, {
        center: [0, 0],
        zoom: 1,
        attributionControl: true,
    });
    assert.equal(options.attributionControl, true);
});

test('createTransformRequest marks OSM tiles', () => {
    const transform = createTransformRequest();
    const result = transform('https://tile.openstreetmap.org/1/1/1.png', 'Tile');
    assert.equal(result.referrerPolicy, 'strict-origin-when-cross-origin');
});

test('waitForStyleLoaded resolves when the style is already loaded', async () => {
    const map = {
        isStyleLoaded: () => true,
        off() {},
        once() {},
        on() {},
    };
    await waitForStyleLoaded(/** @type {never} */ (map));
});

test('waitForStyleLoaded waits for the load event', async () => {
    let loaded = false;
    /** @type {((event: string, handler: () => void) => void) | null} */
    let attach = null;
    const map = {
        isStyleLoaded: () => loaded,
        off() {},
        once(event, handler) {
            if (event === 'load') attach = handler;
        },
        on() {},
    };
    const pending = waitForStyleLoaded(/** @type {never} */ (map));
    loaded = true;
    attach();
    await pending;
});

function createIdleMap(options = {}) {
    /** @type {Record<string, Array<() => void>>} */
    const listeners = {};
    return {
        styleLoaded: options.styleLoaded ?? true,
        moving: options.moving ?? false,
        isStyleLoaded() { return this.styleLoaded; },
        isMoving() { return this.moving; },
        isZooming() { return false; },
        isRotating() { return false; },
        once(event, handler) {
            (listeners[event] ??= []).push(handler);
        },
        on(event, handler) {
            (listeners[event] ??= []).push(handler);
        },
        off(event, handler) {
            listeners[event] = (listeners[event] ?? []).filter((item) => item !== handler);
        },
        emit(event) {
            for (const handler of [...(listeners[event] ?? [])]) handler();
        },
    };
}

test('isMapIdle is false while the style is still loading or the camera is moving', () => {
    const map = createIdleMap({ styleLoaded: false });
    assert.equal(isMapIdle(/** @type {never} */ (map)), false);
    map.styleLoaded = true;
    map.moving = true;
    assert.equal(isMapIdle(/** @type {never} */ (map)), false);
    map.moving = false;
    assert.equal(isMapIdle(/** @type {never} */ (map)), true);
});

test('waitForMapIdle resolves immediately when the map is already idle', async () => {
    const map = createIdleMap();
    await waitForMapIdle(/** @type {never} */ (map), 50);
});

test('waitForMapIdle resolves on the idle event', async () => {
    const map = createIdleMap({ styleLoaded: false, moving: true });
    const pending = waitForMapIdle(/** @type {never} */ (map), 200);
    map.styleLoaded = true;
    map.moving = false;
    map.emit('idle');
    await pending;
});

test('waitForMapIdle resolves on timeout when the style is already loaded', async () => {
    const map = createIdleMap({ styleLoaded: true, moving: true });
    await waitForMapIdle(/** @type {never} */ (map), 20);
});

test('waitForMapIdle rejects on timeout when the style never loads', async () => {
    const map = createIdleMap({ styleLoaded: false, moving: true });
    await assert.rejects(() => waitForMapIdle(/** @type {never} */ (map), 20), /Timed out waiting for map idle/);
});

test('waitForMapIdle ignores idle until the style is loaded', async () => {
    const map = createIdleMap({ styleLoaded: false, moving: false });
    const pending = waitForMapIdle(/** @type {never} */ (map), 200);
    map.emit('idle');
    map.styleLoaded = true;
    map.emit('idle');
    await pending;
});

test('resolveMapStyle does not return an empty style URL', () => {
    const style = resolveMapStyle({
        id: 'maptiler-satellite',
        name: 'Satellite',
        type: 'maptiler',
        client_config: { type: 'maptiler' },
    });
    assert.equal(typeof style, 'object');
    assert.equal(style.version, 8);
});

test('GPUInitializationError is remapped to a WebGL2 message', () => {
    const error = new Error('webgl failed');
    error.name = 'GPUInitializationError';
    assert.throws(() => remapMapInitError(error), /This browser cannot create a WebGL2 map context/);
});

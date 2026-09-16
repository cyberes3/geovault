import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { buildMapConstructorOptions, remapMapInitError, createTransformRequest, waitForStyleLoaded } = await import('./mapInitialization.ts');

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

test('GPUInitializationError is remapped to a WebGL2 message', () => {
    const error = new Error('webgl failed');
    error.name = 'GPUInitializationError';
    assert.throws(() => remapMapInitError(error), /This browser cannot create a WebGL2 map context/);
});

import test from 'node:test';
import assert from 'node:assert/strict';

const gvCore = { map: {}, maplibre: null };
globalThis.window = { gv_core: gvCore, maplibregl: null };

const { MapLibreLoader, getLoadedMaplibreGl, mapLibreLoader } = await import('./lazyMaplibreGl.ts');

test('getLoadedMaplibreGl throws before load', () => {
    gvCore.maplibre = null;
    assert.throws(() => getLoadedMaplibreGl(), /before loadMaplibreGl/);
});

test('publish assigns the namespace and calls setWorkerUrl', () => {
    const loader = new MapLibreLoader();
    const calls = [];
    const namespace = {
        setWorkerUrl(url) { calls.push(url); },
        Map: function Map() {},
    };
    const published = loader.publish(namespace, 'worker://maplibre');
    assert.equal(published, namespace);
    assert.equal(gvCore.maplibre, namespace);
    assert.equal(gvCore.map.maplibre, namespace);
    assert.equal(globalThis.window.maplibregl, namespace);
    assert.deepEqual(calls, ['worker://maplibre']);
    assert.equal(getLoadedMaplibreGl(), namespace);
    assert.ok(mapLibreLoader);
});

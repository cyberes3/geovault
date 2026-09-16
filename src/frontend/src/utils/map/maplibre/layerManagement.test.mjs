import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { createFakeMap } = await import('../test/FakeMap.mjs');
const { FEATURE_LAYER_STACK } = await import('../mapLayers.ts');
const { registerFeatureLayers } = await import('./layerManagement.ts');

test('no source means no feature layers', () => {
    const map = createFakeMap();
    registerFeatureLayers(map, 1);
    assert.equal(FEATURE_LAYER_STACK.every((id) => !map.getLayer(id)), true);
});

test('feature stack is registered above raster-layer', () => {
    const map = createFakeMap();
    map.addSource('geojson-data', { type: 'geojson', promoteId: 'database_id', data: { type: 'FeatureCollection', features: [] } });
    map.addLayer({ id: 'raster-layer', type: 'raster' });
    registerFeatureLayers(map, 1);
    const ids = map.getStyle().layers.map((layer) => layer.id);
    const rasterIndex = ids.indexOf('raster-layer');
    for (const layerId of FEATURE_LAYER_STACK) {
        assert.ok(ids.indexOf(layerId) > rasterIndex, `${layerId} should be above raster-layer`);
    }
    assert.deepEqual(
        ids.slice(rasterIndex + 1),
        [...FEATURE_LAYER_STACK],
    );
});

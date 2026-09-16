import test from 'node:test';
import assert from 'node:assert/strict';

globalThis.window = { location: { origin: 'http://localhost' } };

const { describeStyleInput, describeMapSnapshot, describeError } = await import('./mapBootLog.ts');

test('describeStyleInput strips query strings from style URLs', () => {
    const described = describeStyleInput('https://api.maptiler.com/maps/satellite/style.json?key=secret');
    assert.equal(described.kind, 'url');
    assert.equal(described.host, 'api.maptiler.com');
    assert.equal(described.path, '/maps/satellite/style.json');
    assert.equal('key' in described, false);
});

test('describeStyleInput reports spec source and layer ids', () => {
    const described = describeStyleInput({
        version: 8,
        sources: { 'raster-source': {} },
        layers: [{ id: 'raster-layer' }],
    });
    assert.deepEqual(described, {
        kind: 'spec',
        sourceIds: ['raster-source'],
        layerIds: ['raster-layer'],
    });
});

test('describeMapSnapshot omits camera coordinates', () => {
    const snapshot = describeMapSnapshot({
        isStyleLoaded: () => true,
        loaded: () => false,
        getZoom: () => 6,
        getStyle: () => ({ sources: { 'geojson-data': {} }, layers: [{ id: 'points' }] }),
        getSource: (id) => (id === 'geojson-data' ? {} : null),
        getCenter: () => ({ lng: 12.34, lat: 56.78 }),
    });
    const serialized = JSON.stringify(snapshot);
    assert.equal(snapshot.hasMap, true);
    assert.equal(snapshot.styleLoaded, true);
    assert.equal(snapshot.hasGeojsonSource, true);
    assert.equal(serialized.includes('12.34'), false);
    assert.equal(serialized.includes('56.78'), false);
});

test('describeError uses Error.message', () => {
    assert.equal(describeError(new Error('style failed')), 'style failed');
});

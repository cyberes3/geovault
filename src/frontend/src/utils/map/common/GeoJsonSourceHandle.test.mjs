import test from 'node:test';
import assert from 'node:assert/strict';
import { GeoJsonSourceHandle } from './GeoJsonSourceHandle.ts';
import { createFakeMap } from '../test/FakeMap.mjs';

test('GeoJsonSourceHandle flushes once per animation frame and dispose cancels', async () => {
    const callbacks = [];
    globalThis.requestAnimationFrame = (cb) => {
        callbacks.push(cb);
        return callbacks.length;
    };
    globalThis.cancelAnimationFrame = (id) => {
        callbacks[id - 1] = null;
    };

    const map = createFakeMap();
    map.addSource('geojson-data', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
    const handle = new GeoJsonSourceHandle(() => map);
    handle.setData({ type: 'FeatureCollection', features: [{ id: 1 }] });
    handle.setData({ type: 'FeatureCollection', features: [{ id: 2 }] });
    assert.equal(callbacks.length, 1);
    callbacks[0]();
    assert.equal(map.getSource('geojson-data').data.features[0].id, 2);

    handle.setData({ type: 'FeatureCollection', features: [{ id: 3 }] });
    handle.dispose();
    assert.equal(callbacks[1], null);
    assert.equal(map.getSource('geojson-data').data.features[0].id, 2);
});

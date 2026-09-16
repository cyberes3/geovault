import test from 'node:test';
import assert from 'node:assert/strict';
import { MapCamera } from './MapCamera.ts';

function fakeMap(view) {
    return {
        getCenter: () => ({ lng: view.center[0], lat: view.center[1] }),
        getZoom: () => view.zoom,
        getPitch: () => view.pitch,
        getBearing: () => view.bearing,
        jumpTo(next) {
            view.center = next.center;
            view.zoom = next.zoom;
            view.pitch = next.pitch;
            view.bearing = next.bearing;
        },
    };
}

test('MapCamera save and restore', () => {
    const view = { center: [-105, 40], zoom: 8, pitch: 15, bearing: 30 };
    const map = fakeMap(view);
    const camera = new MapCamera();
    const snapshot = camera.save(map);
    view.center = [0, 0];
    view.zoom = 1;
    camera.restore(map);
    assert.deepEqual(view, snapshot);
    camera.apply(map, { center: [1, 2], zoom: 3, pitch: 4, bearing: 5 });
    assert.deepEqual(view, { center: [1, 2], zoom: 3, pitch: 4, bearing: 5 });
});

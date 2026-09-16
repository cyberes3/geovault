import test from 'node:test';
import assert from 'node:assert/strict';
import { manualChunkName } from './vite.manual-chunks.mjs';

test('library, wrappers, and worker chunk assignment', () => {
    assert.equal(manualChunkName('/app/node_modules/maplibre-gl/dist/maplibre-gl.mjs'), 'maplibre-gl');
    assert.equal(manualChunkName('/app/src/utils/map/maplibre/lazyMaplibreGl.ts'), 'map-utils');
    assert.equal(manualChunkName('/app/node_modules/maplibre-gl/dist/maplibre-gl-worker.mjs'), undefined);
    assert.equal(manualChunkName('/app/src/utils/map/maplibre/maplibre-gl-worker-helper.ts'), undefined);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { firstPaintPath, mountWaitsForExtensions } from './BootGraph.ts';

test('first paint path comes from the hash route', () => {
    assert.equal(firstPaintPath('#/map?collection=1'), '/map');
    assert.equal(firstPaintPath(''), '/');
});

test('public share and core routes do not wait on extensions', () => {
    assert.equal(mountWaitsForExtensions('/map'), false);
    assert.equal(mountWaitsForExtensions('/mapshare'), false);
    assert.equal(mountWaitsForExtensions('/extensions/live-track/share'), false);
    assert.equal(mountWaitsForExtensions('/extensions/places'), true);
});

test('main.ts does not statically import MapLibre helpers or code-editor CSS', () => {
    const mainPath = join(dirname(fileURLToPath(import.meta.url)), '../../main.ts');
    const source = readFileSync(mainPath, 'utf8');
    assert.equal(source.includes("import 'simple-code-editor"), false);
    assert.equal(source.includes("from '@/utils/map/common/index"), false);
    assert.equal(/import (?!type )[^;\n]*locationMarker/.test(source), false);
    assert.equal(source.includes("await import('@/utils/map/maplibre/locationMarker"), true);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const { window } = new JSDOM('<!DOCTYPE html><canvas></canvas>');
Object.defineProperty(globalThis, 'window', { value: window, configurable: true });
Object.defineProperty(globalThis, 'document', { value: window.document, configurable: true });

class ImmediateImage {
    constructor() {
        this.onload = null;
        this.onerror = null;
        this.crossOrigin = '';
    }
    set src(_value) {
        const canvas = document.createElement('canvas');
        canvas.getContext = () => ({
            drawImage() {},
            getImageData() { return { width: 20, height: 20, data: new Uint8ClampedArray(20 * 20 * 4) }; },
        });
        document.createElement = (tag) => (tag === 'canvas' ? canvas : window.document.createElement(tag));
        this.onload?.();
    }
}
Object.defineProperty(globalThis, 'Image', { value: ImmediateImage, configurable: true });

const { createFakeMap } = await import('./test/FakeMap.mjs');
const { FeatureSource } = await import('./common/FeatureSource.ts');
const { FeatureIconResolver } = await import('./FeatureIconResolver.ts');

test('non-icon ids are ignored immediately', () => {
    const map = createFakeMap();
    const resolver = new FeatureIconResolver(new FeatureSource());
    resolver.attach(map);
    const result = resolver.resolve(map, 'sprite-missing');
    assert.equal(result, undefined);
    assert.equal(map.images.size, 0);
});

test('addImage happens before the resolver promise settles', async () => {
    const map = createFakeMap();
    const source = new FeatureSource();
    source.upsert([{
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [1, 2] },
        properties: { database_id: 'p1', icon: '/api/icons/system/dot.png' },
    }]);
    source.setRuntime('p1', { iconId: 'icon-test' });
    const resolver = new FeatureIconResolver(source);
    let settled = false;
    const pending = Promise.resolve(resolver.resolve(map, 'icon-test')).then(() => {
        settled = true;
    });
    assert.equal(map.hasImage('icon-test'), true);
    assert.equal(settled, false);
    await pending;
    assert.equal(settled, true);
});

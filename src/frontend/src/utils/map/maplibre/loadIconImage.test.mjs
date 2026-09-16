import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createFakeMap } from '../test/FakeMap.mjs';

const { window } = new JSDOM('<!DOCTYPE html>');
Object.defineProperty(globalThis, 'window', { value: window, configurable: true });
Object.defineProperty(globalThis, 'document', { value: window.document, configurable: true });

if (typeof document.createElement('canvas').getContext !== 'function') {
    test('JSDOM skip when canvas is unavailable', () => {
        assert.ok(true);
    });
}

const addedSizes = [];
class TestImage {
    constructor() {
        this.onload = null;
        this.onerror = null;
        this.crossOrigin = '';
        this._src = '';
    }
    set src(value) {
        this._src = value;
        if (String(value).includes('error')) {
            queueMicrotask(() => this.onerror?.(new Error('fail')));
            return;
        }
        queueMicrotask(() => this.onload?.());
    }
}

Object.defineProperty(globalThis, 'Image', { value: TestImage, configurable: true });

const originalCreate = document.createElement.bind(document);
document.createElement = (tag) => {
    const el = originalCreate(tag);
    if (tag === 'canvas') {
        el.getContext = () => ({
            drawImage() {},
            getImageData(_x, _y, width, height) {
                addedSizes.push({ width, height });
                return { width, height, data: new Uint8ClampedArray(width * height * 4) };
            },
        });
    }
    return el;
};

const { loadIconImage } = await import('./featureLayerSpec.ts');

test('loadIconImage draws 20x20 and treats duplicates as success', async () => {
    const map = createFakeMap();
    await loadIconImage(map, 'icon-a', 'https://example.test/a.png');
    assert.deepEqual(addedSizes.at(-1), { width: 20, height: 20 });
    await loadIconImage(map, 'icon-a', 'https://example.test/a.png');
    assert.equal(map.hasImage('icon-a'), true);
});

test('loadIconImage rejects an error URL', async () => {
    const map = createFakeMap();
    await assert.rejects(() => loadIconImage(map, 'icon-b', 'https://example.test/error.png'));
});

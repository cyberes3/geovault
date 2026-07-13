import test from 'node:test';
import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { httpClient } from '../httpClient.ts';
import { listExtensions, clearExtensionsCache } from './extensionsApi.ts';

test.beforeEach(() => {
  clearExtensionsCache();
});

test.after(() => {
  mock.restoreAll();
});

test('listExtensions only hits the network once across repeated sequential calls', async () => {
  const get = mock.method(httpClient, 'get', () => Promise.resolve({ data: [{ name: 'places' }] }));

  const first = await listExtensions();
  const second = await listExtensions();

  assert.equal(get.mock.callCount(), 1);
  assert.deepEqual(first, [{ name: 'places' }]);
  assert.deepEqual(second, [{ name: 'places' }]);
  get.mock.restore();
});

test('listExtensions only hits the network once across overlapping concurrent calls', async () => {
  let resolveRequest;
  const get = mock.method(httpClient, 'get', () => new Promise((resolve) => {
    resolveRequest = resolve;
  }));

  const call1 = listExtensions();
  const call2 = listExtensions();
  const call3 = listExtensions();

  resolveRequest({ data: [{ name: 'caltopo' }] });
  const results = await Promise.all([call1, call2, call3]);

  assert.equal(get.mock.callCount(), 1);
  for (const result of results) {
    assert.deepEqual(result, [{ name: 'caltopo' }]);
  }
  get.mock.restore();
});

test('listExtensions caches an empty list (and does not retry) after a failed request', async () => {
  const get = mock.method(httpClient, 'get', () => Promise.reject(new Error('network down')));

  const first = await listExtensions();
  const second = await listExtensions();

  assert.deepEqual(first, []);
  assert.deepEqual(second, []);
  assert.equal(get.mock.callCount(), 1);
  get.mock.restore();
});

test('clearExtensionsCache forces the next call to hit the network again', async () => {
  const get = mock.method(httpClient, 'get', () => Promise.resolve({ data: [{ name: 'places' }] }));

  await listExtensions();
  clearExtensionsCache();
  await listExtensions();

  assert.equal(get.mock.callCount(), 2);
  get.mock.restore();
});

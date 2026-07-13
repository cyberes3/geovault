import test from 'node:test';
import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { createScopedRouter, createScopedRegistry, prefetchExtensions } from './extensionLoader.ts';

test('createScopedRouter prefixes route paths with the extension prefix', () => {
  const router = { addRoute: mock.fn(), push: mock.fn() };
  const scoped = createScopedRouter(router, '/extensions/my-ext');

  scoped.addRoute({ path: '/settings', name: 'settings' });

  assert.equal(router.addRoute.mock.callCount(), 1);
  assert.equal(router.addRoute.mock.calls[0].arguments[0].path, '/extensions/my-ext/settings');
});

test('createScopedRouter treats an empty/root path as just the prefix', () => {
  const router = { addRoute: mock.fn(), push: mock.fn() };
  const scoped = createScopedRouter(router, '/extensions/my-ext');

  scoped.addRoute({ path: '', name: 'root' });

  assert.equal(router.addRoute.mock.calls[0].arguments[0].path, '/extensions/my-ext');
});

test('createScopedRouter.navigate pushes the prefixed path onto the main router', () => {
  const router = { addRoute: mock.fn(), push: mock.fn() };
  const scoped = createScopedRouter(router, '/extensions/my-ext');

  scoped.navigate('/settings');

  assert.equal(router.push.mock.callCount(), 1);
  assert.equal(router.push.mock.calls[0].arguments[0], '/extensions/my-ext/settings');
});

test('createScopedRegistry stamps fullPath with the extension prefix for nav links and tools', () => {
  const registry = { registerNavLink: mock.fn(), registerSettingsTab: mock.fn(), registerTool: mock.fn() };
  const scoped = createScopedRegistry(registry, '/extensions/my-ext');

  scoped.registerNavLink({ label: 'My Ext', path: '/home' });
  scoped.registerTool({ label: 'Do Thing', path: '/do-thing' });

  assert.equal(registry.registerNavLink.mock.calls[0].arguments[0].fullPath, '/extensions/my-ext/home');
  assert.equal(registry.registerTool.mock.calls[0].arguments[0].fullPath, '/extensions/my-ext/do-thing');
});

test('createScopedRegistry.registerSettingsTab passes the tab through unscoped', () => {
  const registry = { registerNavLink: mock.fn(), registerSettingsTab: mock.fn(), registerTool: mock.fn() };
  const scoped = createScopedRegistry(registry, '/extensions/my-ext');
  const tab = { id: 'my-ext', label: 'My Ext', component: {} };

  scoped.registerSettingsTab(tab);

  assert.equal(registry.registerSettingsTab.mock.callCount(), 1);
  assert.equal(registry.registerSettingsTab.mock.calls[0].arguments[0], tab);
});

test('prefetchExtensions fires every bundle/icon fetch immediately instead of waiting on prior extensions', () => {
  const list = [
    { name: 'places', frontend_entry: 'places.js' },
    { name: 'caltopo', frontend_entry: 'caltopo.js' },
    { name: 'live_track', frontend_entry: 'live_track.js' }
  ];
  const importModule = mock.fn(() => new Promise(() => {})); // never resolves
  const resolveIcon = mock.fn(() => new Promise(() => {}));

  prefetchExtensions(list, { importModule, resolveIcon });

  // All three should have been *started* synchronously, before anything resolves - if this
  // were still sequential, only the first extension's fetch would have been kicked off yet.
  assert.equal(importModule.mock.callCount(), 3);
  assert.equal(resolveIcon.mock.callCount(), 3);
  assert.deepEqual(importModule.mock.calls.map((c) => c.arguments[0]), ['places.js', 'caltopo.js', 'live_track.js']);
});

test('prefetchExtensions skips extensions with no frontend_entry entirely', () => {
  const list = [
    { name: 'disabled_ext' },
    { name: 'places', frontend_entry: 'places.js' }
  ];
  const importModule = mock.fn(() => Promise.resolve({}));
  const resolveIcon = mock.fn(() => Promise.resolve(null));

  const prefetches = prefetchExtensions(list, { importModule, resolveIcon });

  assert.equal(prefetches.size, 1);
  assert.equal(importModule.mock.callCount(), 1);
  assert.ok(prefetches.has(list[1]));
  assert.ok(!prefetches.has(list[0]));
});

test('prefetchExtensions settles a rejected bundle fetch instead of rejecting, and does not affect other extensions', async () => {
  const list = [
    { name: 'broken_ext', frontend_entry: 'broken.js' },
    { name: 'places', frontend_entry: 'places.js' }
  ];
  const importModule = mock.fn((entry) =>
    entry === 'broken.js' ? Promise.reject(new Error('network error')) : Promise.resolve({ default: () => {} })
  );
  const resolveIcon = mock.fn(() => Promise.resolve(null));

  const prefetches = prefetchExtensions(list, { importModule, resolveIcon });

  const broken = await prefetches.get(list[0]);
  const places = await prefetches.get(list[1]);

  assert.equal(broken.module.status, 'rejected');
  assert.equal(broken.module.reason.message, 'network error');
  assert.equal(places.module.status, 'fulfilled');
});

test('prefetchExtensions preserves setup order guarantees: results are keyed by extension so a slow-resolving fetch does not block reading earlier-resolved ones out of list order', async () => {
  const list = [
    { name: 'slow_ext', frontend_entry: 'slow.js' },
    { name: 'fast_ext', frontend_entry: 'fast.js' }
  ];
  const resolveOrder = [];
  let resolveSlow;
  const importModule = mock.fn((entry) => {
    if (entry === 'slow.js') {
      return new Promise((resolve) => {
        resolveSlow = () => { resolveOrder.push('slow'); resolve({}); };
      });
    }
    resolveOrder.push('fast');
    return Promise.resolve({});
  });
  const resolveIcon = mock.fn(() => Promise.resolve(null));

  const prefetches = prefetchExtensions(list, { importModule, resolveIcon });
  resolveSlow();

  // The consuming loop in loadExtensions() iterates `list` in original order and `await`s each
  // extension's own prefetch promise - so even though `fast_ext`'s underlying fetch settles
  // first, processing still waits for `slow_ext` (list[0]) before moving on, preserving order.
  const slow = await prefetches.get(list[0]);
  const fast = await prefetches.get(list[1]);

  assert.deepEqual(resolveOrder, ['fast', 'slow']);
  assert.equal(slow.ext.name, 'slow_ext');
  assert.equal(fast.ext.name, 'fast_ext');
});

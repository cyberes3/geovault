import test from 'node:test';
import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { createScopedRouter, createScopedRegistry } from './extensionLoader.ts';

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

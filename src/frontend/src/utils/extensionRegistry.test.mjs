import test from 'node:test';
import assert from 'node:assert/strict';
import { extensionRegistry } from './extensionRegistry.js';

test.beforeEach(() => {
  extensionRegistry.navLinks.length = 0;
  extensionRegistry.tools.length = 0;
  extensionRegistry.settingsTabs.length = 0;
});

test('registerNavLink adds the link to navLinks', () => {
  extensionRegistry.registerNavLink({ label: 'My Ext', fullPath: '/extensions/my-ext' });
  assert.equal(extensionRegistry.navLinks.length, 1);
  assert.equal(extensionRegistry.navLinks[0].label, 'My Ext');
});

test('registerTool adds the tool to tools', () => {
  extensionRegistry.registerTool({ label: 'Geotagger', fullPath: '/extensions/exif-geotagger' });
  assert.equal(extensionRegistry.tools.length, 1);
  assert.equal(extensionRegistry.tools[0].label, 'Geotagger');
});

test('registerSettingsTab adds the tab to settingsTabs', () => {
  extensionRegistry.registerSettingsTab({ id: 'my-ext', label: 'My Ext', component: { render() {} } });
  assert.equal(extensionRegistry.settingsTabs.length, 1);
  assert.equal(extensionRegistry.settingsTabs[0].id, 'my-ext');
});

test('registerRoutes no longer exists (routing goes through the scoped router, not the registry)', () => {
  assert.equal('registerRoutes' in extensionRegistry, false);
  assert.equal('routes' in extensionRegistry, false);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveExtensionIcon } from './resolveExtensionIcon.ts';

test('returns null when the extension has no icon', async () => {
  assert.equal(await resolveExtensionIcon(null, 'my-ext'), null);
  assert.equal(await resolveExtensionIcon(undefined, 'my-ext'), null);
  assert.equal(await resolveExtensionIcon('', 'my-ext'), null);
});

test('resolves a known heroicon name to a component', async () => {
  const icon = await resolveExtensionIcon('MapIcon', 'my-ext');
  assert.notEqual(icon, null);
});

test('returns null for an unknown heroicon-like name', async () => {
  const icon = await resolveExtensionIcon('NotARealIconName', 'my-ext');
  assert.equal(icon, null);
});

test('resolves an inline <svg> string to a component', async () => {
  const icon = await resolveExtensionIcon('<svg viewBox="0 0 24 24"><path d="M0 0"/></svg>', 'my-ext');
  assert.notEqual(icon, null);
  assert.equal(typeof icon, 'object');
});

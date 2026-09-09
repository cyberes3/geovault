import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

test('setup is the default export and wraps routes with a name', () => {
  const src = readFileSync(join(here, 'main.ts'), 'utf8');
  assert.match(src, /export default setup/);
  assert.match(src, /createRouteWrapper/);
  assert.match(src, /routeName/);
  assert.doesNotMatch(src, /csrf_exempt/);
});

test('example page lists scoped features through the extension API', () => {
  const src = readFileSync(join(here, 'ExamplePage.vue'), 'utf8');
  assert.match(src, /api\.get\('\/features\/'\)/);
  assert.doesNotMatch(src, /\/api\/features\/all\//);
  assert.doesNotMatch(src, /getCookie/);
});

test('example settings write only through namespaced gv_core.settings', () => {
  const src = readFileSync(join(here, 'ExampleSettings.vue'), 'utf8');
  assert.match(src, /gv_core\.settings\.useExtensionSettings/);
  assert.match(src, /example_extension/);
  assert.doesNotMatch(src, /saveUserSetting/);
});

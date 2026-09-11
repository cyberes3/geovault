import test from 'node:test';
import assert from 'node:assert/strict';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createRouteWrapper } from './routeWrapper.ts';

test('names the wrapper after the extension so errors are easy to trace', () => {
  const api = { kebabName: 'live-track' };
  const wrapper = createRouteWrapper({}, { api });

  assert.equal(wrapper.name, 'ExtensionBoundary_live-track_default');
  assert.equal(typeof wrapper.setup, 'function');
});

test('derives the wrapper name straight from api.kebabName for a different extension', () => {
  const wrapper = createRouteWrapper({}, { api: { kebabName: 'places' } });

  assert.equal(wrapper.name, 'ExtensionBoundary_places_default');
});

test('wrapper root fills the map-route height chain', async () => {
  const wrapper = createRouteWrapper({ render: () => h('span') }, { api: { kebabName: 'places' } });
  const html = await renderToString(createSSRApp(wrapper));

  assert.match(html, /gv-ext gv-ext-places flex flex-col flex-1 min-h-0 h-full w-full/);
});

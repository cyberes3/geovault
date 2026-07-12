import test from 'node:test';
import assert from 'node:assert/strict';
import { createRouteWrapper } from './routeWrapper.ts';

test('names the wrapper after the extension so errors are easy to trace', () => {
  const api = { kebabName: 'live-track' };
  const wrapper = createRouteWrapper({}, { api });

  assert.equal(wrapper.name, 'ExtensionBoundary_live-track');
  assert.equal(typeof wrapper.setup, 'function');
});

test('derives the wrapper name straight from api.kebabName for a different extension', () => {
  const wrapper = createRouteWrapper({}, { api: { kebabName: 'places' } });

  assert.equal(wrapper.name, 'ExtensionBoundary_places');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { mock } from 'node:test';
import { createPlatformStateBridge } from './platformState.ts';

function createMockStore(userSettings) {
  return {
    getters: { 'userSettings/userSettings': userSettings },
    dispatch: mock.fn(async () => {})
  };
}

test('userSettings reflects the store getter', () => {
  const store = createMockStore({ map: { basemap: 'osm' } });
  const bridge = createPlatformStateBridge(store);

  assert.deepEqual(bridge.userSettings.value, { map: { basemap: 'osm' } });
});

test('userSettings is null before settings have loaded', () => {
  const store = createMockStore(null);
  const bridge = createPlatformStateBridge(store);

  assert.equal(bridge.userSettings.value, null);
});

test('fetchUserSettings dispatches the userSettings/fetchUserSettings action', async () => {
  const store = createMockStore(null);
  const bridge = createPlatformStateBridge(store);

  await bridge.fetchUserSettings();

  assert.equal(store.dispatch.mock.callCount(), 1);
  assert.equal(store.dispatch.mock.calls[0].arguments[0], 'userSettings/fetchUserSettings');
});

test('the bridge exposes exactly userSettings/fetchUserSettings/saveUserSetting, not the raw store', () => {
  const store = createMockStore(null);
  const bridge = createPlatformStateBridge(store);

  assert.deepEqual(Object.keys(bridge).sort(), ['fetchUserSettings', 'saveUserSetting', 'userSettings']);
  assert.equal(bridge.dispatch, undefined);
  assert.equal(bridge.commit, undefined);
  assert.equal(bridge.state, undefined);
});

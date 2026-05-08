import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildTrackerSettingsPayloadFromSnapshot,
} from '../src/settingsPayloadBuilders.js';

test('buildTrackerSettingsPayloadFromSnapshot preserves every recent data filter option', () => {
  for (const option of ['', '1min', '1h', '1d', '1w', '1m', 'session', 'current_session']) {
    const payload = buildTrackerSettingsPayloadFromSnapshot({
      name: 'Tracker',
      color: '#6C93DE',
      recentDataWindow: option,
      visibility: 'private',
    });

    assert.equal(payload.recent_data_window, option || null);
  }
});

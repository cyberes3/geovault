import test from 'node:test';
import assert from 'node:assert/strict';
import {
  didRecentDataWindowChange,
  shouldReloadGeometryForSettingsChange
} from '../src/settingsChangePolicy.js';

test('didRecentDataWindowChange returns true when recent_data_window changed', () => {
  const previous = { recentDataWindow: '1h' };
  const current = { recentDataWindow: 'session' };
  assert.equal(didRecentDataWindowChange(previous, current), true);
});

test('didRecentDataWindowChange returns false when unchanged', () => {
  const previous = { recentDataWindow: 'session' };
  const current = { recentDataWindow: 'session' };
  assert.equal(didRecentDataWindowChange(previous, current), false);
});

test('shouldReloadGeometryForSettingsChange allows selected tracker refresh', () => {
  assert.equal(
    shouldReloadGeometryForSettingsChange(true, 'tracker-1', 'tracker-1'),
    true
  );
});

test('shouldReloadGeometryForSettingsChange blocks non-selected tracker refresh', () => {
  assert.equal(
    shouldReloadGeometryForSettingsChange(true, 'tracker-2', 'tracker-1'),
    false
  );
});

test('shouldReloadGeometryForSettingsChange blocks reload when refresh flag is false', () => {
  assert.equal(
    shouldReloadGeometryForSettingsChange(false, 'tracker-1', 'tracker-1'),
    false
  );
});

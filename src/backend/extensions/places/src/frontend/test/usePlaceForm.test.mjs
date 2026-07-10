import test from 'node:test';
import assert from 'node:assert/strict';

function hasAddressLikeLetters(str) {
  return /[a-zA-Z]/.test(str.replace(/[nsewd]/gi, ''));
}

function isDirty(snapshot, current) {
  return current.name !== snapshot.name
    || current.description !== snapshot.description
    || current.lat !== snapshot.lat
    || current.lon !== snapshot.lon
    || (current.address || '') !== (snapshot.address || '');
}

test('hasAddressLikeLetters ignores coordinate direction letters', () => {
  assert.equal(hasAddressLikeLetters('20.5 N, 10.5 W'), false);
  assert.equal(hasAddressLikeLetters('Example City, ST'), true);
});

test('isDirty detects changed coordinates and address', () => {
  const snapshot = { name: 'A', description: '', lat: 1, lon: 2, address: null };
  assert.equal(isDirty(snapshot, { ...snapshot }), false);
  assert.equal(isDirty(snapshot, { ...snapshot, lat: 3 }), true);
  assert.equal(isDirty(snapshot, { ...snapshot, address: 'Example Street' }), true);
});

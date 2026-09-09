import test from 'node:test';
import assert from 'node:assert/strict';
import { buildStatusUpdateFields, isTerminalStatus } from './jobStatusHelpers.ts';

test('isTerminalStatus treats completed, failed, and canceled as terminal', () => {
    assert.equal(isTerminalStatus('completed'), true);
    assert.equal(isTerminalStatus('failed'), true);
    assert.equal(isTerminalStatus('canceled'), true);
    assert.equal(isTerminalStatus('processing'), false);
    assert.equal(isTerminalStatus('queued'), false);
});

test('buildStatusUpdateFields clears queued/processing for canceled', () => {
    assert.deepEqual(buildStatusUpdateFields('canceled'), {
        processing: false,
        processing_failed: false,
        queued: false,
    });
});

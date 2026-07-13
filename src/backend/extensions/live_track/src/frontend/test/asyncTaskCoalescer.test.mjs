import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCoalescedTask } from '../src/asyncTaskCoalescer.js';

/** Manually-flushed scheduler so tests can control exactly when the coalesced task runs. */
function createManualScheduler() {
  const queued = [];
  const schedule = (cb) => queued.push(cb);
  const flush = () => {
    const toRun = queued.splice(0, queued.length);
    for (const cb of toRun) cb();
  };
  return { schedule, flush };
}

test('runs the task once for a single call', async () => {
  const { schedule, flush } = createManualScheduler();
  let calls = 0;
  const requestRun = createCoalescedTask(() => { calls += 1; }, schedule);

  const p = requestRun();
  flush();
  await p;

  assert.equal(calls, 1);
});

test('coalesces multiple calls before the scheduler flushes into one execution', async () => {
  const { schedule, flush } = createManualScheduler();
  let calls = 0;
  const requestRun = createCoalescedTask(() => { calls += 1; }, schedule);

  const p1 = requestRun();
  const p2 = requestRun();
  const p3 = requestRun();
  flush();
  await Promise.all([p1, p2, p3]);

  assert.equal(calls, 1, 'three calls within the same frame should only run the task once');
});

test('a call after the previous run has settled schedules a new execution', async () => {
  const { schedule, flush } = createManualScheduler();
  let calls = 0;
  const requestRun = createCoalescedTask(() => { calls += 1; }, schedule);

  const p1 = requestRun();
  flush();
  await p1;

  const p2 = requestRun();
  flush();
  await p2;

  assert.equal(calls, 2);
});

test('all coalesced callers resolve once the shared execution completes', async () => {
  const { schedule, flush } = createManualScheduler();
  const requestRun = createCoalescedTask(async () => {
    await Promise.resolve();
    return 'done';
  }, schedule);

  const p1 = requestRun();
  const p2 = requestRun();
  flush();
  const [r1, r2] = await Promise.all([p1, p2]);

  assert.equal(r1, 'done');
  assert.equal(r2, 'done');
});

test('propagates a rejection from the task to every coalesced caller', async () => {
  const { schedule, flush } = createManualScheduler();
  const requestRun = createCoalescedTask(() => {
    throw new Error('boom');
  }, schedule);

  const p1 = requestRun();
  const p2 = requestRun();
  flush();

  await assert.rejects(p1, /boom/);
  await assert.rejects(p2, /boom/);
});

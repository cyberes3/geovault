/**
 * Coalesces bursts of calls into a single execution of `taskFn`, scheduled via `schedule`
 * (default: `requestAnimationFrame`). Used to throttle expensive work triggered by
 * high-frequency events - e.g. LiveTrackView's per-GPS-tick map redraw, where many
 * `track_updated` socket events arriving within the same frame should collapse into one
 * `setData()`/layer rebuild instead of running once per event.
 *
 * All callers of the returned function share the same in-flight promise until it settles, so
 * `await requestUpdate()` from any caller resolves once the single coalesced execution
 * completes - not once per call.
 *
 * `schedule` defaults to `requestAnimationFrame` (falls back to a macrotask when unavailable,
 * e.g. in tests/SSR). Tests can inject a synchronous or manually-flushed scheduler to make
 * coalescing behavior deterministic.
 */
export function createCoalescedTask(taskFn: () => Promise<void> | void, schedule: (cb: () => void) => void = defaultSchedule): () => Promise<void> {
  let pending: Promise<void> | null = null;

  return function requestRun(): Promise<void> {
    pending ??= new Promise((resolve, reject) => {
      schedule(() => {
        pending = null;
        Promise.resolve()
          .then(() => taskFn())
          .then(resolve, reject);
      });
    });
    return pending;
  };
}

function defaultSchedule(cb: () => void): void {
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(cb);
  } else {
    setTimeout(cb, 0);
  }
}

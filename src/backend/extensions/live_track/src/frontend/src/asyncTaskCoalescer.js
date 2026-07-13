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
 * @param {() => (Promise<void> | void)} taskFn
 * @param {(cb: () => void) => void} [schedule] - defaults to `requestAnimationFrame` (falls
 *   back to a macrotask when unavailable, e.g. in tests/SSR). Tests can inject a synchronous
 *   or manually-flushed scheduler to make coalescing behavior deterministic.
 * @returns {() => Promise<void>} requestRun
 */
export function createCoalescedTask(taskFn, schedule = defaultSchedule) {
  let pending = null;

  return function requestRun() {
    if (!pending) {
      pending = new Promise((resolve, reject) => {
        schedule(() => {
          pending = null;
          Promise.resolve()
            .then(() => taskFn())
            .then(resolve, reject);
        });
      });
    }
    return pending;
  };
}

function defaultSchedule(cb) {
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(cb);
  } else {
    setTimeout(cb, 0);
  }
}

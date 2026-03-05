# GPSLogger Custom URL and out-of-order / old points

## Why you see 2‑hour‑old (or out-of-order) points with “Discard offline locations” on

We only accept points that are **strictly after** the last stored point. If the client sends an older point, we return **400** and reject it. This doc explains why GPSLogger can still send old points even with “Discard offline locations” enabled.

### 1. Live Custom URL uses WorkManager (deferred, out-of-order)

- **CustomUrlLogger** (when a point is logged) does **not** send the HTTP request immediately.
- It calls **CustomUrlManager.sendByHttp()**, which enqueues **one WorkManager job per point** (`CustomUrlWorker`).
- **Systems.startWorkManagerRequest()** uses:
  - **1 second initial delay** before the job runs
  - **enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, workRequest)**  
    The tag is `hashCode(serializedRequest)`, so each point has a **different** tag → many independent jobs.
- WorkManager runs jobs when constraints (e.g. network) are met. **Order of execution is not guaranteed.** So a newer point’s job can run before an older point’s job.

So “Discard offline locations” only prevents **enqueueing** when there’s no network at **log time**. It does **not** guarantee that jobs run in timestamp order or that an old job won’t run much later.

### 2. Retries treat 4xx as transient (including our 400)

- **CustomUrlWorker** treats **any** non‑2xx as failure:
  - `if (response.isSuccessful())` → success  
  - else → `success = false`, then:
- **Result.retry()** is returned (up to **3** times) with **exponential backoff** (30s, 60s, 120s…).
- So when we return **400** (“Point time must be after the last point”), GPSLogger **retries the same request** later. That’s why you see the same old point (and the same 400) repeatedly.

So:
- An old point’s job runs (perhaps after delay or after a previous failure).
- We reject it with 400.
- GPSLogger retries that **same** old point again (and again), which keeps producing 400s.

### 3. Why a point can be “2 hours old”

- The point was **logged and enqueued** when network was available (discard offline doesn’t block it).
- The job didn’t run immediately (initial delay, Doze, or queue backlog).
- When it first ran, it may have **failed** (timeout, 5xx, or an earlier 400). Worker returns **Result.retry()**.
- Backoff and WorkManager scheduling can run the retry **much later** (e.g. 30s, 60s, 120s, or after the app is used again).
- In the meantime, **newer** points were enqueued and **succeeded**. So when the old job runs (or runs again), our track already has a newer last point → we reject the old one with 400.

So “2 hour old” is the **point’s timestamp**; the **job** for that point may have been enqueued hours ago and only run (or retry) now.

### Summary

| What | Behavior |
|------|----------|
| **Discard offline locations** | Only skips **enqueueing** when there’s no network at log time. Does not reorder or cancel already-enqueued jobs. |
| **WorkManager** | One job per point; 1s delay; order of execution not guaranteed; jobs can run long after enqueue. |
| **Retries** | Any non‑2xx (including 400) → up to 3 retries with exponential backoff. So our 400 causes the same old point to be sent again. |

So we are **not** doing something wrong. Rejecting out-of-order points is correct. The behavior comes from GPSLogger’s design: deferred WorkManager jobs and retrying on 4xx.

### Possible improvements (in GPSLogger)

- **Do not retry on 4xx** (client error): e.g. in `CustomUrlWorker`, treat 4xx as `Result.failure()` so the same request is not retried.
- **Optionally send live Custom URL synchronously** (no WorkManager) so each point is sent immediately in order; only use WorkManager for batch uploads. That would require a code change in GPSLogger (e.g. in `CustomUrlManager.sendByHttp` or `CustomUrlLogger`).

Until then, our 400 and log message are correct; the repeated 400s are expected when the app retries the same out-of-order point.

# TrackingService Cleanup Notes

`TrackingService.kt` stays monolithic for now because the current tracking behavior is working and a broad split would add unnecessary risk. The next cleanup pass should be deliberate and preserve the strict filter plus service-liveness separation.

## Required Safety Net

Before decomposing or rewriting this file, add characterization tests that lock down current service behavior:

- Start, stop, restart, and foreground reshow command handling.
- Provider disabled/restored transitions, including paused provider state.
- Location ingest outcomes: committed point, held fix, rejected fix, snap/internal accept, fallback candidate, and recovery probe commit.
- Paused freshness probe scheduling, timeout, poor-accuracy handling, and anchored persistence.
- Low-accuracy fallback preservation across pause/resume/provider transitions.
- Recovery anchor restore and local freshness restore after process restart.
- Upload success/failure effects on runtime snapshot, local freshness, upload freshness, and notification counts.
- UI/runtime status projection for `Tracking`, `Locking`, `Paused`, and `Waiting for GPS`.

Prefer tests through stable seams: intents, runtime state store, persisted state, policy/controller collaborators, and Robolectric service flows for the highest-risk lifecycle paths. These tests should protect behavior, not incidental private method structure.

## Later Cleanup Targets

- Provider health and request lifecycle:
  - Own provider enabled/disabled transitions, callback silence, request generation, request reapply backoff, and preflight checks in one controller.
  - Keep Android request side effects in the service.

- Stationary region and paused freshness:
  - Move paused-state anchor, radius, probe cadence, timeout, and provider-deferral state into a small region controller/value object.
  - Keep `StationaryPauseEligibilityPolicy` as the final sleep gate.

- Recovery and fallback liveness:
  - Keep filter physics authoritative.
  - Keep recovery/fallback as probe, wait, and anchored-commit decisions rather than one-off service branches.

- Diagnostics:
  - Replace inline telemetry string assembly with typed event builders.
  - Maintain concise kebab-case event names and capture-log-friendly details.

- Runtime snapshot projection:
  - Keep UI state derived from a compact runtime snapshot: GPS state, provider health, local freshness, upload freshness, recovery probe state, and stationary region state.

## Guardrails

- Do not loosen `LocationFilter`, `MovementCandidateGate`, `ResumeAnchorGate`, or speed-cap behavior.
- Do not rewrite the service around a large framework abstraction.
- Do not change UI layout as part of this cleanup.
- Do not add destructive database migrations.

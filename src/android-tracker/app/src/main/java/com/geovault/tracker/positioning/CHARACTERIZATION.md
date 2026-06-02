# Positioning Characterization Safety Net

Regression tests for behaviors that must not change when refactoring `positioning/`. Prefer stable public seams (policies, FSM, intent routing, snapshot projection)—not reflection on subsystem internals.

## Required coverage

| Area | What to lock | Test home |
|------|----------------|-----------|
| Commands | Every `TrackingServiceIntents.StartupCommandPath` and foreground-promotion rules | `TrackingServiceCharacterizationTest` |
| Provider / GPS FSM | `GpsRuntimeStateMachine` transitions (pause, provider wait, fallback) | `positioning/config/GpsRuntimeStateMachineTest` |
| Fix delivery | `LocationRequestController.expectsActiveFixDelivery` across collecting / paused / fallback / waiting | `TrackingServiceCharacterizationTest` |
| Ingest gate | `TrackingRuntimeOrchestrator.shouldProcessLocationUpdate` when paused, waiting, or bypassed | `TrackingRuntimeOrchestratorTest` |
| Paused freshness | Stationary region, probe timeout, sparse intervals | `location/StationaryFreshnessCoordinatorTest` |
| Fallback persistence | First fallback point without a previous accept | `TrackingServiceFallbackPersistenceTest` |
| Recovery anchor | `RecoveryAnchorStore` save/load/clear round-trip | `positioning/RecoveryAnchorRestartTest` |
| Upload liveness | Failure posture, skipped results, success timestamps | `UploadLivenessStateTest`, `TrackingServiceUploadCharacterizationTest` |
| Upload → snapshot timestamps | `lastPointSentAtMs` only advances when visible rows were sent | `QueueUploadOutcomePolicyTest` (wired from `UploadSubsystem.applyQueueUploadResult`) |
| Runtime store projection | `gpsCollecting` on shared runtime state | `TrackingRuntimeStateStoreTest` |
| UI status | `TrackingUiStatusResolver` from runtime snapshot fields | `TrackingUiStatusResolverTest` |
| Layer boundary | No `com.geovault.common.maps` imports under `positioning/` | `PositioningLayerMapsImportTest` |
| Session state resets | `resetForStart` / `resetForStop` clear upload, boundaries, adaptive, and job handles | `PositioningSessionStateResetTest` |

## Manual smoke (release checklist)

1. Start / stop tracking from UI and notification.
2. Auto-pause for motion, resume on movement / sig-motion.
3. Toggle GPS provider off and on while recording.
4. Manual send point while recording.
5. Background location wakeup intent while tracking.
6. Fast GPS lock window after poor-accuracy reject.
7. Low-accuracy fallback timer emit.
8. Map puck does not drive collection (recording independent of map).

## Future (optional)

- Robolectric harness: minimal `PositioningRuntime` START → fix → STOP with fake `LocationSessionCoordinator`.

## Out of scope

- Map presentation (`com.geovault.common.maps.*` must not appear under `positioning/`).
- Destructive DB migrations or loosening filter thresholds without tests.

# Positioning Characterization Safety Net

Regression tests for behaviors that must not change when refactoring `positioning/`. Prefer stable public seams (policies, FSM, intent routing, snapshot projection)—not reflection on subsystem internals.

## Required coverage

| Area | What to lock | Test home |
|------|----------------|-----------|
| Commands | Every `TrackingServiceIntents.StartupCommandPath` and foreground-promotion rules | `TrackingServiceCharacterizationTest` |
| Provider / GPS FSM | `GpsRuntimeStateMachine` transitions (pause, provider wait, fallback) | `positioning/config/GpsRuntimeStateMachineTest` |
| Runtime provider projection | Collecting vs paused vs waiting affects fix-delivery expectation | `PositioningRuntimeProviderCharacterizationTest` |
| Ingest gate | `TrackingRuntimeOrchestrator.shouldProcessLocationUpdate` when paused / bypassed | `FixIngestCharacterizationTest`, `TrackingRuntimeOrchestratorTest` |
| Paused freshness | Stationary region + probe eligibility while GPS paused | `PausedFreshnessCharacterizationTest` |
| Fallback + pause | Fallback timer / emit policy across GPS states | `RecoveryCharacterizationTest`, `TrackingServiceFallbackPersistenceTest` |
| Recovery anchor | `RecoveryAnchorStore` save/load/clear round-trip | `RecoveryAnchorRestartTest` |
| Upload → snapshot | Queue result updates snapshot fields | `RuntimeSnapshotProjectorTest`, `UploadLivenessStateTest` |
| UI status | `TrackingUiStatusResolver` sequences from runtime snapshot | `PositioningStatusProjectionCharacterizationTest` |

## Manual smoke (release checklist)

1. Start / stop tracking from UI and notification.
2. Auto-pause for motion, resume on movement / sig-motion.
3. Toggle GPS provider off and on while recording.
4. Manual send point while recording.
5. Background location wakeup intent while tracking.
6. Fast GPS lock window after poor-accuracy reject.
7. Low-accuracy fallback timer emit.
8. Map puck does not drive collection (recording independent of map).

## Out of scope

- Map presentation (`com.geovault.common.maps.*` must not appear under `positioning/`).
- Destructive DB migrations or loosening filter thresholds without tests.

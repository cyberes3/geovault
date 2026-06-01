# Tracking Foreground Service Runtime

Foreground GPS recording orchestration for the tracker app. Reusable engines remain in `com.geovault.tracker.services`; positioning policy code remains in `location/` and `policy/`.

## Layout

| File | Role |
|------|------|
| `TrackingService.kt` | Android `Service` shell; delegates lifecycle to the host |
| `TrackingServiceHost.kt` | Session state, listeners, `onCreate` / `onStartCommand`, wiring |
| `TrackingServiceDependencies.kt` | Collaborator construction (`onCreate` wiring) |
| `TrackingRecordingSession.kt` | Mutable session state bag and reset helpers |
| `TrackingServiceIntents.kt` | Intent actions, command routing helpers |
| `TrackingServiceConstants.kt` | Shared timing/queue constants |
| `TrackingSessionLifecycle.kt` | Start/stop, cleanup, location session start/stop |
| `TrackingCommandDispatcher.kt` | Background wakeup and location-update command helpers |
| `TrackingForegroundController.kt` | Foreground promotion, startup failure paths |
| `TrackingLocationFixHandler.kt` | Live fix ingest (`processLocationUpdate`) |
| `PausedFreshnessFixHandler.kt` | Paused-motion freshness probe path |
| `GpsCollectionController.kt` | GPS runtime transitions, pause/resume, provider wait |
| `TrackingLocationRequestController.kt` | Location request apply/reapply and fix-delivery watchdog |
| `FastGpsLockController.kt` | Fast-lock window and sample selection |
| `LowAccuracyFallbackRunner.kt` | Low-accuracy fallback timer and candidate handling |
| `TrackingUploadCoordinator.kt` | Queue push, retry/backlog/preflight jobs |
| `TrackingRuntimeProjection.kt` | Runtime snapshot, notification, session stats broadcast |
| `TrackingPositioningContext.kt` | Positioning presets, sparse tracking, recovery anchor |
| `TrackingAdaptationController.kt` | Auto-motion and elastic distance filter |
| `TrackingManualAndWakeupCommands.kt` | Manual point send |
| `TrackingRecoveryJobs.kt` | Recovery heartbeat |
| `TrackingHostUtilities.kt` | Battery, haptics, track-point bus publish, policy delegates |
| `GpsProviderWaitPolicy.kt`, `FallbackTransitionPolicy.kt`, `FallbackPersistencePolicy.kt`, `ObservedSpeedResolver.kt` | Pure policy helpers (unit-tested) |
| `LocationRequestController.kt` | Fix-delivery expectation (`expectsActiveFixDelivery`) |

## Related packages

- `services/` — `QueueUploadEngine`, `LocationIngestCoordinator`, `GpsRuntimeStateMachine`, notification builder
- `runtime/` — app-wide runtime controller and `TrackingRuntimeStateStore` (UI health)
- `location/` — filter pipeline, motion/freshness coordinators

# Positioning Runtime

Unified recording-time positioning for android-tracker. `PositioningRuntime` is a thin facade; subsystems own GPS collection, fix ingest, recovery, motion, lifecycle, and projection. The foreground service shell in `tracking/` delegates here.

## Layout

| Area | Responsibility |
|------|----------------|
| `PositioningRuntime.kt` | Facade: lifecycle, listeners, subsystem wiring (~310 LOC) |
| `PositioningSessionState.kt` | Sole mutable session bag; `resetForStart()` / `resetForStop()` clear all session fields |
| `SessionResetCoordinator.kt` | Ordered dependency + subsystem resets on session start/stop |
| `PositioningConfig.kt` / `PositioningContext.kt` | Immutable context per fix (includes `RecordingPace`) |
| `PositioningDependencies.kt` | Wires DB, upload, coordinators, pipeline |
| `PositioningAndroidPorts.kt` | Service, notification id, selected tracker id |
| `PositioningHostUtilities.kt` | Publish, haptics, device/battery helpers |
| `PositioningContextBuilder.kt` | Settings → `PositioningContext`, recovery config |
| `RuntimeProjectionSubsystem.kt` | Snapshot, notification, control state |
| `SessionLifecycleSubsystem.kt` | Start/stop, location updates, FSM/snapshot/jobs (resets via coordinator) |
| `ForegroundSubsystem.kt` | FGS promotion, safe stop |
| `CommandDiagnosticsSubsystem.kt` | Background wakeup + location-update commands |
| `ManualFixSubsystem.kt` | Manual send point |
| `UploadSubsystem.kt` | Queue upload jobs and liveness |
| `ingest/FixIngestSubsystem.kt` | Serialized fix processing |
| `collection/` | `GpsCollectionSubsystem`, `LocationRequestSubsystem`, `UnifiedLocationClient` |
| `recovery/RecoverySubsystem.kt` | Fast-lock, fallback, paused freshness, heartbeat jobs |
| `motion/MotionSubsystem.kt` | Elastic distance + auto-motion |
| `config/` | GPS state machine, presets |

See [`CHARACTERIZATION.md`](CHARACTERIZATION.md) for the regression-test checklist.

## Boundaries

- **No** `com.geovault.common.maps.*` imports in this package (map UI lives in presentation + android-common-maps).
- Fix ingress validates coordinates with `com.geovault.common.geo.GeoCoordinates`.
- Background fixes use **PendingIntent +** `UnifiedLocationClient` (not common-maps `LocationUpdates`).

## RecordingPace

Derived from `GpsRuntimeState` (and optional stationary region). Used for diagnostics and logging—not for filter thresholds or GPS transitions.

# Positioning Runtime

Unified recording-time positioning for android-tracker. `PositioningRuntime` owns session state, GPS collection, fix ingest, recovery, and motion adaptation. The foreground service shell in `tracking/` delegates here.

## Layout

| Area | Responsibility |
|------|----------------|
| `PositioningRuntime.kt` | Facade: lifecycle, listeners, session fields via `PositioningSessionState` |
| `PositioningSessionState.kt` | Sole mutable session bag; `resetForStart()` / `resetForStop()` |
| `PositioningConfig.kt` | Builds `PositioningContext` from settings + state |
| `PositioningContext.kt` | Immutable snapshot per fix (includes derived `RecordingPace`) |
| `PositioningDependencies.kt` | Wires DB, upload, coordinators, pipeline |
| `PositioningAndroidPorts.kt` | Service / notification / tracker-id Android boundary |
| `ingest/` | `TrackerLocationPipeline`, `FixIngestSubsystem` (fix processing) |
| `collection/` | `GpsCollectionSubsystem`, `LocationRequestSubsystem`, `UnifiedLocationClient` |
| `recovery/` | Fast-lock, low-accuracy fallback, paused freshness, recovery jobs |
| `motion/` | Elastic distance + auto-motion adaptation |
| `config/` | GPS state machine, presets, policy config |

## Boundaries

- **No** `com.geovault.common.maps.*` imports in this package (map UI lives in presentation + android-common-maps).
- Fix ingress validates coordinates with `com.geovault.common.geo.GeoCoordinates`.
- Background fixes use **PendingIntent +** `UnifiedLocationClient` (not common-maps `LocationUpdates`).

## RecordingPace

Derived from `GpsRuntimeState` (and optional stationary region). Used for diagnostics and logging—not for filter thresholds or GPS transitions.

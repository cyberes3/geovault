# Tracking Service Shell

Thin Android foreground-service layer around `com.geovault.tracker.positioning.PositioningRuntime`.

| File | Role |
|------|------|
| `TrackingService.kt` | `Service` lifecycle entry |
| `TrackingServiceHost.kt` | Delegates to `PositioningRuntime` (~20 LOC) |
| `TrackingServiceIntents.kt` | Intent actions / extras (public contract) |
| `TrackingServiceConstants.kt` | Notification id, timeouts, queue limits |

All recording positioning logic lives under `positioning/`. Do not add map or ingest logic here.

# Tracker History

Compose-time trail storage for the map. Room queue and server geometry feed a single in-memory pipeline; the map display layer reads snapshots only.

## Layout

| Type | Responsibility |
|------|----------------|
| `TrackerHistorySourceStore` | Raw trunk and overlay batches per tracker/window |
| `TrackerHistoryRepository` | Compose, publish `StateFlow` snapshots |
| `TrackerHistoryAssembler` | Merge trunk + overlay; apply window filter |
| `TrackerHistoryWindowFilter` | `recent_data_window` rules (current_session, session, rolling) |
| `TrackerHistorySessionAttribution` | Segment points by `startTimestampMs` |
| `TrackerHistorySessionBoundary` | Recording start/stop display boundaries only |
| `TrackerHistoryActiveSessionPolicy` | Clip server trunks to active session |
| `TrackerHistoryIntentDispatcher` | Commit trunk/overlay/clear intents |

See [`CHARACTERIZATION.md`](CHARACTERIZATION.md) for regression tests.

## Data flow

```
Intents (trunk/overlay/clear) → SourceStore → Assembler (+ window filter) → snapshots
```

## Boundaries

- Window filtering runs **once** at compose time in this package.
- `TrackerHistorySessionBoundary` may clear/recompose on **recording start/stop** only.
- **Do not** call `Clear` or `repository.reset()` from map reload, streaming reconcile, or map surface open.
- User clear history and logout use `Clear` / `clearSelectedTrackerCaches` as today.
- Map render adds runtime head overlay in `map.MapTrailDisplaySubsystem`; it does not re-filter windows.

## Map integration

[`TrackerMapRuntime`](../map/TrackerMapRuntime.kt) owns reload and display subsystems that dispatch intents and read `TrackerHistoryRepository.snapshots`.

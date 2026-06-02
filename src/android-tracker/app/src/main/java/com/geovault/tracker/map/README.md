# Map Runtime

Map presentation logic for android-tracker. `TrackerMapRuntime` is a thin facade; subsystems own streaming collectors, map context transitions, trail display/render, and database reload. `TrackerMapViewModel` delegates here.

## Layout

| Area | Responsibility |
|------|----------------|
| `TrackerMapRuntime.kt` | Facade: shared flows, reload/streaming state, subsystem wiring (~220 LOC) |
| `TrackerMapPorts.kt` | `Application`, `viewModelScope` holder |
| `presentation/TrackerMapModels.kt` | `TrackerMapUiState`, render package, selection card, reload reason enums |
| `MapStreamingSubsystem.kt` | Flow collectors (runtime, streaming, roster metadata, events, points), reconcile |
| `MapContextSubsystem.kt` | Display mode, map surface lifecycle, selection card, resume/reopen |
| `MapTrailDisplaySubsystem.kt` | Render package, effective session projection, camera directive, bounds |
| `MapTrailReloadSubsystem.kt` | Trail reload coalescing, server/queue loads, preload, point overlay commits |
| `MapTrailLogExtensions.kt` | Trail/bounds debug string helpers |
| `presentation/TrackerMapViewModel.kt` | Thin shell: public API + `@JvmStatic` test helpers |

## Boundaries

- History filtering uses `TrackerHistoryWindowFilter` in the history package; `TrackerMapSessionEngine` does not apply per-tracker window keys at draw time.
- Recording start/stop uses `TrackerHistorySessionBoundary` in `MapStreamingSubsystem` (recompose on start, clear on stop — never clear on start).
- Cache/server trunk preload skips `CommitTrunk` when `TrackerHistoryActiveSessionPolicy` would reject the batch (avoids stale 3-point geometry spam).
- Map UI types stay in `presentation/`; orchestration lives under `map/`.

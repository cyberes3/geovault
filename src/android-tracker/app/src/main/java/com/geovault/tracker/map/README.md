# Map Runtime

Map presentation logic for android-tracker. `TrackerMapRuntime` is the composition root: it constructs the shared reactive state, the camera/trail-commit coordinators, and every per-concern subsystem, then exposes a handful of stateless helpers called from multiple subsystems. All actual behavior lives on the subsystems below. `TrackerMapViewModel` delegates here.

## Layout

| Area | Responsibility |
|------|----------------|
| `TrackerMapRuntime.kt` | Composition root: wires dependencies/state/coordinators/subsystems, exposes shared stateless helpers |
| `TrackerMapPorts.kt` | `Application`, `viewModelScope` holder |
| `TrackerMapDependencies.kt` | DI graph: repositories/services resolved once for the runtime's lifetime |
| `TrackerMapStateHub.kt` | Owns the shared `TrackerMapUiState`/`TrackerMapRenderPackage` `MutableStateFlow`s |
| `TrailCommitCoordinator.kt` | Owns `trailCommitLock`, the mutex serializing trail-state commits |
| `TrackerMapCameraCoordinator.kt` | Mints/dedupes camera directives, tracks the manual-control generation counter |
| `PendingReloadCameraFit.kt` | Type-safe arm/consume/disarm state for post-reload camera re-fits |
| `TrackerMapStreamingPlanCache.kt` | Memoizes `projectSession` per unique input signature |
| `presentation/TrackerMapModels.kt` | `TrackerMapUiState`, render package, selection card, reload reason enums |
| `MapStreamingSubsystem.kt` | Flow collectors (runtime, streaming, roster metadata, events, points), reconcile |
| `MapContextSubsystem.kt` | Display mode, map surface lifecycle, selection card, resume/reopen |
| `MapTrailDisplaySubsystem.kt` | Render package, effective session projection, camera directive, bounds |
| `MapTrailReloadSubsystem.kt` | Trail reload coalescing, server/queue loads, preload, point overlay commits |
| `StreamRosterResolver.kt` | Decides which trackers should be streamed/displayed right now |
| `StreamTargetReconciler.kt` | Turns a roster resolution into an actual WebSocket lease |
| `TrackPointReducer.kt` | Applies one incoming track point to UI state |
| `MapTrailLogExtensions.kt` | Trail/bounds debug string helpers |
| `presentation/TrackerMapViewModel.kt` | Thin shell: public API + `@JvmStatic` test helpers |

## Boundaries

- History filtering uses `TrackerHistoryWindowFilter` in the history package; `TrackerMapSessionEngine` does not apply per-tracker window keys at draw time.
- Recording start/stop uses `TrackerHistorySessionBoundary` in `MapStreamingSubsystem` (recompose on start, clear on stop — never clear on start).
- Cache/server trunk preload skips `CommitTrunk` when `TrackerHistoryActiveSessionPolicy` would reject the batch (avoids stale 3-point geometry spam).
- Map UI types stay in `presentation/`; orchestration lives under `map/`.

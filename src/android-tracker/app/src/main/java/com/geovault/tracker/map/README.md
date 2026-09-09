# Map Runtime

Map presentation logic for android-tracker. `TrackerMapRuntime` is the composition factory: it constructs shared reactive state and the three engines. Session, trail, and render behavior live on the engines. `TrackerMapViewModel` delegates here.

## Layout

| Area | Responsibility |
|------|----------------|
| `TrackerMapRuntime.kt` | Composition factory: wires dependencies/state/engines and serializes trail commits |
| `TrackerMapPorts.kt` | `Application`, `viewModelScope` holder |
| `TrackerMapDependencies.kt` | DI graph: repositories/services resolved once for the runtime's lifetime |
| `TrackerMapStateHub.kt` | Owns the shared `TrackerMapUiState` `MutableStateFlow` |
| `GeoVaultMapCameraDirectiveBus` | Common generation-stamped camera command bus; tracker lock precedence stays on `MapRenderEngine` |
| `presentation/TrackerMapModels.kt` | `TrackerMapUiState`, render package, selection card, reload reason enums |
| `MapSessionEngine.kt` | Session document, mailbox, collectors, MAP lease, display mode, surface lifecycle, resume |
| `MapTrailEngine.kt` | `TrailView` owner; consumes history snapshots and the local queue |
| `MapRenderEngine.kt` | Render package + camera directive from session/trail |
| `MapTrailLogExtensions.kt` | Trail/bounds debug string helpers |
| `presentation/TrackerMapViewModel.kt` | Thin shell: public API delegated to the engines |

## Boundaries

- History filtering uses `TrackerHistoryWindowFilter` in the history package; `TrackerMapSessionEngine` does not apply per-tracker window keys at draw time.
- Recording start/stop uses `TrackerHistorySessionBoundary` in `MapSessionEngine` (recompose on start, clear on stop — never clear on start).
- Cache/server trunk preload skips `CommitTrunk` when `TrackerHistoryActiveSessionPolicy` would reject the batch (avoids stale 3-point geometry spam).
- Map UI types stay in `presentation/`; orchestration lives under `map/`.

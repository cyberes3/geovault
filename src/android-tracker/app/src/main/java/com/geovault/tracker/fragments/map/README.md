# Map package

Map UI and logic for the tracker app: single-tracker and all-trackers views, camera, rendering, and data loading.

**MapFragment** is the orchestrator: lifecycle, view binding, and wiring between the modules below.

## Layers

| Layer | Purpose | Files |
|-------|---------|-------|
| **Models / constants** | Shared types, intents, IDs, magic numbers | MapUiModels, MapConstants, BestEffortViewportSelector |
| **Camera** | Bounds fit, padding, camera moves | MapBoundsFitController, MapCameraController, MapCameraMath, MapPaddingCalculator, MapPaddingRefresher |
| **Rendering** | Styles, sources, layers, track lines, multi-track, visibility | MapStyleSetup, MapTrackLineUpdater, MapTrackGeometryRenderer, MapMultiTrackRenderer, MapLayerVisibility |
| **Data** | Coordinates, history, loading decisions | MapCoordinateUtils, MapHistoryUtils, MapDataLoader |
| **UI / selection** | Labels, info card, tap selection, live-fit, standalone location | MapTrackerLabelController, MapTrackerInfoCardController, MapTapSelectionHandler, MapSelectionUtils, MapLiveActiveFitController, MapStandaloneLocationController, MapFollowLockUi (or MapCameraController) |
| **Flow / orchestration** | Debounce, streaming, service, receivers, group/all-trackers, single-tracker fetch, live stream point | MapLiveStreamCoordinator, MapLiveStreamHandler, MapStreamingServiceHelper, MapBroadcastHandlers, MapAllTrackersFlow, MapGroupRefreshHandler, MapSingleTrackFetch, MapLiveStreamPointHandler, MapStreamingDataHelper |

## Known Stability Findings

This section tracks map pipeline issues that caused incorrect tracks, dropped live points,
or race-related rendering bugs.

### Critical

- **Stale geometry overwrite on tracker switch**
  - Symptom: old geometry callback can replace current tracker line after quick tracker switches.
  - Files: `MapSingleTrackFetch`, `MapFragment`.
- **Live points dropped when geometry has no timestamp**
  - Symptom: merge path treats geometry timestamp as max value and filters all live points out.
  - File: `MapHistoryUtils`.

### High

- **Resume duplicate window**
  - Symptom: receiver registration before buffer drain can process the same point twice.
  - File: `MapFragment`.
- **Tracker switch race window**
  - Symptom: delayed switch apply can accept stale tracker live points into new state.
  - File: `MapFragment`.
- **Style-null intent loss for group/all mode**
  - Symptom: user action during style reload may be dropped instead of replayed.
  - File: `MapGroupRefreshHandler`.

### Medium

- **Mode/state drift risk**
  - Symptom: `displayedTrackerId`, `displayedTracker`, `showAllTrackers`, and `mapViewContext`
    can briefly diverge and allow wrong update decisions.
  - Files: `MapFragment`, `MapTrackerHeaderUiHelper`.

## Stability Test Matrix

Use this matrix for regression checks when touching map tracking/history code.

### Unit tests (required)

- `MapHistoryUtilsTest.applyGeometryToTrack_mergeExternalStreaming_preservesNewerStreamedPoints`
- `MapHistoryUtilsTest.applyGeometryToTrack_mergeExternalStreaming_withoutGeometryTimestamps_keepsStreamedPoints`
- `MapLiveStreamHandlerTest` (single vs multi-context routing)
- `MapDataLoaderTest` (active/displayed tracker resolution rules)

### Manual/integration checks (required before release)

- **Tracker switch race**
  - Start on tracker A, quickly switch to B while network is slow.
  - Expected: A callbacks are ignored; map remains on B.
- **Background catch-up while tracking**
  - Start tracking, background app for several minutes, reopen map.
  - Expected: buffered points and geometry refresh catch up without duplicates.
- **No streaming during tracking**
  - Start tracking from Home.
  - Expected: websocket streaming service does not run; map still updates from tracking broadcasts.
- **Style reload under live updates**
  - Toggle basemap while points are arriving.
  - Expected: track line and marker recover after style load; no stuck stale frame.
- **Group/all intent during style reload**
  - Trigger group/all map action while style is loading.
  - Expected: intent is replayed once style becomes ready.

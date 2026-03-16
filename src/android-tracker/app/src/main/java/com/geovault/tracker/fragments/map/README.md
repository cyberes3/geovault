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

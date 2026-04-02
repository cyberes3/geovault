# Tracker Compose Rebuild (Full Parity)

## Build Strategy

- Build each subsystem **piece by piece** in `src/new-apps/android-tracker`, referencing legacy `src/android-tracker` as the behavioral spec.
- Port and rewrite each component directly into the modern architecture (Compose, manual DI, clean packages) rather than copying then refactoring.
- Copy individual source files from legacy when their logic is complex and already correct (e.g. pipeline, runtime state machines, binary payload builders), then clean up imports/structure in place.

## Locked Behavioral Parity Invariants

- Runtime/service parity must preserve:
  - null-intent process restart does **not** auto-restart tracking
  - backlog vs live queue boundaries and visible-session counting behavior
  - start/stop/watchdog/recovery intent paths and `wasTrackingBeforeExit` semantics
- Map parity must preserve:
  - mode/source filtering (`TRACKING_SINGLE` local-only vs browse remote-only)
  - follow-lock, live-fit, and my-location interaction precedence rules
  - single/all/group context switching and stream eligibility filtering
- Management-flow parity must preserve:
  - shared/discover/public filtering and dedupe rules
  - tracker/group hidden semantics and owner/non-owner action boundaries
  - tracker settings persistence checks and side effects (selected tracker interactions)
- Platform/auth parity must preserve:
  - OAuth callback validation/state checks and token exchange path
  - server reachability gating that disables restricted tabs and forces Home when unavailable
  - reset behavior on auth failure/manual sign-out with deterministic cleanup ordering

## Architecture Direction

- Use a **single `app` module** with clear package boundaries (`di`, `domain`, `data`, `presentation`, `ui`, `navigation`, `runtime`, `tracking`, `map`).
- Follow the rewritten-app composition style from `android-places` and `android-uploader`.
- Use a dedicated `TrackerAppServices` composition root to centralize object wiring.
- Mirror proven `new-apps` patterns:
  - `android-places` as canonical map-enabled architecture and lifecycle reference
  - `android-uploader` as non-map service/composition reference

## Planned Improvements During Rewrite

- Reorganize code by responsibility while preserving behavior
- Standardize on explicit state/event/result models for UI and orchestration boundaries
- Consolidate overlapping repository responsibilities into consistent contracts
- Harden risky subsystems (runtime startup/recovery, map lock/camera/stream policies)
- Replace XML layout screens with Compose equivalents
- Add targeted tests for dangerous logic (queue boundaries, lock precedence, share filters)

## Target App Shape

- Five-tab Compose shell: Home, Map, Trackers+Groups, Shared, Settings
- Activity-level platform responsibilities (permissions, OAuth, service intents)
- ViewModels orchestrate state and invoke domain use cases/coordinators
- `TrackerAppServices` composition root (manual DI)

## Execution Steps

### Step 1: App Foundation and Gradle Setup
- Set up Gradle with version catalog, common module dependencies, manifest
- Create `TrackerApplication` with auth init, MapLibre init, reset hooks
- Create `OAuthCallbackActivity` using shared callback handler
- Create `MainActivity` with `GeoVaultTheme` + `GeoVaultBottomNavScaffold` (5 tabs)
- Create `TrackerAppServices` composition root
- Wire `ServerUrlProvider` in manifest
- **Exit gate:** app compiles, launches, shows auth gate and empty tab shell

### Step 2: Data Layer (API, Models, Storage)
- Port `TrackerApi` Retrofit interface (all endpoints)
- Port `TrackerModels` (DTOs, request/response types)
- Build `TrackerRepository` with clean contracts
- Set up Room database (`AppDatabase`, `QueuedLocation`, `LocationDao`)
- Build settings store (`TrackerSettingsRepository` via DataStore)
- Build `SelectedTrackerManager`
- **Exit gate:** API calls compile, Room schema defined, settings read/write works

### Step 3: Runtime and Tracking Services
- Port `TrackingService` (foreground service, queue, upload, notification)
- Port tracking pipeline (`TrackPointPipeline`, `TrackPointBus`, policy engine)
- Port location client integration (`UnifiedLocationClient`)
- Port runtime controller stack (state machine, command handler, effects, watchdog)
- Port boot/recovery receivers (`BootReceiver`, `TrackingRecoveryReceiver`, `ForegroundNotificationReshowReceiver`)
- Port `LiveTrackStreamingService` (WebSocket lifecycle, session guard)
- Wire manifest entries for all services/receivers/permissions
- **Exit gate:** tracking starts/stops, queues upload, survives boot, streams live

### Step 4: Common-Maps Enhancements
- P0: Add synchronous GeoJSON update option to `GeoJsonRenderPlugin`
- P0: Add symbol rotation support to render point model and plugin
- P1: Add style failure callback hook to base map controller
- P1: Add explicit style-finished hook path
- Verify no regressions in `android-places` after changes
- **Exit gate:** common-maps builds, places still works, new APIs available

### Step 5: Map Screen
- Build `MapViewModel` with mode state machine, streaming, camera commands
- Build map Compose screen with `GeoVaultMainMapView` and retained map key
- Wire map plugins (render + location) with proper lifecycle
- Port track geometry rendering (single + multi-track, outlined lines)
- Port live stream point handling and debouncing
- Port camera/lock/follow-lock/live-fit/my-location policy stack
- Port map preload and resume/reopen orchestration
- Wire map FABs (zoom, layer toggle, GPS recenter, follow lock)
- **Exit gate:** map shows tracks, streams live points, lock/follow works, group/all modes work

### Step 6: Home Screen
- Build `HomeViewModel` with tracking state, permission status, session stats
- Build Home Compose screen with start/stop, manual point, permission strip
- Wire tracker params display (local stream vs server metadata)
- Wire server reachability overlay
- **Exit gate:** start/stop tracking from home, permissions gated correctly

### Step 7: Trackers and Groups Screens
- Build trackers list screen (owned, non-hidden, with actions)
- Build groups list screen (owned, non-hidden, with actions)
- Build pager/tab container for trackers + groups
- Build new tracker creation flow
- Build edit tracker flow (owner settings, sharing, recipients, world share, KML, clear history, delete)
- Build edit shared tracker flow (unsubscribe/leave)
- Build group detail flow (name, members, sharing, hide, delete)
- Build add group trackers flow
- Build edit shared group flow
- **Exit gate:** full CRUD for trackers and groups with owner/non-owner boundaries

### Step 8: Shared, Discover, and Public Screens
- Build shared trackers list (combined shared trackers + groups with filter/dedupe rules)
- Build discover flow (incoming vs on-my-map, subscribe/unsubscribe, accept group)
- Build public trackers flow (public trackers + groups, subscribe, multi-subscribe for groups)
- Build hidden trackers screen (unhide one / unhide all)
- **Exit gate:** all share/discover/public flows work with correct filtering

### Step 9: Settings Screen
- Build settings screen (server config, OAuth connect/disconnect, tracking profiles, intervals, flags, hidden entry, view-all-on-map)
- Wire settings to `TrackerSettingsRepository` with write policy/validation
- Wire version check and update snackbar
- **Exit gate:** all settings read/write correctly, disconnect triggers reset

### Step 10: Integration, Testing, and Polish
- Run full parity checklist against legacy behavior
- Add focused unit tests for runtime policies, map state transforms, share filters, queue boundaries
- Verify build (debug + release)
- Remove dead code and clean up naming
- Final dependency version alignment
- **Exit gate:** app builds clean, tests pass, parity checklist complete

## `android-common` Integration Checklist

- Initialize auth manager once and set auth failure listener in `Application`
- Register `AppResetFlow` hooks for tracker teardown (service/map/cache cleanup)
- Compose root wrapped in `GeoVaultTheme`
- Use shared bars/nav/snackbar/form components where equivalent exists
- OAuth callback activity uses shared callback handling/validation path
- Authenticated API clients through common auth-aware OkHttp path

## `android-common-maps` Integration Checklist

- Include `:android-common` and `:android-common-maps` in settings and dependencies
- One stable retained main-map key for tracker tab
- Register/unregister render/location plugins in composable lifecycle
- Render state pushed from viewmodel transforms to plugin
- Camera and bounds actions gated by map ready phase
- Force release retained map key during auth/local reset

## `android-common-maps` Shared Enhancements

### P0 Required
- Synchronous GeoJSON source updates in render plugin config
- Symbol rotation support for rendered points

### P1 High-Value Hooks
- Style/load failure callback hook in base map controller
- Explicit style-finished hook path

### P2 Optional
- Non-Compose host wrapper only if justified by multiple apps

### Boundary Rules
- Allowed: generic rendering options, lifecycle hooks, style/camera extension points
- Not allowed: tracker lock-state semantics, streaming acceptance rules, tracker camera policies

## Tracker Map Responsibilities (Stay App-Side)

- Map mode/source acceptance rules tied to tracking state
- Follow-lock/live-fit/my-location precedence and persistence
- Streaming orchestration, debouncing, and session-window resync
- Map reopen/resume/runtime resync policy

## Known Legacy Ambiguities To Resolve Early

- `map-visibility` API semantics vs tracker/group hidden flags
- discover/public/group remove semantics (`leaveGroup` vs unsubscribe)
- OAuth success surface details (signed-in identity presentation)
- dead/unused paths to remove vs retain
- runtime recovery expectations vs current effective behavior

## Reorganization Blueprint

- **Activity layer:** platform concerns only (permissions, intents, activity results)
- **Presentation layer:** feature viewmodels own screen state machines
- **Domain layer:** policies, use cases, coordinators, sealed outcomes (no Android UI deps)
- **Data layer:** repositories, API clients, local stores implementing domain contracts
- **Runtime layer:** tracking runtime controller, watchdog, boot/recovery, service launch gates
- **Map layer:** map context state, camera/lock policy, stream application, geometry render
- **DI layer:** `TrackerAppServices` and small factories

## Acceptance Criteria

- New tracker app matches legacy user-visible behavior and runtime semantics
- No XML layout screen dependencies remain (Compose-driven UI)
- Common library changes are lean, reusable, and not tracker-specific dumping grounds
- Targeted tests pass; app builds cleanly for debug/release
- Internal architecture materially improved from legacy baseline
- `android-common-maps` contains only minimal, reusable extensions with no tracker business logic leakage

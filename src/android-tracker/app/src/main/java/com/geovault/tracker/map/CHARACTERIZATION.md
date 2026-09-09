# Map Characterization

Pinned working outcomes before session/trail/render/scaffold cuts.

| Case | Today |
| --- | --- |
| Visibility | Owner hidden + group hidden + `hidden_track_ids` / `hidden_group_ids`. Empty visible set draws nothing |
| History-only | MAP Hold when viewed == selected |
| GROUP/ALL idle selected | May stream the idle selected tracker |
| Resume / tab return | Reproject when surface visible; skip if map not ready |
| Live heads | `LiveHeadStore` snapshot on `TrailView`; local GPS tip is not a history compose kind |
| Trail degrade | `degradedLocalOnly` trunks appear on `TrailView.degradedTrackerIds` and the map host |
| Camera | `GeoVaultMapCameraDirectiveBus` generation-stamped directives |
| LiveActiveFit | User/session lock only; puck disable does not auto-clear it |
| Show All | Clears owner-hidden only; map still applies both filters |
| Recording start | Recompose history; do not treat as mode switch always-refresh |

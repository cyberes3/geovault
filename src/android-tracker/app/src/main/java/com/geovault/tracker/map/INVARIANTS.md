# Map Invariants

If two files can answer the same question, one dies.

- Visibility: only `CatalogState.mapVisibility`. Empty `visibleTrackerIds` draws nothing.
- Geometry: only `TrailView`. Window filter once at history compose. Degraded trunks are on `TrailView.degradedTrackerIds` and visible on the map host.
- Live points: bus → overlay intent → snapshot → `TrailView`.
- Live heads: only `LiveHeadStore`. Remote tips and the local GPS tip share that map. `TrailView.remoteLastPoints` is the published snapshot. Local GPS is not a history `RUNTIME_HEAD` overlay.
- Last-point chrome is one `LiveHeadStore` lookup. Roster `last_point`, trail tails, and recording GPS are not a second last-point tournament.
- Lease: map writes `StreamingOwner.MAP` only. Blank SINGLE is Hold.
- One-shot roster events (`HistoryCleared`, `TrackerDeleted`) enter the session mailbox. The mailbox consumer is supervised so a throw does not drop later events.
- Camera: one directive bus. Stale generation never applies. GPS one-shot is a fit-include on that bus, not a Compose camera authority. Follow lock uses the puck, not the home-fit include.
- Cosmetic vs structural: name/color republish render; roster/visibility new StreamIntent + reload.

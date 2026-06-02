# Tracker History Characterization

Regression checklist for map trail persistence and compose.

| Behavior | Test |
|----------|------|
| Trunk + overlay + clear round trip | `TrackerHistoryReloadIntegrationTest.trunkOverlayClear_roundTripThroughRepository` |
| Clear purges all window keys for tracker | `clearHistory_purgesAllWindowKeysForTracker` |
| Overlay before session start composes | `recordStart_overlayBeforeRuntimeSessionStart_composesNonEmptyCurrentSession` |
| Mixed server trunk clipped to session | `activeRecording_clipsMixedCurrentSessionServerTrunk` |
| All-pre-session trunk rejected | `activeRecording_ignoresStaleCurrentSessionServerTrunk` |
| Assembler window filter | `TrackerHistoryAssemblerTest` |
| Session boundary | `TrackerHistorySessionBoundaryTest` — recompose on start, clear on stop only, pending session flush |
| Cache preload | Skip `CommitTrunk` when `TrackerHistoryActiveSessionPolicy` would reject stale server geometry |
| Live trail gaps while recording | `MAX_TRACK_TIME_GAP_WHILE_RECORDING_MS` (15 min) vs 5 min when viewing history |

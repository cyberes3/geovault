# Live Stream Invariants

If two files can answer the same question, one dies.

- Who wants a stream? `repository.state.mergedTargets` (and `.leases`)
- Is the socket healthy? `state.health` only
- Who may write MAP / PARAMS? Reconciler / Params controller only
- Who may write health? `LiveStreamRuntime` via `reportConnectionHealth` only
- Who may persist / start the FGS? PersistPort / HostPort only
- How do remote points enter the map? `LiveStreamIngress` → `AdmissionPipeline.admit(REMOTE_STREAM)` → `TrackPointBus`
- Is this track locally recorded? `TrackerRuntimeDocument.locallyRecordedTrackerId`
- Is the connection stale? last app pong vs `StreamingConfig`. Not point recency. Not OkHttp ping.
- Who owns connect/reconnect generation? `LiveStreamConnection` under the runtime lock. `Connected` only when the socket is non-null in that same acquisition.
- Roster change on a live socket? Hot-update ingress scope + notification. No new socket.
- Process death? PersistPort + bootstrap + `START_STICKY` Apply
- User / logout / task-removed / notification stop? `clearAllLeases` → dispatch Stop
- History-only? `viewed == selected` → MAP Hold / Params no-lease. Idle selected still streams in GROUP/ALL

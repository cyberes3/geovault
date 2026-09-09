# Live Stream Characterization

Pinned working outcomes before command/runtime/ingress cuts.

| Case | Today |
| --- | --- |
| History-only | MAP Hold when viewed == selected |
| GROUP/ALL idle selected | May stream the idle selected tracker |
| Swipe away | `onTaskRemoved` stops watching |
| Two FGS types | Tracking 101 location, streaming 102 dataSync |
| Notification Stop | Must stop the socket (not remount) |
| Persist | On successful apply; clear on stop / logout / permanent fail |
| Retry alarm | Cancel on every terminal path |
| Remote TTL/acc/speed | Stay looser than local recording |

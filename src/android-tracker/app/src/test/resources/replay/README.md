# Capture replay fixtures

Committed JSON under this directory is produced from a **local** capture log. Coordinates are anonymized in memory during extraction; originals are never stored in git.

## CI

```bash
cd src/android-tracker
./gradlew :app:validateCaptureReplay :app:testCaptureReplay
```

`validateCaptureReplay` needs Python 3 (stdlib only). Tests do not read capture logs.

## Regenerate a session (maintainer)

All extractor arguments are required (no defaults).

```bash
cd src/android-tracker

python3 scripts/extract_capture_replay.py write /path/to/capture.txt.gz \
  --session traffic_jam_2026_06_02 \
  --start 2026-06-02T22:30:00Z \
  --end 2026-06-02T22:46:00Z \
  --output app/src/test/resources/replay/traffic_jam_2026_06_02.json

python3 scripts/extract_capture_replay.py check /path/to/capture.txt.gz \
  --session traffic_jam_2026_06_02 \
  --start 2026-06-02T22:30:00Z \
  --end 2026-06-02T22:46:00Z \
  --output app/src/test/resources/replay/traffic_jam_2026_06_02.json

./gradlew :app:testCaptureReplay
```

Optional: `--track-id <uuid>` when the log contains multiple tracks.

## Rules

- Never commit capture logs (`.txt`, `.gz`) or unshifted extracts.
- Commit only shifted replay JSON.
- Review PRs for coordinates that look like an identifiable region cluster.

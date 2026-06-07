# Capture replay fixtures

Committed JSON under this directory is produced from a **local** point recording export. Coordinates are anonymized in memory during extraction; originals are never stored in git.

## CI

```bash
cd src/android-tracker
./gradlew :app:validateCaptureReplay :app:testCaptureReplay
```

`validateCaptureReplay` needs Python 3 (stdlib only). Tests do not read capture logs; they feed committed schema-v2 `rawFixes` into the real positioning runtime replay driver.

## Regenerate a session (maintainer)

Build with point recording enabled, capture a drive, export, then extract:

```bash
cd src/android-tracker
./build-android.sh debug --add-recording --install
# reproduce scenario on device
./download-point-recording-log.sh
```

Export is triggered via `com.geovault.tracker.EXPORT_POINT_RECORDING_LOG` (see `download-point-recording-log.sh`).

All extractor arguments are required (no defaults). The export must contain `positioning_raw_fix` lines from `FixIngestSubsystem` (written only when `--add-recording` is compiled in).

```bash
python3 scripts/extract_capture_replay.py write /tmp/MyApp_point-recording_....txt.gz \
  --session walk_short_drive_walk_2026_06_03 \
  --start 2026-06-03T12:00:00Z \
  --end 2026-06-03T12:05:00Z \
  --output app/src/test/resources/replay/walk_short_drive_walk_2026_06_03.json \
  --settings-json /path/to/replay_settings.json

python3 scripts/extract_capture_replay.py check /tmp/MyApp_point-recording_....txt.gz \
  --session walk_short_drive_walk_2026_06_03 \
  --start 2026-06-03T12:00:00Z \
  --end 2026-06-03T12:05:00Z \
  --output app/src/test/resources/replay/walk_short_drive_walk_2026_06_03.json \
  --settings-json /path/to/replay_settings.json

./gradlew :app:testCaptureReplay
```

Optional: `--track-id <uuid>` when the log contains multiple tracks.

Use `./build-android.sh ... --add-logging` and `./download-capture-log.sh` only when you need full diagnostic capture. Replay **raw fixes** come from the point recording DB.

## Rules

- Never commit capture or point recording logs (`.txt`, `.gz`) or unshifted extracts.
- Commit only shifted replay JSON.
- Review PRs for coordinates that look like an identifiable region cluster.

# GeoVault Android Tracker

Native Android live-tracking app for GeoVault.

## Recovery Telemetry Dump

The tracker app keeps a persistent in-app recovery telemetry ring buffer so you can inspect recovery behavior after returning home.

### Trigger a telemetry dump to logcat

```bash
adb shell am start -n com.geovault.tracker.debug/com.geovault.tracker.MainActivity -a com.geovault.tracker.ACTION_DUMP_RECOVERY_TELEMETRY
```

### Read dumped telemetry

```bash
adb logcat -d -v time -s TrackingRecovery
```

Look for:

- `Telemetry dump requested ...`
- `Telemetry[1/N] ...` through `Telemetry[N/N] ...`

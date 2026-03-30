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



## GrapheneOS and Location Services

This app uses Google Play Location Services at the API layer. There is no supported build without that stack, and I am not interested in maintaining one. If you have an issue with this, run GrapheneOS and use the setup below so you get OS-backed non-Google positioning.

On GrapheneOS, with the default `Reroute location requests to OS APIs` toggle, GrapheneOS provides an OS-level shim: the tracker still calls Play Location APIs, but fixes are satisfied through GrapheneOS/OS geolocation, not Google’s full geolocation pipeline, unless you deliberately turn rerouting off ([configuration](https://grapheneos.org/usage#sandboxed-google-play-configuration)).

By default, `Settings → Apps → Sandboxed Google Play → Reroute location requests to OS APIs` is on. Leave it that way if you want GrapheneOS’s reimplementation on top of the standard OS location stack instead of Google’s Play geolocation service.

For Wi‑Fi/cell–assisted fixes without relying on Google’s network location, enable `Settings → Location → Location services → Network location` per GrapheneOS ([network location](https://grapheneos.org/features#network-location)). GrapheneOS notes that rerouting without this can use more power than using Play’s geolocation path unless network location is enabled.

### If you want to use Google’s Location Services instead

Google Play Location Services is more accurate and battery-efficient than what GrapheneOS's implementation provides.

1. Under `Settings → Apps → Sandboxed Google Play`, turn off `Reroute location requests to OS APIs`.
2. Grant Google Play services the `Location` permission with `Allow all the time`, plus the `Nearby Devices` permission.
3. Open `Google Location Accuracy` from the `Sandboxed Google Play` menu and opt in to Google’s options there.
4. To use Wi‑Fi and Bluetooth scanning while Wi‑Fi/Bluetooth are otherwise off, enable the scanning toggles under `Settings → Location → Location services` (off by default on GrapheneOS).

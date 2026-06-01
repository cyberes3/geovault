#!/usr/bin/env python3
"""Split TrackingServiceHost methods into extension files."""

from __future__ import annotations

import re
from pathlib import Path

HOST_PATH = Path(
    "app/src/main/java/com/geovault/tracker/tracking/TrackingServiceHost.kt"
)
TRACKING_DIR = HOST_PATH.parent

# Methods that stay in TrackingServiceHost.kt
KEEP_IN_HOST = {
    "onCreate",
    "onStartCommand",
    "onBind",
    "onTaskRemoved",
    "onDestroy",
}

# method_name -> output file (without .kt)
SPLIT_MAP: dict[str, str] = {
    # lifecycle
    "requestStartTracking": "TrackingSessionLifecycle",
    "performStartTracking": "TrackingSessionLifecycle",
    "stopTracking": "TrackingSessionLifecycle",
    "transitionToStoppedState": "TrackingSessionLifecycle",
    "cleanupServiceResources": "TrackingSessionLifecycle",
    "stopServiceInstance": "TrackingSessionLifecycle",
    "startLocationUpdates": "TrackingSessionLifecycle",
    "stopLocationUpdates": "TrackingSessionLifecycle",
    "setStartupInProgress": "TrackingSessionLifecycle",
    "isTrackingActiveOrStarting": "TrackingSessionLifecycle",
    # commands / wakeup
    "logBackgroundWakeupDiagnostics": "TrackingCommandDispatcher",
    "summarizeLocationForTelemetry": "TrackingCommandDispatcher",
    "handleLocationUpdateCommand": "TrackingCommandDispatcher",
    "handleManualSendPointCommand": "TrackingManualAndWakeupCommands",
    "getManualSendCandidateLocation": "TrackingManualAndWakeupCommands",
    "buildManualSendLocation": "TrackingManualAndWakeupCommands",
    # foreground
    "failStartup": "TrackingForegroundController",
    "failActiveTrackingAndStop": "TrackingForegroundController",
    "promoteToForegroundForStartup": "TrackingForegroundController",
    "stopSelfSafelyAfterStartup": "TrackingForegroundController",
    "logNotificationSurfaceDiagnostics": "TrackingForegroundController",
    # location fix
    "processLocationUpdate": "TrackingLocationFixHandler",
    "processLocationUpdateSerialized": "TrackingLocationFixHandler",
    # paused freshness
    "requestStationaryFreshnessProbe": "PausedFreshnessFixHandler",
    "handlePausedFreshnessProbeFix": "PausedFreshnessFixHandler",
    "markPausedFreshnessProbeStarted": "PausedFreshnessFixHandler",
    "clearPausedFreshnessProbe": "PausedFreshnessFixHandler",
    "logPausedFreshnessDecision": "PausedFreshnessFixHandler",
    "persistPausedFreshnessPoint": "PausedFreshnessFixHandler",
    # GPS collection
    "enterWaitingForGpsProvider": "GpsCollectionController",
    "resumeFromGpsProviderWait": "GpsCollectionController",
    "transitionGpsState": "GpsCollectionController",
    "pauseGps": "GpsCollectionController",
    "pauseGpsInternal": "GpsCollectionController",
    "resumeGps": "GpsCollectionController",
    "startSensorWatchdog": "GpsCollectionController",
    "requestStationaryFreshnessProbeIfDue": "GpsCollectionController",
    "ensureGpsProviderReceiverRegistered": "GpsCollectionController",
    "unregisterGpsProviderReceiverIfNeeded": "GpsCollectionController",
    "enterStationaryRegion": "GpsCollectionController",
    # location requests
    "applyCurrentLocationRequest": "TrackingLocationRequestController",
    "reapplyLocationRequestIfActive": "TrackingLocationRequestController",
    "shouldDebounceLocationRequestReapply": "TrackingLocationRequestController",
    "scheduleLocationRequestReapplyRetry": "TrackingLocationRequestController",
    "startFixDeliveryWatchdog": "TrackingLocationRequestController",
    "expectsActiveFixDelivery": "TrackingLocationRequestController",
    "resolveLocationRequestFailureMessage": "TrackingLocationRequestController",
    # fast lock
    "maybeStartFastGpsLockWindow": "FastGpsLockController",
    "shouldSuppressFastLockForAutoMotion": "FastGpsLockController",
    "startFastGpsLockBurst": "FastGpsLockController",
    "stopFastGpsLockWindow": "FastGpsLockController",
    "resetFastGpsLockSamples": "FastGpsLockController",
    "recordFastGpsLockSample": "FastGpsLockController",
    "selectBestFastGpsLockSample": "FastGpsLockController",
    "isFreshAccurateLocation": "FastGpsLockController",
    "isMoreAccurateSample": "FastGpsLockController",
    "isFresherSample": "FastGpsLockController",
    "maybeLogFastGpsLockSummary": "FastGpsLockController",
    "selectPreferredFastGpsSample": "FastGpsLockController",
    "selectMoreAccurateLocation": "FastGpsLockController",
    "selectNewerTimestampLocation": "FastGpsLockController",
    "hasRecoveredFastGpsLock": "FastGpsLockController",
    # fallback
    "ensureLowAccuracyFallbackTimerRunning": "LowAccuracyFallbackRunner",
    "cancelLowAccuracyFallbackTimer": "LowAccuracyFallbackRunner",
    "logFallbackWait": "LowAccuracyFallbackRunner",
    "selectLowAccuracyFallbackCandidate": "LowAccuracyFallbackRunner",
    "maybeLogFallbackRejectSummary": "LowAccuracyFallbackRunner",
    "shouldEmitFallbackForTransition": "LowAccuracyFallbackRunner",
    "shouldPersistFallbackPoint": "LowAccuracyFallbackRunner",
    # upload
    "pushQueuedLocations": "TrackingUploadCoordinator",
    "applyQueueUploadResult": "TrackingUploadCoordinator",
    "logQueueUploadResult": "TrackingUploadCoordinator",
    "trimQueuedLocationsRetention": "TrackingUploadCoordinator",
    "updateUploadQueueCounts": "TrackingUploadCoordinator",
    "getAuthenticatedHttpClient": "TrackingUploadCoordinator",
    "startRetryJob": "TrackingUploadCoordinator",
    "stopRetryJob": "TrackingUploadCoordinator",
    "startBacklogUploader": "TrackingUploadCoordinator",
    "stopBacklogUploader": "TrackingUploadCoordinator",
    "startPreflightMonitor": "TrackingUploadCoordinator",
    "stopPreflightMonitor": "TrackingUploadCoordinator",
    # runtime projection
    "updateNotificationFromDb": "TrackingRuntimeProjection",
    "broadcastSessionStats": "TrackingRuntimeProjection",
    "updateRuntimeSnapshot": "TrackingRuntimeProjection",
    "applyAccuracyHoldUpdate": "TrackingRuntimeProjection",
    "syncRuntimeStateStore": "TrackingRuntimeProjection",
    "maybeLogPositioningDiagnosticSnapshot": "TrackingRuntimeProjection",
    "transitionControlState": "TrackingRuntimeProjection",
    "validateRuntimeInvariant": "TrackingRuntimeProjection",
    "restoreLocalFreshnessFromDatabase": "TrackingRuntimeProjection",
    "buildAccuracyHoldLogKey": "TrackingRuntimeProjection",
    "accuracyMetersBucket": "TrackingRuntimeProjection",
    "logPointEmissionTroubleTransition": "TrackingRuntimeProjection",
    # positioning context
    "effectivePositioningPreset": "TrackingPositioningContext",
    "resolvePointFreshnessIntervalSec": "TrackingPositioningContext",
    "resolveActiveMotionMode": "TrackingPositioningContext",
    "startSparseTrackingObserver": "TrackingPositioningContext",
    "onSparseTrackingChanged": "TrackingPositioningContext",
    "currentPositioningRecoveryConfig": "TrackingPositioningContext",
    "currentPositioningRuntimeContext": "TrackingPositioningContext",
    "resolvePointEmissionTrouble": "TrackingPositioningContext",
    "maybeLogFreshnessProbeDecision": "TrackingPositioningContext",
    "buildFreshnessRecoveryLocation": "TrackingPositioningContext",
    "updateRecoveryAnchor": "TrackingPositioningContext",
    # adaptation / auto motion
    "handleAutoMotionRejectedFix": "TrackingAdaptationController",
    "processAutoTrackingOutput": "TrackingAdaptationController",
    "maybeApplyElasticDistanceFilter": "TrackingAdaptationController",
    "resetElasticDistanceOverride": "TrackingAdaptationController",
    "startAutoModeTickIfNeeded": "TrackingAdaptationController",
    "stopAutoModeTick": "TrackingAdaptationController",
    "computeElasticitySpeedBucket": "TrackingAdaptationController",
    "computeElasticDistanceFilterMeters": "TrackingAdaptationController",
    # recovery heartbeat
    "startRecoveryHeartbeat": "TrackingRecoveryJobs",
    "stopRecoveryHeartbeat": "TrackingRecoveryJobs",
    # utilities
    "readBatteryLevel": "TrackingHostUtilities",
    "isCharging": "TrackingHostUtilities",
    "isGpsProviderEnabled": "TrackingHostUtilities",
    "publishTrackPoint": "TrackingHostUtilities",
    "resolveTrackPointQuality": "TrackingHostUtilities",
    "triggerLightHaptic": "TrackingHostUtilities",
    "buildLocalPointPropsJson": "TrackingHostUtilities",
    "getDeviceIdentifier": "TrackingHostUtilities",
    "isWaitingForProviderState": "TrackingHostUtilities",
    "resolveObservedSpeedMps": "TrackingHostUtilities",
}

METHOD_START = re.compile(
    r"^    (private suspend fun|private fun|internal suspend fun|internal fun) (\w+)"
)


def extract_methods(lines: list[str]) -> dict[str, tuple[int, int, str]]:
    """Return method_name -> (start_line_idx, end_line_idx_exclusive, full_text)."""
    methods: dict[str, tuple[int, int, str]] = {}
    i = 0
    while i < len(lines):
        m = METHOD_START.match(lines[i])
        if m:
            name = m.group(2)
            start = i
            # find opening brace of function
            j = i
            while j < len(lines) and "{" not in lines[j]:
                j += 1
            if j >= len(lines):
                break
            depth = 0
            k = j
            while k < len(lines):
                depth += lines[k].count("{") - lines[k].count("}")
                if depth == 0 and k > j:
                    end = k + 1
                    methods[name] = (start, end, "".join(lines[start:end]))
                    i = end
                    break
                k += 1
            else:
                i += 1
        else:
            i += 1
    return methods


def to_extension_method(block: str) -> str:
    """Convert private method inside class to internal extension on host."""
    lines = block.splitlines(keepends=True)
    first = lines[0]
    first = first.replace("private suspend fun", "internal suspend fun TrackingServiceHost.")
    first = first.replace("private fun", "internal fun TrackingServiceHost.")
    if "TrackingServiceHost." not in first:
        raise ValueError(f"Could not convert method header: {first!r}")
    lines[0] = first
    return "".join(lines)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    host_path = root / HOST_PATH
    text = host_path.read_text()
    lines = text.splitlines(keepends=True)

    # find class body start/end
    class_start = next(i for i, l in enumerate(lines) if l.startswith("internal class TrackingServiceHost"))
    class_open = next(i for i in range(class_start, len(lines)) if lines[i].strip() == ") {")
    class_close = len(lines) - 1
    while class_close > 0 and lines[class_close].strip() != "}":
        class_close -= 1

    methods = extract_methods(lines[class_open + 1 : class_close])
    by_file: dict[str, list[str]] = {}
    kept_blocks: list[str] = []

    for name, (rel_start, rel_end, block) in sorted(methods.items(), key=lambda x: x[1][0]):
        abs_start = class_open + 1 + rel_start
        abs_end = class_open + 1 + rel_end
        if name in KEEP_IN_HOST:
            kept_blocks.append((abs_start, abs_end, block))
            continue
        target = SPLIT_MAP.get(name)
        if target is None:
            raise SystemExit(f"Unmapped method: {name}")
        by_file.setdefault(target, []).append(to_extension_method(block))

    # rebuild host: remove split methods
    remove_ranges = []
    for name, (rel_start, rel_end, _) in methods.items():
        if name in KEEP_IN_HOST or name not in SPLIT_MAP:
            if name not in KEEP_IN_HOST and name not in SPLIT_MAP:
                raise SystemExit(f"Unmapped method: {name}")
            continue
        abs_start = class_open + 1 + rel_start
        abs_end = class_open + 1 + rel_end
        remove_ranges.append((abs_start, abs_end))
    remove_ranges.sort(reverse=True)
    new_lines = list(lines)
    for start, end in remove_ranges:
        del new_lines[start:end]

    host_path.write_text("".join(new_lines))

    import_block = "".join(lines[0:class_start])
    for file_stem, blocks in sorted(by_file.items()):
        out = TRACKING_DIR / f"{file_stem}.kt"
        body = "\n".join(blocks)
        out.write_text(import_block + "\n" + body)
        print(f"Wrote {out.name} ({len(blocks)} methods)")

    print(f"Host now {len(new_lines)} lines (was {len(lines)})")


if __name__ == "__main__":
    main()

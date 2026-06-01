#!/usr/bin/env python3
"""Migrate tracking extensions to positioning subsystems (one-time big-bang helper)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRACKING = ROOT / "app/src/main/java/com/geovault/tracker/tracking"
POSITIONING = ROOT / "app/src/main/java/com/geovault/tracker/positioning"

EXTENSION_TO_SUBSYSTEM = {
    "TrackingLocationFixHandler.kt": ("ingest", "FixIngestSubsystem.kt", "FixIngestSubsystem"),
    "GpsCollectionController.kt": ("collection", "GpsCollectionSubsystem.kt", "GpsCollectionSubsystem"),
    "TrackingLocationRequestController.kt": ("collection", "LocationRequestSubsystem.kt", "LocationRequestSubsystem"),
    "PausedFreshnessFixHandler.kt": ("recovery", "PausedFreshnessSubsystem.kt", "PausedFreshnessSubsystem"),
    "LowAccuracyFallbackRunner.kt": ("recovery", "LowAccuracyFallbackSubsystem.kt", "LowAccuracyFallbackSubsystem"),
    "FastGpsLockController.kt": ("recovery", "FastGpsLockSubsystem.kt", "FastGpsLockSubsystem"),
    "TrackingAdaptationController.kt": ("motion", "MotionSubsystem.kt", "MotionSubsystem"),
    "TrackingPositioningContext.kt": ("", "PositioningContextBuilder.kt", "PositioningContextBuilder"),
    "TrackingRuntimeProjection.kt": ("", "RuntimeProjectionSubsystem.kt", "RuntimeProjectionSubsystem"),
    "TrackingSessionLifecycle.kt": ("", "SessionLifecycleSubsystem.kt", "SessionLifecycleSubsystem"),
    "TrackingCommandDispatcher.kt": ("", "CommandDiagnosticsSubsystem.kt", "CommandDiagnosticsSubsystem"),
    "TrackingForegroundController.kt": ("", "ForegroundSubsystem.kt", "ForegroundSubsystem"),
    "TrackingManualAndWakeupCommands.kt": ("", "ManualFixSubsystem.kt", "ManualFixSubsystem"),
    "TrackingUploadCoordinator.kt": ("", "UploadSubsystem.kt", "UploadSubsystem"),
    "TrackingRecoveryJobs.kt": ("recovery", "RecoveryJobsSubsystem.kt", "RecoveryJobsSubsystem"),
    "TrackingHostUtilities.kt": ("", "PositioningHostUtilities.kt", "PositioningHostUtilities"),
}

# Methods that live on other subsystems (called from extension bodies)
CROSS_SUBSYSTEM_CALLS = {
    "transitionGpsState": "rt.collection",
    "enterWaitingForGpsProvider": "rt.collection",
    "resumeFromGpsProviderWait": "rt.collection",
    "pauseGps": "rt.collection",
    "pauseGpsInternal": "rt.collection",
    "resumeGps": "rt.collection",
    "enterStationaryRegion": "rt.collection",
    "ensureGpsProviderReceiverRegistered": "rt.collection",
    "unregisterGpsProviderReceiverIfNeeded": "rt.collection",
    "startSensorWatchdog": "rt.collection",
    "requestStationaryFreshnessProbeIfDue": "rt.collection",
    "applyCurrentLocationRequest": "rt.locationRequests",
    "reapplyLocationRequestIfActive": "rt.locationRequests",
    "scheduleLocationRequestReapplyRetry": "rt.locationRequests",
    "startFixDeliveryWatchdog": "rt.locationRequests",
    "expectsActiveFixDelivery": "rt.locationRequests",
    "processLocationUpdate": "rt.fixIngest",
    "processLocationUpdateSerialized": "rt.fixIngest",
    "handlePausedFreshnessProbeFix": "rt.pausedFreshness",
    "requestStationaryFreshnessProbe": "rt.pausedFreshness",
    "clearPausedFreshnessProbe": "rt.pausedFreshness",
    "persistPausedFreshnessPoint": "rt.pausedFreshness",
    "maybeStartFastGpsLockWindow": "rt.fastLock",
    "stopFastGpsLockWindow": "rt.fastLock",
    "ensureLowAccuracyFallbackTimerRunning": "rt.fallback",
    "cancelLowAccuracyFallbackTimer": "rt.fallback",
    "processAutoTrackingOutput": "rt.motion",
    "maybeApplyElasticDistanceFilter": "rt.motion",
    "resetElasticDistanceOverride": "rt.motion",
    "currentPositioningRuntimeContext": "rt.contextBuilder",
    "currentPositioningRecoveryConfig": "rt.contextBuilder",
    "resolveActiveMotionMode": "rt.contextBuilder",
    "updateRuntimeSnapshot": "rt.projection",
    "syncRuntimeStateStore": "rt.projection",
    "updateNotificationFromDb": "rt.projection",
    "broadcastSessionStats": "rt.projection",
    "applyAccuracyHoldUpdate": "rt.projection",
    "transitionControlState": "rt.projection",
    "restoreLocalFreshnessFromDatabase": "rt.projection",
    "pushQueuedLocations": "rt.upload",
    "getAuthenticatedHttpClient": "rt.upload",
    "startRecoveryHeartbeat": "rt.recoveryJobs",
    "stopRecoveryHeartbeat": "rt.recoveryJobs",
    "startRetryJob": "rt.upload",
    "stopRetryJob": "rt.upload",
    "startBacklogUploader": "rt.upload",
    "stopBacklogUploader": "rt.upload",
    "startPreflightMonitor": "rt.upload",
    "stopPreflightMonitor": "rt.upload",
    "publishTrackPoint": "rt.utilities",
    "buildLocalPointPropsJson": "rt.utilities",
    "resolveTrackPointQuality": "rt.utilities",
    "isGpsProviderEnabled": "rt.utilities",
    "readBatteryLevel": "rt.utilities",
    "isCharging": "rt.utilities",
    "getDeviceIdentifier": "rt.utilities",
    "isWaitingForProviderState": "rt.utilities",
    "resolveObservedSpeedMps": "rt.utilities",
    "buildFreshnessRecoveryLocation": "rt.contextBuilder",
    "updateRecoveryAnchor": "rt.contextBuilder",
    "performStartTracking": "rt.lifecycle",
    "stopTracking": "rt.lifecycle",
    "startLocationUpdates": "rt.lifecycle",
    "stopLocationUpdates": "rt.lifecycle",
    "failStartup": "rt.foreground",
    "failActiveTrackingAndStop": "rt.foreground",
    "promoteToForegroundForStartup": "rt.foreground",
}

STATE_FIELDS = {
    "isTracking", "startupInProgress", "startupReadyForEvents", "controlState",
    "startupForegroundPromoted", "sessionVisibleBoundaryId", "sessionBoundaryForBacklogId",
    "lastFilteredLocation", "latestObservedRawLocation", "lowAccuracyFallbackCandidate",
    "lowAccuracyFallbackTimerArmedAtMs", "lowAccuracyFallbackEmitCountThisSession",
    "lowAccuracyFallbackArmCountThisSession", "lowAccuracyFallbackCancelCountThisSession",
    "lowAccuracyFallbackRejectedFixCountThisSession", "lowAccuracyFallbackLastRejectSummaryAtMs",
    "lastLowAccuracyFallbackWaitReason", "lowAccuracyFallbackJob", "lastLoggedPointEmissionTrouble",
    "lastAccuracyHoldLogKey", "lastLocationFilterLogSignature", "lastPositioningDiagnosticSnapshotKey",
    "lastAutoModeChangedAtMs", "autoModeTickJob", "locationRequestReapplyRetryJob",
    "lastAppliedLocationRequestKey", "lastLocationRequestAppliedAtMs", "lastFixDeliveryAtMs",
    "fixDeliveryWatchdogJob", "elasticDistanceOverrideMeters", "elasticitySpeedBucket",
    "lastSpeedReferenceLocation", "isFastGpsLockWindowActive", "isFastGpsLockPriming",
    "fastGpsLockWindowJob", "fastGpsLockSampleCount", "fastGpsLockPreferredSample",
    "fastGpsLockBestAccuracySample", "fastGpsLockFreshestSample", "fastGpsLockNewestSample",
    "fastGpsLockStartCountThisSession", "fastGpsLockStopCountThisSession",
    "fastGpsLockTimeoutCountThisSession", "fastGpsLockLastSummaryAtMs", "sigMotionSensorStartTime",
    "watchdogJob", "consecutiveStationaryPoints", "stationaryAnchorLocation",
    "consecutivePushFailures", "lastSyncFailureClass", "gpsRuntimeState", "trackingGeneration",
    "runtimeSnapshot", "recoveryAnchorState", "uploadLivenessState", "recoveryHeartbeatJob",
    "retryJob", "backlogUploaderJob", "preflightJob", "sparseTrackingObserverJob",
    "gpsProviderReceiverRegistered",
}

DEPS_FIELDS = {
    "database", "settingsRepository", "sessionCoordinator", "locationIngestCoordinator",
    "trackerLocationPipeline", "notificationPresenter", "runtimeEventPublisher",
    "queueUploadEngine", "locationSessionCoordinator", "runtimeTelemetry", "recoveryAnchorStore",
    "stationaryPingController", "stationaryFreshnessCoordinator", "httpClient",
    "significantMotionBridge", "lowAccuracyFallbackCoordinator", "repeatedOutlierSuppressor",
    "freshnessRecoveryController", "providerHealthController", "pointFreshnessTracker",
    "autoTrackingMotionEngine", "autoTrackingMotionCoordinator",
}

PORTS_FIELDS = {"service"}


def strip_import_block(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out = []
    in_imports = True
    for line in lines:
        if in_imports and (line.startswith("import ") or line.startswith("package ") or line.strip() == ""):
            if line.startswith("package "):
                continue
            if line.startswith("import "):
                continue
            if line.strip() == "":
                continue
        in_imports = False
        out.append(line)
    return "".join(out)


def convert_body(body: str, class_name: str) -> str:
    body = re.sub(
        r"internal (suspend )?fun TrackingServiceHost\.(\w+)",
        lambda m: f"{'suspend ' if m.group(1) else ''}fun {class_name}.{m.group(2)}",
        body,
    )
    for method, prefix in sorted(CROSS_SUBSYSTEM_CALLS.items(), key=lambda x: -len(x[0])):
        body = re.sub(rf"(?<![.\w]){method}\(", rf"{prefix}.{method}(", body)
    for field in sorted(STATE_FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![.\w]){field}\b", f"rt.state.{field}", body)
    for field in sorted(DEPS_FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![.\w]){field}\b", f"rt.deps.{field}", body)
    for field in PORTS_FIELDS:
        body = re.sub(rf"(?<![.\w]){field}\b", f"rt.ports.{field}", body)
    body = body.replace("rt.state.runtimeSnapshotLock", "rt.state.runtimeSnapshotLock")
    body = body.replace("rt.state.startupStateLock", "rt.state.startupStateLock")
    body = body.replace("rt.state.localTrackPointOrderingCounter", "rt.state.localTrackPointOrderingCounter")
    body = body.replace("locationUpdateMutex", "rt.locationUpdateMutex")
    body = body.replace("pushDispatcher", "rt.pushDispatcher")
    body = body.replace("serviceScope", "rt.serviceScope")
    body = body.replace("ingestScope", "rt.ingestScope")
    body = body.replace("gpsProviderReceiver", "rt.gpsProviderReceiver")
    return body


def migrate_extension(filename: str, subdir: str, target_name: str, class_name: str) -> None:
    src = TRACKING / filename
    if not src.exists():
        print(f"skip missing {filename}")
        return
    text = src.read_text()
    body = strip_import_block(text)
    body = convert_body(body, class_name)
    out_dir = POSITIONING / subdir if subdir else POSITIONING
    out_dir.mkdir(parents=True, exist_ok=True)
    wrapped = (
        f"package com.geovault.tracker.positioning"
        f"{'.' + subdir if subdir else ''}\n\n"
        f"import com.geovault.tracker.positioning.PositioningRuntime\n\n"
        f"internal class {class_name}(private val rt: PositioningRuntime) {{\n"
        f"{body}\n"
        f"}}\n"
    )
    (out_dir / target_name).write_text(wrapped)
    print(f"wrote {out_dir / target_name}")


def main() -> None:
    POSITIONING.mkdir(parents=True, exist_ok=True)
    for filename, (subdir, target, cls) in EXTENSION_TO_SUBSYSTEM.items():
        migrate_extension(filename, subdir, target, cls)


if __name__ == "__main__":
    main()

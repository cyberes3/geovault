#!/usr/bin/env python3
"""Convert PositioningRuntime extension files into subsystem classes."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POSITIONING = ROOT / "app/src/main/java/com/geovault/tracker/positioning"

# (source_filename_in_positioning_root, subpackage, target_filename, class_name)
SOURCES = [
    ("TrackingHostUtilities.kt", "", "PositioningHostUtilities.kt", "PositioningHostUtilities"),
    ("TrackingPositioningContext.kt", "", "PositioningContextBuilder.kt", "PositioningContextBuilder"),
    ("TrackingRuntimeProjection.kt", "", "RuntimeProjectionSubsystem.kt", "RuntimeProjectionSubsystem"),
    ("GpsCollectionSubsystem.kt", "collection", "GpsCollectionSubsystem.kt", "GpsCollectionSubsystem"),
    ("LocationRequestSubsystem.kt", "collection", "LocationRequestSubsystem.kt", "LocationRequestSubsystem"),
    ("FastGpsLockSubsystem.kt", "recovery", "FastGpsLockSubsystem.kt", "FastGpsLockSubsystem"),
    ("LowAccuracyFallbackSubsystem.kt", "recovery", "LowAccuracyFallbackSubsystem.kt", "LowAccuracyFallbackSubsystem"),
    ("PausedFreshnessFixHandler.kt", "recovery", "PausedFreshnessSubsystem.kt", "PausedFreshnessSubsystem"),
    ("RecoveryJobsSubsystem.kt", "recovery", "RecoveryJobsSubsystem.kt", "RecoveryJobsSubsystem"),
    ("MotionSubsystem.kt", "motion", "MotionSubsystem.kt", "MotionSubsystem"),
    ("FixIngestSubsystem.kt", "ingest", "FixIngestSubsystem.kt", "FixIngestSubsystem"),
    ("TrackingSessionLifecycle.kt", "", "SessionLifecycleSubsystem.kt", "SessionLifecycleSubsystem"),
    ("TrackingForegroundController.kt", "", "ForegroundSubsystem.kt", "ForegroundSubsystem"),
    ("TrackingCommandDispatcher.kt", "", "CommandDiagnosticsSubsystem.kt", "CommandDiagnosticsSubsystem"),
    ("TrackingManualAndWakeupCommands.kt", "", "ManualFixSubsystem.kt", "ManualFixSubsystem"),
    ("TrackingUploadCoordinator.kt", "", "UploadSubsystem.kt", "UploadSubsystem"),
]

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
    "shouldDebounceLocationRequestReapply": "rt.locationRequests",
    "resolveLocationRequestFailureMessage": "rt.locationRequests",
    "processLocationUpdate": "rt.fixIngest",
    "processLocationUpdateSerialized": "rt.fixIngest",
    "handlePausedFreshnessProbeFix": "rt.recovery.pausedFreshness",
    "requestStationaryFreshnessProbe": "rt.recovery.pausedFreshness",
    "clearPausedFreshnessProbe": "rt.recovery.pausedFreshness",
    "persistPausedFreshnessPoint": "rt.recovery.pausedFreshness",
    "markPausedFreshnessProbeStarted": "rt.recovery.pausedFreshness",
    "logPausedFreshnessDecision": "rt.recovery.pausedFreshness",
    "maybeStartFastGpsLockWindow": "rt.recovery.fastLock",
    "stopFastGpsLockWindow": "rt.recovery.fastLock",
    "resetFastGpsLockSamples": "rt.recovery.fastLock",
    "recordFastGpsLockSample": "rt.recovery.fastLock",
    "ensureLowAccuracyFallbackTimerRunning": "rt.recovery.fallback",
    "cancelLowAccuracyFallbackTimer": "rt.recovery.fallback",
    "selectLowAccuracyFallbackCandidate": "rt.recovery.fallback",
    "logFallbackWait": "rt.recovery.fallback",
    "maybeLogFallbackRejectSummary": "rt.recovery.fallback",
    "shouldEmitFallbackForTransition": "rt.recovery.fallback",
    "shouldPersistFallbackPoint": "rt.recovery.fallback",
    "processAutoTrackingOutput": "rt.motion",
    "maybeApplyElasticDistanceFilter": "rt.motion",
    "resetElasticDistanceOverride": "rt.motion",
    "handleAutoMotionRejectedFix": "rt.motion",
    "startAutoModeTickIfNeeded": "rt.motion",
    "stopAutoModeTick": "rt.motion",
    "currentPositioningRuntimeContext": "rt.contextBuilder",
    "currentPositioningRecoveryConfig": "rt.contextBuilder",
    "resolveActiveMotionMode": "rt.contextBuilder",
    "resolvePointFreshnessIntervalSec": "rt.contextBuilder",
    "effectivePositioningPreset": "rt.contextBuilder",
    "buildFreshnessRecoveryLocation": "rt.contextBuilder",
    "updateRecoveryAnchor": "rt.contextBuilder",
    "resolvePointEmissionTrouble": "rt.contextBuilder",
    "maybeLogFreshnessProbeDecision": "rt.contextBuilder",
    "startSparseTrackingObserver": "rt.contextBuilder",
    "onSparseTrackingChanged": "rt.contextBuilder",
    "updateRuntimeSnapshot": "rt.projection",
    "syncRuntimeStateStore": "rt.projection",
    "updateNotificationFromDb": "rt.projection",
    "broadcastSessionStats": "rt.projection",
    "applyAccuracyHoldUpdate": "rt.projection",
    "transitionControlState": "rt.projection",
    "restoreLocalFreshnessFromDatabase": "rt.projection",
    "maybeLogPositioningDiagnosticSnapshot": "rt.projection",
    "validateRuntimeInvariant": "rt.projection",
    "logPointEmissionTroubleTransition": "rt.projection",
    "pushQueuedLocations": "rt.upload",
    "getAuthenticatedHttpClient": "rt.upload",
    "applyQueueUploadResult": "rt.upload",
    "startRecoveryHeartbeat": "rt.recovery.jobs",
    "stopRecoveryHeartbeat": "rt.recovery.jobs",
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
    "triggerLightHaptic": "rt.utilities",
    "performStartTracking": "rt.lifecycle",
    "stopTracking": "rt.lifecycle",
    "transitionToStoppedState": "rt.lifecycle",
    "requestStartTracking": "rt.lifecycle",
    "cleanupServiceResources": "rt.lifecycle",
    "stopServiceInstance": "rt.lifecycle",
    "startLocationUpdates": "rt.lifecycle",
    "stopLocationUpdates": "rt.lifecycle",
    "setStartupInProgress": "rt.lifecycle",
    "isTrackingActiveOrStarting": "rt.lifecycle",
    "failStartup": "rt.foreground",
    "failActiveTrackingAndStop": "rt.foreground",
    "promoteToForegroundForStartup": "rt.foreground",
    "stopSelfSafelyAfterStartup": "rt.foreground",
    "logNotificationSurfaceDiagnostics": "rt.foreground",
    "handleManualSendPointCommand": "rt.manualFix",
    "getManualSendCandidateLocation": "rt.manualFix",
    "buildManualSendLocation": "rt.manualFix",
    "logBackgroundWakeupDiagnostics": "rt.commands",
    "summarizeLocationForTelemetry": "rt.commands",
    "handleLocationUpdateCommand": "rt.commands",
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

RUNTIME_FIELDS = {
    "locationUpdateMutex", "pushDispatcher", "serviceScope", "ingestScope", "gpsProviderReceiver",
    "locationListener",
}


def strip_import_block(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    in_imports = True
    for line in lines:
        if in_imports:
            if line.startswith("package ") or line.startswith("import ") or line.strip() == "":
                continue
            in_imports = False
        out.append(line)
    return "".join(out)


def convert_body(body: str, class_name: str) -> str:
    body = re.sub(
        r"internal (suspend )?fun PositioningRuntime\.(\w+)",
        lambda m: f"{'suspend ' if m.group(1) else ''}fun {m.group(2)}",
        body,
    )
    body = re.sub(
        r"^internal (suspend )?fun PositioningRuntime\.(\w+)",
        lambda m: f"{'suspend ' if m.group(1) else ''}fun {m.group(2)}",
        body,
        flags=re.MULTILINE,
    )
    for method, prefix in sorted(CROSS_SUBSYSTEM_CALLS.items(), key=lambda x: -len(x[0])):
        body = re.sub(rf"(?<![.\w]){method}\(", rf"{prefix}.{method}(", body)
    for field in sorted(STATE_FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![.\w]){field}\b", f"rt.state.{field}", body)
    for field in sorted(DEPS_FIELDS, key=len, reverse=True):
        body = re.sub(rf"(?<![.\w]){field}\b", f"rt.deps.{field}", body)
    body = re.sub(r"(?<![.\w])service\b", "rt.ports.service", body)
    for field in RUNTIME_FIELDS:
        body = re.sub(rf"(?<![.\w]){field}\b", f"rt.{field}", body)
    body = body.replace("rt.state.runtimeSnapshotLock", "rt.state.runtimeSnapshotLock")
    body = body.replace("rt.state.startupStateLock", "rt.state.startupStateLock")
    return body


def migrate_source(src_name: str, subdir: str, target_name: str, class_name: str) -> None:
    src = POSITIONING / src_name
    if not src.exists():
        print(f"skip missing {src_name}")
        return
    text = src.read_text()
    body = strip_import_block(text)
    body = convert_body(body, class_name)
    out_dir = POSITIONING / subdir if subdir else POSITIONING
    out_dir.mkdir(parents=True, exist_ok=True)
    pkg = "com.geovault.tracker.positioning" + (f".{subdir}" if subdir else "")
    wrapped = (
        f"package {pkg}\n\n"
        f"import com.geovault.tracker.positioning.PositioningRuntime\n\n"
        f"internal class {class_name}(private val rt: PositioningRuntime) {{\n"
        f"{body}\n"
        f"}}\n"
    )
    out_path = out_dir / target_name
    out_path.write_text(wrapped)
    if src_name != target_name or (subdir and str(src.parent) != str(out_dir)):
        if src.resolve() != out_path.resolve() and src.exists():
            src.unlink()
    print(f"wrote {out_path}")


def main() -> None:
    for src, subdir, target, cls in SOURCES:
        migrate_source(src, subdir, target, cls)


if __name__ == "__main__":
    main()

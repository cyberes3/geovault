#!/usr/bin/env python3
"""
Extract anonymized end-to-end positioning replay artifacts from tracker capture logs.

Requires positioning_raw_fix lines in the capture log (logged at FixIngestSubsystem
entry). One-way anonymization: random ~900-1100 mi translation applied in memory only.
Original coordinates, the translation vector, and random seed are never written. The
real on-device trackId is likewise never written; a fresh random UUID is substituted
so a fixture cannot be correlated back to the source device's other tracks.

Usage:
  python3 scripts/extract_capture_replay.py write LOG \\
    --session SESSION --start ISO --end ISO --output PATH.json --settings-json PATH.json
  python3 scripts/extract_capture_replay.py check LOG \\
    --session SESSION --start ISO --end ISO --output PATH.json --settings-json PATH.json
  python3 scripts/extract_capture_replay.py validate \\
    --session SESSION --output PATH.json
"""

from __future__ import annotations

import argparse
import gzip
import json
import math
import re
import secrets
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator, TextIO

METERS_PER_MILE = 1609.344
MILES_MIN = 900.0
MILES_MAX = 1100.0
METERS_PER_DEGREE_LAT = 111_320.0
SCHEMA_VERSION = 1
EXTRACTOR_VERSION = "schema-v1"

ISO_TS_RE = re.compile(r"^(?P<iso>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)\s+")

RAW_FIX_RE = re.compile(
    r"positioning_raw_fix\s+"
    r"track=(?P<track>\S*)\s+"
    r"wall=(?P<wall>\d+)\s+"
    r"elapsedNanos=(?P<elapsedNanos>\d+)\s+"
    r"time=(?P<time>\d+)\s+"
    r"lat=(?P<lat>[-\d.eE+]+)\s+"
    r"lon=(?P<lon>[-\d.eE+]+)\s+"
    r"acc=(?P<acc>[-\d.eE+]+)\s+"
    r"speed=(?P<speed>\S+)\s+"
    r"bearing=(?P<bearing>\S+)\s+"
    r"provider=(?P<provider>\S+)\s+"
    r"mock=(?P<mock>true|false)\s+"
    r"gpsState=(?P<gpsState>\S+)\s+"
    r"trackingGeneration=(?P<trackingGeneration>-?\d+)\s+"
    r"allowWhenGpsPaused=(?P<allowWhenGpsPaused>true|false)\s+"
    r"bypassFilters=(?P<bypassFilters>true|false)\s+"
    r"skipAdaptiveTrackingEffects=(?P<skipAdaptiveTrackingEffects>true|false)\s+"
    r"propsKind=(?P<propsKind>\S+)"
)

TRACE_RE = re.compile(
    r"positioning_decision_trace\s+"
    r"track=(?P<track>[^\s]+)\s+"
    r"ts=(?P<ts>\d+)\s+"
    r"lat=(?P<lat>[-\d.eE+]+)\s+"
    r"lon=(?P<lon>[-\d.eE+]+)\s+"
    r"acc=(?P<acc>[-\d.eE+]+)\s+"
    r"accepted=(?P<accepted>true|false)\s+"
    r"emission=(?P<emission>\S+)\s+"
    r"reject=(?P<reject>\S+)\s+"
    r"policy=(?P<policy>\S+)\s+"
    r"raw=(?P<raw>[-\d.eE+]+)\s+"
    r"effective=(?P<effective>[-\d.eE+]+)\s+"
    r"dt=(?P<dt>[-\d.eE+]+)\s+"
    r"speed=(?P<speed>[-\d.eE+]+)\s+"
    r"committedLat=(?P<committedLat>\S+)\s+"
    r"committedLon=(?P<committedLon>\S+)\s+"
    r"now=(?P<now>\d+)"
)

MOTION_EVIDENCE_RE = re.compile(
    r"auto_motion_evidence\s+"
    r"modeBefore=(?P<modeBefore>\S+)\s+"
    r"modeAfter=(?P<modeAfter>\S+)\s+"
    r"reason=(?P<reason>\S+)\s+"
    r"speed=(?P<speed>[-\d.eE+]+)\s+"
    r"accuracy=(?P<accuracy>[-\d.eE+]+)\s+"
    r"dt=(?P<dt>[-\d.eE+]+)\s+"
    r"path=(?P<path>\S+)"
)

MODE_CHANGED_RE = re.compile(
    r"auto_mode_changed\s+"
    r"mode=(?P<mode>\S+)\s+"
    r"reason=(?P<reason>\S+)\s+"
    r"path=(?P<path>\S+)"
)

IMU_CLASSIFICATION_RE = re.compile(
    r"positioning_imu_classification\s+"
    r"track=(?P<track>\S*)\s+"
    r"wall=(?P<wall>\d+)\s+"
    r"elapsedNanos=(?P<elapsedNanos>\d+)\s+"
    r"class=(?P<cls>\S+)\s+"
    r"confidence=(?P<confidence>[-\d.eE+]+)\s+"
    r"variance=(?P<variance>[-\d.eE+]+)\s+"
    r"stepRate=(?P<stepRate>[-\d.eE+]+)"
)

@dataclass
class RawFix:
    track_id: str
    wall_ms: int
    elapsed_realtime_nanos: int
    gps_time_ms: int
    lat: float
    lon: float
    accuracy: float
    speed_mps: float | None
    bearing_deg: float | None
    provider: str
    mock: bool
    gps_state: str
    tracking_generation: int
    allow_when_gps_paused: bool
    bypass_filters: bool
    skip_adaptive_tracking_effects: bool
    props_kind: str


@dataclass
class DecisionTrace:
    track_id: str
    wall_ms: int
    gps_time_ms: int
    lat: float
    lon: float
    accuracy: float
    accepted: bool
    emission: str
    reject: str
    policy: str
    raw_distance_meters: float
    effective_distance_meters: float
    elapsed_seconds: float
    implied_speed_mps: float
    committed_lat: float | None
    committed_lon: float | None


@dataclass
class Milestone:
    wall_ms: int
    kind: str
    mode_before: str | None
    mode_after: str | None
    reason: str | None
    speed_mps: float | None
    accuracy_meters: float | None
    elapsed_seconds: float | None
    path: str | None


@dataclass
class ImuClassificationEvent:
    track_id: str
    wall_ms: int
    elapsed_realtime_nanos: int
    classification: str
    confidence: float
    variance: float
    step_rate: float


@dataclass
class CaptureEvents:
    raw_fixes: list[RawFix]
    decision_traces: list[DecisionTrace]
    milestones: list[Milestone]
    imu_events: list[ImuClassificationEvent]


class CaptureLogReader:
    def iter_window(self, path: Path, start_ms: int, end_ms: int) -> Iterator[tuple[int, str]]:
        with self._open(path) as stream:
            for line in stream:
                wall_ms = self._line_wall_ms(line)
                if wall_ms is None or wall_ms < start_ms or wall_ms > end_ms:
                    continue
                yield wall_ms, line

    def _open(self, path: Path) -> TextIO:
        if path.suffix == ".gz":
            return gzip.open(path, "rt", encoding="utf-8", errors="replace")
        return path.open("r", encoding="utf-8", errors="replace")

    def _line_wall_ms(self, line: str) -> int | None:
        match = ISO_TS_RE.match(line)
        if not match:
            return None
        return parse_iso_ms(match.group("iso"))


class CaptureEventParser:
    def parse(self, log_path: Path, track_id: str | None, start_ms: int, end_ms: int) -> CaptureEvents:
        raw_fixes: list[RawFix] = []
        decision_traces: list[DecisionTrace] = []
        milestones: list[Milestone] = []
        imu_events: list[ImuClassificationEvent] = []
        reader = CaptureLogReader()
        for wall_ms, line in reader.iter_window(log_path, start_ms, end_ms):
            raw_match = RAW_FIX_RE.search(line)
            if raw_match:
                parsed = self._raw_fix(raw_match)
                if track_id is None or parsed.track_id == track_id:
                    raw_fixes.append(parsed)
                continue

            imu_match = IMU_CLASSIFICATION_RE.search(line)
            if imu_match:
                parsed = self._imu_event(imu_match)
                if track_id is None or parsed.track_id == track_id:
                    imu_events.append(parsed)
                continue

            trace_match = TRACE_RE.search(line)
            if trace_match:
                parsed = self._decision_trace(trace_match)
                if track_id is None or parsed.track_id == track_id:
                    decision_traces.append(parsed)
                continue

            evidence_match = MOTION_EVIDENCE_RE.search(line)
            if evidence_match:
                milestones.append(
                    Milestone(
                        wall_ms=wall_ms,
                        kind="auto_motion_evidence",
                        mode_before=evidence_match.group("modeBefore"),
                        mode_after=evidence_match.group("modeAfter"),
                        reason=evidence_match.group("reason"),
                        speed_mps=float(evidence_match.group("speed")),
                        accuracy_meters=float(evidence_match.group("accuracy")),
                        elapsed_seconds=float(evidence_match.group("dt")),
                        path=evidence_match.group("path"),
                    )
                )
                continue

            mode_match = MODE_CHANGED_RE.search(line)
            if mode_match:
                milestones.append(
                    Milestone(
                        wall_ms=wall_ms,
                        kind="auto_mode_changed",
                        mode_before=None,
                        mode_after=mode_match.group("mode"),
                        reason=mode_match.group("reason"),
                        speed_mps=None,
                        accuracy_meters=None,
                        elapsed_seconds=None,
                        path=mode_match.group("path"),
                    )
                )
        return CaptureEvents(
            raw_fixes=raw_fixes,
            decision_traces=decision_traces,
            milestones=milestones,
            imu_events=imu_events,
        )

    def _imu_event(self, match: re.Match[str]) -> ImuClassificationEvent:
        return ImuClassificationEvent(
            track_id=match.group("track"),
            wall_ms=int(match.group("wall")),
            elapsed_realtime_nanos=int(match.group("elapsedNanos")),
            classification=match.group("cls"),
            confidence=float(match.group("confidence")),
            variance=float(match.group("variance")),
            step_rate=float(match.group("stepRate")),
        )

    def _raw_fix(self, match: re.Match[str]) -> RawFix:
        return RawFix(
            track_id=match.group("track"),
            wall_ms=int(match.group("wall")),
            elapsed_realtime_nanos=int(match.group("elapsedNanos")),
            gps_time_ms=int(match.group("time")),
            lat=float(match.group("lat")),
            lon=float(match.group("lon")),
            accuracy=float(match.group("acc")),
            speed_mps=parse_optional_float(match.group("speed")),
            bearing_deg=parse_optional_float(match.group("bearing")),
            provider=match.group("provider"),
            mock=parse_bool(match.group("mock")),
            gps_state=match.group("gpsState"),
            tracking_generation=int(match.group("trackingGeneration")),
            allow_when_gps_paused=parse_bool(match.group("allowWhenGpsPaused")),
            bypass_filters=parse_bool(match.group("bypassFilters")),
            skip_adaptive_tracking_effects=parse_bool(match.group("skipAdaptiveTrackingEffects")),
            props_kind=match.group("propsKind"),
        )

    def _decision_trace(self, match: re.Match[str]) -> DecisionTrace:
        return DecisionTrace(
            track_id=match.group("track"),
            wall_ms=int(match.group("now")),
            gps_time_ms=int(match.group("ts")),
            lat=float(match.group("lat")),
            lon=float(match.group("lon")),
            accuracy=float(match.group("acc")),
            accepted=parse_bool(match.group("accepted")),
            emission=match.group("emission"),
            reject=match.group("reject"),
            policy=match.group("policy"),
            raw_distance_meters=float(match.group("raw")),
            effective_distance_meters=float(match.group("effective")),
            elapsed_seconds=float(match.group("dt")),
            implied_speed_mps=float(match.group("speed")),
            committed_lat=parse_committed(match.group("committedLat")),
            committed_lon=parse_committed(match.group("committedLon")),
        )


class ReplayAnonymizer:
    def __init__(self, reference_lat: float) -> None:
        self.dlat, self.dlon = random_translation_meters(reference_lat)

    def coord(self, lat: float, lon: float) -> tuple[float, float]:
        return lat + self.dlat, lon + self.dlon

    def optional_coord(self, lat: float | None, lon: float | None) -> tuple[float | None, float | None]:
        if lat is None or lon is None:
            return None, None
        return self.coord(lat, lon)


class ReplaySessionBuilder:
    def build(
        self,
        session_id: str,
        events: CaptureEvents,
        settings: dict[str, Any],
        start_ms: int,
        end_ms: int,
    ) -> dict[str, Any]:
        if not events.raw_fixes:
            raise ValueError(
                "no positioning_raw_fix lines found in capture window; "
                "re-capture with a build that logs positioning_raw_fix at ingest"
            )

        reference_lat = events.raw_fixes[0].lat
        anonymizer = ReplayAnonymizer(reference_lat)
        wall_base_ms = min(fix.wall_ms for fix in events.raw_fixes)
        elapsed_base_nanos = min(fix.elapsed_realtime_nanos for fix in events.raw_fixes)
        # The real on-device trackId is a stable per-track identifier that can persist
        # across many capture sessions; it is never written to the fixture, only a
        # fresh random UUID generated for this extraction.
        track_id = str(uuid.uuid4())

        shifted_fixes = [
            self._raw_fix_json(index, fix, wall_base_ms, elapsed_base_nanos, anonymizer)
            for index, fix in enumerate(events.raw_fixes)
        ]
        shifted_imu_events = [
            self._imu_event_json(event, wall_base_ms, elapsed_base_nanos)
            for event in events.imu_events
        ]
        self._enforce_merged_timeline_monotonicity(shifted_fixes, shifted_imu_events)
        expected_events = [
            self._trace_json(trace, wall_base_ms, anonymizer)
            for trace in events.decision_traces
        ]
        expected_events.extend(self._milestone_json(milestone, wall_base_ms) for milestone in events.milestones)
        expected_events.sort(key=lambda item: item["wallOffsetMs"])

        return {
            "schemaVersion": SCHEMA_VERSION,
            "sessionId": session_id,
            "trackId": track_id,
            "wallBaseMs": wall_base_ms,
            "elapsedRealtimeBaseNanos": elapsed_base_nanos,
            "settings": settings,
            "initialState": {
                "mode": self._initial_mode(events.milestones),
                "sessionBoundaryId": 0,
            },
            "rawFixes": shifted_fixes,
            "imuEvents": shifted_imu_events,
            "expectedEvents": expected_events,
            "assertions": self._assertions(events),
            "source": {
                "captureLabel": session_id,
                "windowStartMs": start_ms,
                "windowEndMs": end_ms,
                "extractorVersion": EXTRACTOR_VERSION,
                "inputKind": "positioning_raw_fix+imu_classification",
                "fidelity": "raw_fix_runtime_replay",
            },
        }

    def _enforce_merged_timeline_monotonicity(
        self,
        shifted_fixes: list[dict[str, Any]],
        shifted_imu_events: list[dict[str, Any]],
    ) -> None:
        """Corrects cross-stream elapsedRealtime skew between raw fixes and IMU
        classifications.

        Both streams are logged from independent code paths on-device, each reading
        SystemClock.elapsedRealtimeNanos() at a slightly different point in its own
        processing pipeline. Within a single stream this reading is always monotonic
        (validated separately), but a real capture can occasionally show a small
        (tens-of-ms) cross-stream skew where an IMU classification's elapsedRealtime
        reads slightly ahead of or behind a raw fix that arrived at nearly the same
        wall-clock instant. PositioningEndToEndReplayDriver merges both streams sorted
        by wallOffsetMs and requires the combined elapsedRealtime sequence to be
        non-decreasing, so an inverted pair would otherwise crash the replay.

        This does not fabricate or discard any event -- it only nudges the affected
        event's elapsedRealtimeOffsetNanos up to match the previous event in wall-time
        order, preserving every real lat/lon/accuracy/speed/classification value.
        """
        timeline = sorted(
            shifted_fixes + shifted_imu_events,
            key=lambda item: item["wallOffsetMs"],
        )
        previous_elapsed = None
        corrections = 0
        for item in timeline:
            elapsed = item["elapsedRealtimeOffsetNanos"]
            if previous_elapsed is not None and elapsed < previous_elapsed:
                item["elapsedRealtimeOffsetNanos"] = previous_elapsed
                corrections += 1
                elapsed = previous_elapsed
            previous_elapsed = elapsed
        if corrections:
            print(
                f"note: corrected {corrections} cross-stream elapsedRealtime "
                "inversion(s) between raw fixes and IMU classifications (see "
                "_enforce_merged_timeline_monotonicity docstring)",
                file=sys.stderr,
            )

    def _raw_fix_json(
        self,
        index: int,
        fix: RawFix,
        wall_base_ms: int,
        elapsed_base_nanos: int,
        anonymizer: ReplayAnonymizer,
    ) -> dict[str, Any]:
        lat, lon = anonymizer.coord(fix.lat, fix.lon)
        return {
            "index": index,
            "wallOffsetMs": fix.wall_ms - wall_base_ms,
            "elapsedRealtimeOffsetNanos": fix.elapsed_realtime_nanos - elapsed_base_nanos,
            "gpsTimeMs": fix.gps_time_ms,
            "lat": lat,
            "lon": lon,
            "accuracy": fix.accuracy,
            "speedMps": fix.speed_mps,
            "bearingDeg": fix.bearing_deg,
            "provider": fix.provider,
            "mock": fix.mock,
            "gpsState": fix.gps_state,
            "trackingGeneration": fix.tracking_generation,
            "allowWhenGpsPaused": fix.allow_when_gps_paused,
            "bypassFilters": fix.bypass_filters,
            "skipAdaptiveTrackingEffects": fix.skip_adaptive_tracking_effects,
            "propsKind": fix.props_kind,
        }

    def _imu_event_json(
        self,
        event: ImuClassificationEvent,
        wall_base_ms: int,
        elapsed_base_nanos: int,
    ) -> dict[str, Any]:
        return {
            "wallOffsetMs": event.wall_ms - wall_base_ms,
            "elapsedRealtimeOffsetNanos": event.elapsed_realtime_nanos - elapsed_base_nanos,
            "classification": event.classification,
            "confidence": event.confidence,
            "accelerationVarianceMps4": event.variance,
            "stepRatePerMinute": event.step_rate,
        }

    def _trace_json(
        self,
        trace: DecisionTrace,
        wall_base_ms: int,
        anonymizer: ReplayAnonymizer,
    ) -> dict[str, Any]:
        lat, lon = anonymizer.coord(trace.lat, trace.lon)
        committed_lat, committed_lon = anonymizer.optional_coord(trace.committed_lat, trace.committed_lon)
        return {
            "kind": "positioning_decision_trace",
            "wallOffsetMs": trace.wall_ms - wall_base_ms,
            "gpsTimeMs": trace.gps_time_ms,
            "lat": lat,
            "lon": lon,
            "accuracy": trace.accuracy,
            "accepted": trace.accepted,
            "emission": trace.emission,
            "reject": trace.reject,
            "policy": trace.policy,
            "rawDistanceMeters": trace.raw_distance_meters,
            "effectiveDistanceMeters": trace.effective_distance_meters,
            "elapsedSeconds": trace.elapsed_seconds,
            "impliedSpeedMps": trace.implied_speed_mps,
            "committedLat": committed_lat,
            "committedLon": committed_lon,
        }

    def _milestone_json(self, milestone: Milestone, wall_base_ms: int) -> dict[str, Any]:
        return {
            "kind": milestone.kind,
            "wallOffsetMs": milestone.wall_ms - wall_base_ms,
            "modeBefore": milestone.mode_before,
            "modeAfter": milestone.mode_after,
            "reason": milestone.reason,
            "speedMps": milestone.speed_mps,
            "accuracyMeters": milestone.accuracy_meters,
            "elapsedSeconds": milestone.elapsed_seconds,
            "path": milestone.path,
        }

    def _initial_mode(self, milestones: list[Milestone]) -> str:
        for milestone in milestones:
            if milestone.kind == "auto_mode_changed" and milestone.mode_after:
                return milestone.mode_after
        return "WALKING"

    def _assertions(self, events: CaptureEvents) -> dict[str, Any]:
        first_cap = next(
            (
                item
                for item in events.milestones
                if item.kind == "auto_motion_evidence"
                and item.reason == "speed-cap-exceeded"
                and item.path == "FAST_EMIT"
            ),
            None,
        )
        required: list[dict[str, Any]] = []
        if first_cap is not None:
            wall_base_ms = min(fix.wall_ms for fix in events.raw_fixes)
            required.append(
                {
                    "kind": "auto_motion_evidence",
                    "reason": "speed-cap-exceeded",
                    "path": "FAST_EMIT",
                    "withinMs": 60_000,
                    "fromWallOffsetMs": first_cap.wall_ms - wall_base_ms,
                }
            )
        seed_count = sum(
            1 for m in events.milestones
            if m.kind == "auto_mode_changed" and m.mode_after is not None
        )
        return {
            "finalMode": "DRIVING",
            "minPersistedPoints": 0,
            "expectedMotionSeedCountMin": seed_count,
            "maxDecisionMismatches": 0,
            "requiredEvents": required,
        }


class ReplayValidator:
    def validate(self, artifact: dict[str, Any], expected_session: str) -> None:
        self._require(artifact.get("schemaVersion") == SCHEMA_VERSION, f"schemaVersion must be {SCHEMA_VERSION}")
        self._require(artifact.get("sessionId") == expected_session, "sessionId mismatch")
        self._require(isinstance(artifact.get("settings"), dict), "settings must be an object")
        self._require(isinstance(artifact.get("initialState"), dict), "initialState must be an object")
        self._require(isinstance(artifact.get("source"), dict), "source must be an object")
        imu_events = artifact.get("imuEvents")
        self._require(isinstance(imu_events, list), "imuEvents must be an array")

        raw_fixes = artifact.get("rawFixes")
        self._require(isinstance(raw_fixes, list) and raw_fixes, "rawFixes must be non-empty")
        expected_events = artifact.get("expectedEvents")
        self._require(isinstance(expected_events, list), "expectedEvents must be an array")
        assertions = artifact.get("assertions")
        self._require(isinstance(assertions, dict), "assertions must be an object")

        previous_wall = -1
        previous_elapsed = -1
        for index, raw_fix in enumerate(raw_fixes):
            self._validate_raw_fix(raw_fix, index)
            wall = raw_fix["wallOffsetMs"]
            elapsed = raw_fix["elapsedRealtimeOffsetNanos"]
            self._require(wall >= previous_wall, f"rawFixes[{index}] wallOffsetMs is not monotonic")
            self._require(
                elapsed >= previous_elapsed,
                f"rawFixes[{index}] elapsedRealtimeOffsetNanos is not monotonic",
            )
            previous_wall = wall
            previous_elapsed = elapsed

        for index, event in enumerate(expected_events):
            self._require(isinstance(event, dict), f"expectedEvents[{index}] must be an object")
            self._require(isinstance(event.get("kind"), str), f"expectedEvents[{index}] missing kind")
            self._require(isinstance(event.get("wallOffsetMs"), int), f"expectedEvents[{index}] missing wallOffsetMs")

    def invariant_view(self, artifact: dict[str, Any]) -> dict[str, Any]:
        def strip_coords(value: Any) -> Any:
            if isinstance(value, dict):
                return {
                    key: strip_coords(item)
                    for key, item in value.items()
                    if key not in {"lat", "lon", "committedLat", "committedLon"}
                }
            if isinstance(value, list):
                return [strip_coords(item) for item in value]
            return value

        return strip_coords(artifact)

    def _validate_raw_fix(self, raw_fix: Any, index: int) -> None:
        self._require(isinstance(raw_fix, dict), f"rawFixes[{index}] must be an object")
        for key in [
            "wallOffsetMs",
            "elapsedRealtimeOffsetNanos",
            "gpsTimeMs",
            "lat",
            "lon",
            "accuracy",
            "provider",
            "mock",
            "bypassFilters",
            "allowWhenGpsPaused",
            "skipAdaptiveTrackingEffects",
        ]:
            self._require(key in raw_fix, f"rawFixes[{index}] missing {key}")
        self._require(isinstance(raw_fix["wallOffsetMs"], int), f"rawFixes[{index}] wallOffsetMs must be int")
        self._require(
            isinstance(raw_fix["elapsedRealtimeOffsetNanos"], int),
            f"rawFixes[{index}] elapsedRealtimeOffsetNanos must be int",
        )
        self._require(isinstance(raw_fix["lat"], (int, float)), f"rawFixes[{index}] lat must be numeric")
        self._require(isinstance(raw_fix["lon"], (int, float)), f"rawFixes[{index}] lon must be numeric")

    def _require(self, condition: bool, message: str) -> None:
        if not condition:
            raise ValueError(message)


class ReplayWriter:
    def write(self, path: Path, artifact: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(artifact, indent=2, sort_keys=False) + "\n", encoding="utf-8")

    def read(self, path: Path) -> dict[str, Any]:
        return json.loads(path.read_text(encoding="utf-8"))


def parse_iso_ms(iso: str) -> int:
    dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return int(dt.timestamp() * 1000)


def parse_bool(value: str) -> bool:
    return value == "true"


def parse_optional_float(value: str) -> float | None:
    if value == "none":
        return None
    return float(value)


def parse_committed(value: str) -> float | None:
    if value == "none":
        return None
    return float(value)


def random_translation_meters(ref_lat: float) -> tuple[float, float]:
    bearing = secrets.SystemRandom().uniform(0.0, 2.0 * math.pi)
    miles = secrets.SystemRandom().uniform(MILES_MIN, MILES_MAX)
    distance_m = miles * METERS_PER_MILE
    dlat = (distance_m / METERS_PER_DEGREE_LAT) * math.cos(bearing)
    cos_lat = math.cos(math.radians(ref_lat))
    denom = METERS_PER_DEGREE_LAT * cos_lat if abs(cos_lat) > 1e-6 else METERS_PER_DEGREE_LAT
    dlon = (distance_m / denom) * math.sin(bearing)
    return dlat, dlon


def load_settings(path: Path) -> dict[str, Any]:
    loaded = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(loaded, dict):
        raise ValueError("--settings-json must point to a JSON object")
    return loaded


def build_artifact(args: argparse.Namespace) -> dict[str, Any]:
    events = CaptureEventParser().parse(
        log_path=args.log,
        track_id=args.track_id,
        start_ms=parse_iso_ms(args.start),
        end_ms=parse_iso_ms(args.end),
    )
    return ReplaySessionBuilder().build(
        session_id=args.session,
        events=events,
        settings=load_settings(args.settings_json),
        start_ms=parse_iso_ms(args.start),
        end_ms=parse_iso_ms(args.end),
    )


def command_write(args: argparse.Namespace) -> int:
    artifact = build_artifact(args)
    ReplayValidator().validate(artifact, expected_session=args.session)
    ReplayWriter().write(args.output, artifact)
    imu_count = len(artifact.get("imuEvents", []))
    print(f"wrote {args.output} ({len(artifact['rawFixes'])} raw fixes, {imu_count} IMU events)")
    return 0


def command_check(args: argparse.Namespace) -> int:
    artifact = build_artifact(args)
    validator = ReplayValidator()
    validator.validate(artifact, expected_session=args.session)
    committed = ReplayWriter().read(args.output)
    validator.validate(committed, expected_session=args.session)
    if validator.invariant_view(artifact) != validator.invariant_view(committed):
        print("replay invariant mismatch", file=sys.stderr)
        return 1
    print(f"ok: {args.output}")
    return 0


def command_validate(args: argparse.Namespace) -> int:
    artifact = ReplayWriter().read(args.output)
    ReplayValidator().validate(artifact, expected_session=args.session)
    source = artifact["source"]
    imu_count = len(artifact.get("imuEvents", []))
    print(
        f"ok: {args.output} ({len(artifact['rawFixes'])} raw fixes, "
        f"{imu_count} IMU events, {source.get('inputKind')})"
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Build and validate android-tracker replay fixtures")
    subparsers = parser.add_subparsers(dest="command", required=True)

    for name, handler in (("write", command_write), ("check", command_check)):
        command = subparsers.add_parser(name)
        command.add_argument("log", type=Path)
        command.add_argument("--session", required=True)
        command.add_argument("--start", required=True)
        command.add_argument("--end", required=True)
        command.add_argument("--output", required=True, type=Path)
        command.add_argument("--settings-json", required=True, type=Path)
        command.add_argument("--track-id", help="Optional filter by track UUID")
        command.set_defaults(func=handler)

    validate = subparsers.add_parser("validate")
    validate.add_argument("--session", required=True)
    validate.add_argument("--output", required=True, type=Path)
    validate.set_defaults(func=command_validate)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

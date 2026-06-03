#!/usr/bin/env python3
"""
Extract positioning_decision_trace (+ motion milestones) from a capture log into
anonymized replay JSON for android-tracker unit tests.

One-way anonymization: random ~900–1100 mi translation applied in memory only.
Original coordinates are never written to disk.

Usage:
  python3 scripts/extract_capture_replay.py write LOG \\
    --session SESSION --start ISO --end ISO --output PATH.json
  python3 scripts/extract_capture_replay.py check LOG \\
    --session SESSION --start ISO --end ISO --output PATH.json
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
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, BinaryIO, Iterator, TextIO

METERS_PER_MILE = 1609.344
MILES_MIN = 900.0
MILES_MAX = 1100.0
METERS_PER_DEGREE_LAT = 111_320.0

SCHEMA_VERSION = 1

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

ISO_TS_RE = re.compile(
    r"^(?P<iso>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)\s+"
)


@dataclass
class RawFrame:
    track_id: str
    gps_time_ms: int
    wall_now_ms: int
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
class RawMilestone:
    wall_now_ms: int
    kind: str
    mode_before: str | None
    mode_after: str | None
    reason: str | None
    speed_mps: float | None
    accuracy_meters: float | None
    elapsed_seconds: float | None
    path: str | None


def parse_iso_ms(iso: str) -> int:
    dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return int(dt.timestamp() * 1000)


def open_log(path: Path) -> TextIO:
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return path.open("r", encoding="utf-8", errors="replace")


def iter_log_lines(stream: TextIO) -> Iterator[tuple[int | None, str]]:
    for line in stream:
        wall_ms: int | None = None
        m = ISO_TS_RE.match(line)
        if m:
            wall_ms = parse_iso_ms(m.group("iso"))
        yield wall_ms, line


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


def shift_coord(lat: float, lon: float, dlat: float, dlon: float) -> tuple[float, float]:
    return lat + dlat, lon + dlon


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def extract_from_log(
    log_path: Path,
    track_id: str | None,
    start_ms: int,
    end_ms: int,
) -> tuple[list[RawFrame], list[RawMilestone]]:
    frames: list[RawFrame] = []
    milestones: list[RawMilestone] = []

    with open_log(log_path) as stream:
        for wall_ms, line in iter_log_lines(stream):
            if wall_ms is None or wall_ms < start_ms or wall_ms > end_ms:
                continue

            trace = TRACE_RE.search(line)
            if trace:
                tid = trace.group("track")
                if track_id and tid != track_id:
                    continue
                frames.append(
                    RawFrame(
                        track_id=tid,
                        gps_time_ms=int(trace.group("ts")),
                        wall_now_ms=int(trace.group("now")),
                        lat=float(trace.group("lat")),
                        lon=float(trace.group("lon")),
                        accuracy=float(trace.group("acc")),
                        accepted=trace.group("accepted") == "true",
                        emission=trace.group("emission"),
                        reject=trace.group("reject"),
                        policy=trace.group("policy"),
                        raw_distance_meters=float(trace.group("raw")),
                        effective_distance_meters=float(trace.group("effective")),
                        elapsed_seconds=float(trace.group("dt")),
                        implied_speed_mps=float(trace.group("speed")),
                        committed_lat=parse_committed(trace.group("committedLat")),
                        committed_lon=parse_committed(trace.group("committedLon")),
                    )
                )
                continue

            ev = MOTION_EVIDENCE_RE.search(line)
            if ev:
                milestones.append(
                    RawMilestone(
                        wall_now_ms=wall_ms,
                        kind="auto_motion_evidence",
                        mode_before=ev.group("modeBefore"),
                        mode_after=ev.group("modeAfter"),
                        reason=ev.group("reason"),
                        speed_mps=float(ev.group("speed")),
                        accuracy_meters=float(ev.group("accuracy")),
                        elapsed_seconds=float(ev.group("dt")),
                        path=ev.group("path"),
                    )
                )
                continue

            ch = MODE_CHANGED_RE.search(line)
            if ch:
                milestones.append(
                    RawMilestone(
                        wall_now_ms=wall_ms,
                        kind="auto_mode_changed",
                        mode_before=None,
                        mode_after=ch.group("mode"),
                        reason=ch.group("reason"),
                        speed_mps=None,
                        accuracy_meters=None,
                        elapsed_seconds=None,
                        path=ch.group("path"),
                    )
                )

    frames.sort(key=lambda f: f.wall_now_ms)
    milestones.sort(key=lambda m: m.wall_now_ms)
    return frames, milestones


def build_session(
    frames: list[RawFrame],
    milestones: list[RawMilestone],
    session_id: str,
) -> dict[str, Any]:
    if not frames:
        raise ValueError("no positioning_decision_trace frames in window")

    ref_lat = frames[0].lat
    dlat, dlon = random_translation_meters(ref_lat)
    wall_base = frames[0].wall_now_ms
    track = frames[0].track_id

    out_frames: list[dict[str, Any]] = []
    for f in frames:
        lat, lon = shift_coord(f.lat, f.lon, dlat, dlon)
        committed_lat = (
            shift_coord(f.committed_lat, f.committed_lon, dlat, dlon)[0]
            if f.committed_lat is not None and f.committed_lon is not None
            else None
        )
        committed_lon = (
            shift_coord(f.committed_lat, f.committed_lon, dlat, dlon)[1]
            if f.committed_lat is not None and f.committed_lon is not None
            else None
        )
        out_frames.append(
            {
                "gpsTimeMs": f.gps_time_ms,
                "wallOffsetMs": f.wall_now_ms - wall_base,
                "lat": lat,
                "lon": lon,
                "accuracy": f.accuracy,
                "accepted": f.accepted,
                "emission": f.emission,
                "reject": f.reject,
                "policy": f.policy,
                "rawDistanceMeters": f.raw_distance_meters,
                "effectiveDistanceMeters": f.effective_distance_meters,
                "elapsedSeconds": f.elapsed_seconds,
                "impliedSpeedMps": f.implied_speed_mps,
                "committedLat": committed_lat,
                "committedLon": committed_lon,
            }
        )

    out_milestones: list[dict[str, Any]] = []
    for m in milestones:
        out_milestones.append(
            {
                "wallOffsetMs": m.wall_now_ms - wall_base,
                "kind": m.kind,
                "modeBefore": m.mode_before,
                "modeAfter": m.mode_after,
                "reason": m.reason,
                "speedMps": m.speed_mps,
                "accuracyMeters": m.accuracy_meters,
                "elapsedSeconds": m.elapsed_seconds,
                "path": m.path,
            }
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "sessionId": session_id,
        "trackId": track,
        "wallBaseMs": wall_base,
        "frameCount": len(out_frames),
        "frames": out_frames,
        "milestones": out_milestones,
    }


def load_session(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_session(session: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if session.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"unsupported schemaVersion={session.get('schemaVersion')}")
    frames = session.get("frames") or []
    if not frames:
        errors.append("frames is empty")
    if session.get("frameCount") != len(frames):
        errors.append("frameCount does not match frames length")

    prev_wall: int | None = None
    prev_lat: float | None = None
    prev_lon: float | None = None
    for i, frame in enumerate(frames):
        wall = frame.get("wallOffsetMs")
        if wall is None:
            errors.append(f"frame[{i}] missing wallOffsetMs")
            continue
        if prev_wall is not None and wall < prev_wall:
            errors.append(f"frame[{i}] wallOffsetMs regressed")
        prev_wall = wall

        for key in (
            "gpsTimeMs",
            "lat",
            "lon",
            "accuracy",
            "policy",
            "rawDistanceMeters",
            "effectiveDistanceMeters",
            "elapsedSeconds",
            "impliedSpeedMps",
        ):
            if key not in frame:
                errors.append(f"frame[{i}] missing {key}")

        lat, lon = frame.get("lat"), frame.get("lon")
        if lat is not None and lon is not None and prev_lat is not None and prev_lon is not None:
            if haversine_m(prev_lat, prev_lon, lat, lon) > 5_000.0:
                errors.append(f"frame[{i}] hop >5km (sanity)")
        if lat is not None and lon is not None:
            prev_lat, prev_lon = lat, lon

    return errors


def invariant_view(session: dict[str, Any]) -> dict[str, Any]:
    frames_out: list[dict[str, Any]] = []
    prev_lat: float | None = None
    prev_lon: float | None = None
    for frame in session["frames"]:
        lat, lon = frame["lat"], frame["lon"]
        step_m = None
        if prev_lat is not None and prev_lon is not None:
            step_m = haversine_m(prev_lat, prev_lon, lat, lon)
        prev_lat, prev_lon = lat, lon
        frames_out.append(
            {
                "gpsTimeMs": frame["gpsTimeMs"],
                "wallOffsetMs": frame["wallOffsetMs"],
                "accuracy": frame["accuracy"],
                "accepted": frame["accepted"],
                "emission": frame["emission"],
                "reject": frame["reject"],
                "policy": frame["policy"],
                "rawDistanceMeters": frame["rawDistanceMeters"],
                "effectiveDistanceMeters": frame["effectiveDistanceMeters"],
                "elapsedSeconds": frame["elapsedSeconds"],
                "impliedSpeedMps": frame["impliedSpeedMps"],
                "stepMeters": step_m,
            }
        )
    milestones_out = [
        {
            "wallOffsetMs": m["wallOffsetMs"],
            "kind": m["kind"],
            "modeBefore": m.get("modeBefore"),
            "modeAfter": m.get("modeAfter"),
            "reason": m.get("reason"),
            "speedMps": m.get("speedMps"),
            "accuracyMeters": m.get("accuracyMeters"),
            "elapsedSeconds": m.get("elapsedSeconds"),
            "path": m.get("path"),
        }
        for m in session.get("milestones", [])
    ]
    return {
        "trackId": session["trackId"],
        "frameCount": session["frameCount"],
        "frames": frames_out,
        "milestones": milestones_out,
    }


def compare_invariant(committed: dict[str, Any], regenerated: dict[str, Any]) -> list[str]:
    a = invariant_view(committed)
    b = invariant_view(regenerated)
    mismatches: list[str] = []
    if a["trackId"] != b["trackId"]:
        mismatches.append(f"trackId {a['trackId']} != {b['trackId']}")
    if a["frameCount"] != b["frameCount"]:
        mismatches.append(f"frameCount {a['frameCount']} != {b['frameCount']}")
    for i, (fa, fb) in enumerate(zip(a["frames"], b["frames"])):
        for key in fa:
            va, vb = fa[key], fb.get(key)
            if isinstance(va, float):
                if vb is None or abs(va - vb) > 1e-3:
                    mismatches.append(f"frame[{i}].{key}: {va} != {vb}")
            elif va != vb:
                mismatches.append(f"frame[{i}].{key}: {va} != {vb}")
    if len(a["frames"]) != len(b["frames"]):
        mismatches.append("frame list length differs")
    if a["milestones"] != b["milestones"]:
        mismatches.append("milestones differ")
    return mismatches


def add_extract_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("log_path", type=Path, help="Capture log .txt or .txt.gz")
    parser.add_argument("--session", required=True, help="Session identifier written into JSON")
    parser.add_argument("--start", required=True, help="Window start ISO-8601 UTC")
    parser.add_argument("--end", required=True, help="Window end ISO-8601 UTC")
    parser.add_argument("--output", type=Path, required=True, help="Committed replay JSON path")
    parser.add_argument("--track-id", default=None, help="Optional filter by track UUID")


def add_validate_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--session", required=True, help="Expected sessionId in committed JSON")
    parser.add_argument("--output", type=Path, required=True, help="Committed replay JSON path")


def run_validate(output_path: Path, session_id: str) -> int:
    if not output_path.is_file():
        print(f"missing committed session: {output_path}", file=sys.stderr)
        return 1
    session = load_session(output_path)
    if session.get("sessionId") != session_id:
        print(
            f"sessionId mismatch: file={session.get('sessionId')} expected={session_id}",
            file=sys.stderr,
        )
        return 1
    errors = validate_session(session)
    if errors:
        for err in errors:
            print(err, file=sys.stderr)
        return 1
    print(f"ok: {output_path} ({session['frameCount']} frames)")
    return 0


def extract_session_from_log(
    log_path: Path,
    session_id: str,
    start_iso: str,
    end_iso: str,
    track_id: str | None,
) -> dict[str, Any]:
    if not log_path.is_file():
        print(f"log not found: {log_path}", file=sys.stderr)
        raise SystemExit(1)
    start_ms = parse_iso_ms(start_iso)
    end_ms = parse_iso_ms(end_iso)
    frames, milestones = extract_from_log(log_path, track_id, start_ms, end_ms)
    if not frames:
        print("no frames extracted", file=sys.stderr)
        raise SystemExit(1)
    return build_session(frames, milestones, session_id)


def main() -> int:
    parser = argparse.ArgumentParser(description="Extract anonymized capture replay JSON")
    subparsers = parser.add_subparsers(dest="command", required=True)

    write_parser = subparsers.add_parser("write", help="Write anonymized replay JSON")
    add_extract_args(write_parser)

    check_parser = subparsers.add_parser("check", help="Shift-invariant compare to committed JSON")
    add_extract_args(check_parser)

    validate_parser = subparsers.add_parser("validate", help="Validate committed JSON schema")
    add_validate_args(validate_parser)

    args = parser.parse_args()

    if args.command == "validate":
        return run_validate(args.output, args.session)

    session = extract_session_from_log(
        log_path=args.log_path,
        session_id=args.session,
        start_iso=args.start,
        end_iso=args.end,
        track_id=args.track_id,
    )

    if args.command == "write":
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(session, indent=2) + "\n", encoding="utf-8")
        print(
            f"wrote {args.output} ({session['frameCount']} frames, "
            f"{len(session['milestones'])} milestones)"
        )
        return 0

    if args.command == "check":
        if not args.output.is_file():
            print(f"missing committed session: {args.output}", file=sys.stderr)
            return 1
        committed = load_session(args.output)
        if committed.get("sessionId") != args.session:
            print(
                f"sessionId mismatch: file={committed.get('sessionId')} expected={args.session}",
                file=sys.stderr,
            )
            return 1
        mismatches = compare_invariant(committed, session)
        if mismatches:
            for m in mismatches:
                print(m, file=sys.stderr)
            return 1
        print(f"ok: log matches committed session invariants ({session['frameCount']} frames)")
        return 0

    parser.error(f"unknown command: {args.command}")
    return 2


if __name__ == "__main__":
    sys.exit(main())

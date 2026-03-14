"""
Create or reuse a tracker, then stream realistic simulated live points.

The command supports activity styles (walk/run/bike/drive), can resume from the
latest existing point, and writes richer point_params for UI/testing.
"""
import bisect
import math
import random
import secrets
import time
import uuid

from django.apps import apps
from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from website.config_loader import get_config_loader

from ...helpers import DEFAULT_TRACK_COLOR, broadcast_track_updated

EARTH_RADIUS_METERS = 6371000
FEET_TO_METERS = 0.3048

User = get_user_model()

# Style -> speed range (kph), bearing drift stddev (deg), altitude drift (m/step)
STYLE_CONFIG = {
    "walk": {"speed_range": (3.0, 6.0), "bearing_stddev": 18.0, "alt_drift": 0.5},
    "run": {"speed_range": (8.0, 15.0), "bearing_stddev": 12.0, "alt_drift": 0.8},
    "bike": {"speed_range": (15.0, 35.0), "bearing_stddev": 8.0, "alt_drift": 1.2},
    "drive": {"speed_range": (30.0, 95.0), "bearing_stddev": 5.0, "alt_drift": 0.6},
}

# Bias starting points toward populated land masses (lon_min, lon_max, lat_min, lat_max).
START_REGIONS = [
    (-125.0, -66.0, 24.0, 49.0),   # Continental USA
    (-10.0, 30.0, 35.0, 60.0),      # Europe
    (125.0, 150.0, 25.0, 45.0),     # East Asia
    (130.0, 155.0, -45.0, -10.0),   # Australia
    (-80.0, -35.0, -35.0, 5.0),     # South America
]


def destination_point(lat_deg: float, lon_deg: float, bearing_deg: float, distance_m: float) -> tuple[float, float]:
    """Return (lat_deg, lon_deg) of the point reached from (lat_deg, lon_deg) by moving distance_m meters at bearing_deg (0=North, 90=East)."""
    lat_rad = math.radians(lat_deg)
    lon_rad = math.radians(lon_deg)
    brng_rad = math.radians(bearing_deg)
    d_R = distance_m / EARTH_RADIUS_METERS
    lat2_rad = math.asin(
        math.sin(lat_rad) * math.cos(d_R)
        + math.cos(lat_rad) * math.sin(d_R) * math.cos(brng_rad)
    )
    lon2_rad = lon_rad + math.atan2(
        math.sin(brng_rad) * math.sin(d_R) * math.cos(lat_rad),
        math.cos(d_R) - math.sin(lat_rad) * math.sin(lat2_rad),
    )
    return math.degrees(lat2_rad), math.degrees(lon2_rad)


def pick_starting_point(rng: random.Random) -> tuple[float, float]:
    """Choose a realistic random starting point on land-like regions."""
    lon_min, lon_max, lat_min, lat_max = rng.choice(START_REGIONS)
    return rng.uniform(lat_min, lat_max), rng.uniform(lon_min, lon_max)


class Command(BaseCommand):
    help = "Create a test tracker if missing, then stream simulated live points."

    def add_arguments(self, parser):
        parser.add_argument(
            "--user",
            type=str,
            help="Email of the user to attach the tracker to. Default: first user.",
        )
        parser.add_argument(
            "--name",
            type=str,
            default="Test Tracker",
            help="Tracker name to get or create (default: Test Tracker).",
        )
        parser.add_argument(
            "--interval",
            type=float,
            default=2.0,
            help="Seconds between each streamed point (default: 2.0).",
        )
        parser.add_argument(
            "--style",
            type=str,
            choices=sorted(STYLE_CONFIG.keys()),
            default="run",
            help="Movement style profile (default: run).",
        )
        parser.add_argument(
            "--max-points",
            type=int,
            default=None,
            help="Override max stored points (default uses extensions.live_track.max_points).",
        )
        parser.add_argument(
            "--seed",
            type=int,
            default=None,
            help="Random seed for reproducible simulation.",
        )
        parser.add_argument(
            "--reset",
            action="store_true",
            help="Clear existing history for this track before streaming.",
        )
        parser.add_argument(
            "--once",
            action="store_true",
            help="Emit a single point then exit (useful for testing).",
        )

    def handle(self, *args, **options):
        try:
            LiveTrack = apps.get_model("live_track", "LiveTrack")
        except LookupError:
            self.stdout.write(
                self.style.ERROR("App 'live_track' not found. Enable the Live Track extension and try again.")
            )
            return

        user = None
        if options.get("user"):
            user = User.objects.filter(email=options["user"]).first()
            if not user:
                self.stdout.write(self.style.ERROR(f"No user with email {options['user']!r}."))
                return
        else:
            user = User.objects.order_by("pk").first()
        if not user:
            self.stdout.write(self.style.ERROR("No user in the database. Create a user first."))
            return

        track = LiveTrack.objects.filter(user=user, name=options["name"]).first()
        if not track:
            track = LiveTrack.objects.create(
                id=uuid.uuid4(),
                tracker_secret=secrets.token_urlsafe(32),
                name=options["name"],
                user=user,
                settings={"color": DEFAULT_TRACK_COLOR},
                geometry={"type": "LineString", "coordinates": []},
                point_params=[],
            )
            self.stdout.write(self.style.SUCCESS(f"Created tracker: {track.name} ({track.id})"))
        else:
            self.stdout.write(self.style.SUCCESS(f"Using existing tracker: {track.name} ({track.id})"))

        rng = random.Random(options["seed"])
        style = options["style"]
        style_cfg = STYLE_CONFIG[style]
        speed_lo, speed_hi = style_cfg["speed_range"]
        bearing_stddev = style_cfg["bearing_stddev"]
        alt_drift = style_cfg["alt_drift"]
        base_speed_kph = rng.uniform(speed_lo, speed_hi)
        bearing_deg = rng.uniform(0.0, 360.0)
        altitude_m = rng.uniform(20.0, 400.0)
        batt = rng.uniform(50.0, 100.0)
        sat = rng.randint(5, 16)
        provider = "gps"
        charging = False

        configured_max_points = get_config_loader().get_int("extensions.live_track.max_points", 1000)
        max_points = options["max_points"] if options["max_points"] is not None else configured_max_points
        max_points = max(1, int(max_points))
        interval = max(0.1, float(options["interval"]))
        once = options.get("once", False)

        if options.get("reset"):
            with transaction.atomic():
                track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
                track_locked.geometry = {"type": "LineString", "coordinates": []}
                track_locked.point_params = []
                track_locked.save(update_fields=["geometry", "point_params", "updated_at"])
            self.stdout.write(self.style.WARNING("Reset existing track history."))

        latest = (
            LiveTrack.objects.filter(pk=track.id)
            .values_list("geometry", flat=True)
            .first()
            or {"type": "LineString", "coordinates": []}
        )
        latest_coords = list((latest or {}).get("coordinates") or [])
        if latest_coords and len(latest_coords[-1]) >= 2:
            lon = float(latest_coords[-1][0])
            lat = float(latest_coords[-1][1])
            self.stdout.write(f"Resuming at latest point ({lat:.5f}, {lon:.5f})")
        else:
            lat, lon = pick_starting_point(rng)
            self.stdout.write(f"Starting at ({lat:.5f}, {lon:.5f})")

        self.stdout.write(
            f"Streaming style={style}, interval={interval:.1f}s, max_points={max_points}"
        )

        count = 0
        try:
            while True:
                timestamp_ms = int(timezone.now().timestamp() * 1000)
                new_point = [round(lon, 6), round(lat, 6), timestamp_ms]
                speed_kph = max(0.2, base_speed_kph + rng.gauss(0.0, base_speed_kph * 0.12))
                acc_m = max(1.0, rng.uniform(3.0, 20.0))
                sat = int(max(3, min(18, sat + rng.randint(-1, 1))))
                if rng.random() < 0.05:
                    provider = "network" if provider == "gps" else "gps"
                if rng.random() < 0.02:
                    charging = not charging
                if charging:
                    batt = min(100.0, batt + rng.uniform(0.02, 0.12))
                else:
                    batt = max(1.0, batt - rng.uniform(0.02, 0.10))
                altitude_m += rng.gauss(0.0, alt_drift)

                extra = {
                    "acc": round(acc_m, 1),  # meters; UI can convert to ft if needed
                    "spd_kph": round(speed_kph, 1),
                    "bearing": round(bearing_deg, 1),
                    "alt": round(altitude_m, 1),
                    "sat": sat,
                    "prov": provider,
                    "batt": round(batt, 1),
                    "ischarging": charging,
                }

                with transaction.atomic():
                    track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
                    geom = track_locked.geometry or {"type": "LineString", "coordinates": []}
                    coords = list(geom.get("coordinates") or [])
                    point_params = list(track_locked.point_params or [])
                    # Keep params aligned with coordinates if legacy data is mismatched.
                    if len(point_params) < len(coords):
                        point_params.extend({} for _ in range(len(coords) - len(point_params)))
                    elif len(point_params) > len(coords):
                        point_params = point_params[:len(coords)]
                    ts_list = [c[2] for c in coords]
                    idx = bisect.bisect_right(ts_list, timestamp_ms)
                    coords.insert(idx, new_point)
                    point_params.insert(idx, extra)
                    if len(coords) > max_points:
                        n_removed = len(coords) - max_points
                        coords = coords[n_removed:]
                        point_params = point_params[n_removed:]
                        idx = max(0, idx - n_removed)
                    track_locked.geometry = {"type": "LineString", "coordinates": coords}
                    track_locked.point_params = point_params
                    track_locked.updated_at = timezone.now()
                    track_locked.save(update_fields=["geometry", "point_params", "updated_at"])
                    broadcast_idx = idx if 0 <= idx < len(coords) else None

                if broadcast_idx is not None:
                    broadcast_track_updated(track_locked, new_point, extra, index=broadcast_idx)

                count += 1
                if count % 10 == 0 or count == 1:
                    self.stdout.write(
                        f"  Streamed #{count} at ({lat:.5f}, {lon:.5f}) "
                        f"spd={speed_kph:.1f}kph batt={batt:.1f}%"
                    )
                if once:
                    break
                # Next point based on speed profile and smooth heading drift.
                bearing_deg = (bearing_deg + rng.gauss(0.0, bearing_stddev)) % 360.0
                distance_m = speed_kph * 1000.0 * interval / 3600.0
                distance_m = max(50 * FEET_TO_METERS, distance_m)
                lat, lon = destination_point(lat, lon, bearing_deg, distance_m)
                time.sleep(interval)
        except KeyboardInterrupt:
            self.stdout.write(self.style.WARNING("\nStopped by user."))
        self.stdout.write(self.style.SUCCESS(f"Done. Streamed {count} point(s)."))

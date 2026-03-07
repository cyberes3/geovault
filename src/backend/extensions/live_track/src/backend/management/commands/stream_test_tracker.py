"""
Create a test tracker if it doesn't exist, then stream points simulating a live track.

Starts at a random point on the planet, then in a loop adds points at random bearing
(0–360°) and random distance between 50 feet and 1 km from the previous point.
"""
import bisect
import math
import random
import secrets
import time
import uuid

from django.apps import apps
from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from website.config_loader import get_config_loader

from ...helpers import broadcast_track_updated

# 50 feet and 1 km in meters
MIN_STEP_METERS = 50 * 0.3048
MAX_STEP_METERS = 1000.0
EARTH_RADIUS_METERS = 6371000
FEET_TO_METERS = 0.3048


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


class Command(BaseCommand):
    help = "Create a test tracker if missing, then stream simulated live points (random bearing, 50 ft–1 km per step)."

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

        User = apps.get_model("auth", "User")
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
                color="#3388ff",
                geometry={"type": "LineString", "coordinates": []},
                point_params=[],
            )
            self.stdout.write(self.style.SUCCESS(f"Created tracker: {track.name} ({track.id})"))
        else:
            self.stdout.write(self.style.SUCCESS(f"Using existing tracker: {track.name} ({track.id})"))

        # Random start anywhere on the planet
        lat = random.uniform(-90.0, 90.0)
        lon = random.uniform(-180.0, 180.0)
        self.stdout.write(f"Starting at ({lat:.5f}, {lon:.5f})")

        max_points = get_config_loader().get_int("extensions.live_track.max_points", 1000)
        interval = max(0.1, float(options["interval"]))
        once = options.get("once", False)

        count = 0
        try:
            while True:
                timestamp_ms = int(timezone.now().timestamp() * 1000)
                new_point = [round(lon, 6), round(lat, 6), timestamp_ms]
                # acc is stored in meters; UI shows ft by multiplying by 3.28084
                extra = {"acc": round(random.uniform(1 * FEET_TO_METERS, 1000 * FEET_TO_METERS), 1)}

                with transaction.atomic():
                    track_locked = LiveTrack.objects.select_for_update().get(pk=track.id)
                    geom = track_locked.geometry or {"type": "LineString", "coordinates": []}
                    coords = list(geom.get("coordinates") or [])
                    point_params = list(track_locked.point_params or [])
                    ts_list = [c[2] for c in coords]
                    idx = bisect.bisect_right(ts_list, timestamp_ms)
                    coords.insert(idx, new_point)
                    point_params.insert(idx, extra)
                    if len(coords) > max_points:
                        n_removed = len(coords) - max_points
                        coords = coords[n_removed:]
                        point_params = point_params[n_removed:]
                    track_locked.geometry = {"type": "LineString", "coordinates": coords}
                    track_locked.point_params = point_params
                    track_locked.updated_at = timezone.now()
                    track_locked.save(update_fields=["geometry", "point_params", "updated_at"])
                    broadcast_idx = next((i for i, c in enumerate(coords) if c == new_point), None)

                if broadcast_idx is not None:
                    broadcast_track_updated(user.id, str(track.id), new_point, extra, index=broadcast_idx)

                count += 1
                if count % 10 == 0 or count == 1:
                    self.stdout.write(f"  Streamed point #{count} at ({lat:.5f}, {lon:.5f})")
                if once:
                    break
                # Next point: random bearing 0–360°, random distance 50 ft–1 km
                bearing_deg = random.uniform(0.0, 360.0)
                distance_m = random.uniform(MIN_STEP_METERS, MAX_STEP_METERS)
                lat, lon = destination_point(lat, lon, bearing_deg, distance_m)
                time.sleep(interval)
        except KeyboardInterrupt:
            self.stdout.write(self.style.WARNING("\nStopped by user."))
        self.stdout.write(self.style.SUCCESS(f"Done. Streamed {count} point(s)."))

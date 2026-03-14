"""
Create dummy LiveTrack records with realistic geometry and point_params for
development/testing.

Uses the first available user (or --user email). Rerunning (without --delete)
deletes existing dummy tracks (identified by the "Dummy: " name prefix) and
recreates them with new random data. Use --delete to only remove dummy tracks.
"""
import math
import random
import secrets
import time
import uuid

from django.apps import apps
from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand

User = get_user_model()

EARTH_RADIUS_METERS = 6_371_000
DUMMY_NAME_PREFIX = "Dummy: "

TRACK_PRESETS = [
    {"name": "Morning Run",       "color": "#e74c3c", "style": "run"},
    {"name": "Evening Walk",      "color": "#2ecc71", "style": "walk"},
    {"name": "Bike Commute",      "color": "#3498db", "style": "bike"},
    {"name": "Road Trip",         "color": "#f39c12", "style": "drive"},
    {"name": "Hiking Trail",      "color": "#9b59b6", "style": "hike"},
    {"name": "Dog Walk",          "color": "#1abc9c", "style": "walk"},
    {"name": "Sprint Intervals",  "color": "#e67e22", "style": "run"},
    {"name": "Weekend Ride",      "color": "#6C93DE", "style": "bike"},
    {"name": "City Exploration",  "color": "#e84393", "style": "walk"},
    {"name": "Mountain Bike Loop","color": "#00b894", "style": "bike"},
    {"name": "Trail Run",         "color": "#fd79a8", "style": "run"},
    {"name": "Cross Country",     "color": "#636e72", "style": "hike"},
    {"name": "Night Ride",        "color": "#0984e3", "style": "bike"},
    {"name": "Recovery Jog",      "color": "#d63031", "style": "run"},
    {"name": "Delivery Route",    "color": "#fdcb6e", "style": "drive"},
]

# style -> (speed_kph_range, num_points_range, interval_sec_range,
#           base_altitude_range, altitude_variance)
STYLE_PARAMS = {
    "walk":  ((3.0,   6.0),  (30,  80),  (15, 30), (50,  300),   5),
    "run":   ((8.0,  15.0),  (40, 120),  (10, 20), (50,  400),  10),
    "bike":  ((15.0, 35.0),  (50, 200),  (10, 15), (50,  500),  15),
    "hike":  ((3.0,   5.0),  (60, 180),  (20, 60), (200, 2000), 50),
    "drive": ((30.0, 100.0), (40, 150),  (10, 30), (50,  800),  20),
}

REGIONS = [
    (-125.0, -66.0,  24.0,  49.0),  # Continental USA
    ( -10.0,  30.0,  35.0,  60.0),  # Europe
    ( 130.0, 155.0, -45.0, -10.0),  # Australia
    ( 125.0, 150.0,  25.0,  45.0),  # Japan / East Asia
    ( -80.0, -35.0, -35.0,   5.0),  # South America
]


def _destination_point(lat_deg, lon_deg, bearing_deg, distance_m):
    """Geodesic destination from (lat, lon) along bearing for distance_m."""
    lat_r = math.radians(lat_deg)
    lon_r = math.radians(lon_deg)
    brng_r = math.radians(bearing_deg)
    d_R = distance_m / EARTH_RADIUS_METERS
    lat2 = math.asin(
        math.sin(lat_r) * math.cos(d_R)
        + math.cos(lat_r) * math.sin(d_R) * math.cos(brng_r)
    )
    lon2 = lon_r + math.atan2(
        math.sin(brng_r) * math.sin(d_R) * math.cos(lat_r),
        math.cos(d_R) - math.sin(lat_r) * math.sin(lat2),
    )
    return math.degrees(lat2), math.degrees(lon2)


def _generate_track(style):
    """Build realistic coordinates and point_params for the given activity style."""
    spd_range, pts_range, interval_range, alt_range, alt_var = STYLE_PARAMS[style]

    lon_min, lon_max, lat_min, lat_max = random.choice(REGIONS)
    lon = random.uniform(lon_min, lon_max)
    lat = random.uniform(lat_min, lat_max)

    num_points = random.randint(*pts_range)
    interval_sec = random.uniform(*interval_range)

    now_ms = int(time.time() * 1000)
    start_ms = now_ms - int(num_points * interval_sec * 1000)

    base_alt = random.uniform(*alt_range)
    bearing = random.uniform(0, 360)
    base_speed = random.uniform(*spd_range)
    batt = random.uniform(40.0, 100.0)

    coords = []
    point_params = []

    for i in range(num_points):
        ts_ms = start_ms + int(i * interval_sec * 1000) + random.randint(-2000, 2000)

        bearing = (bearing + random.gauss(0, 15)) % 360
        speed = max(0.5, base_speed + random.gauss(0, base_speed * 0.15))
        distance_m = speed * 1000.0 / 3600.0 * interval_sec

        if i > 0:
            lat, lon = _destination_point(lat, lon, bearing, distance_m)

        alt = base_alt + random.gauss(0, alt_var)
        base_alt += random.gauss(0, alt_var * 0.1)

        coords.append([round(lon, 6), round(lat, 6), ts_ms])

        params = {
            "alt": round(alt, 1),
            "spd_kph": round(speed, 1),
            "bearing": round(bearing, 1),
            "acc": round(random.uniform(2.0, 25.0), 1),
            "sat": random.randint(4, 16),
            "prov": random.choice(["gps", "gps", "gps", "network"]),
            "batt": round(batt, 1),
        }
        batt = max(1.0, batt - random.uniform(0.01, 0.1))
        point_params.append(params)

    return coords, point_params


class Command(BaseCommand):
    help = "Create dummy LiveTrack records with realistic geometry and params."

    def add_arguments(self, parser):
        parser.add_argument(
            "--user",
            type=str,
            help="Email of the user to attach the tracks to. Default: first user.",
        )
        parser.add_argument(
            "--count",
            type=int,
            default=5,
            help="Number of dummy tracks to create (default: 5, max: 15).",
        )
        parser.add_argument(
            "--delete",
            action="store_true",
            help="Only delete existing dummy tracks for the user; do not create any.",
        )

    def handle(self, *args, **options):
        try:
            LiveTrack = apps.get_model("live_track", "LiveTrack")
        except LookupError:
            self.stdout.write(self.style.ERROR(
                "App 'live_track' not found. Enable the Live Track extension and try again."
            ))
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

        to_delete = LiveTrack.objects.filter(user=user, name__startswith=DUMMY_NAME_PREFIX)
        deleted_count, _ = to_delete.delete()

        if options.get("delete"):
            self.stdout.write(self.style.SUCCESS(
                f"Done. Deleted {deleted_count} dummy track(s) for {user.email}."
            ))
            return

        count = max(1, min(options["count"], len(TRACK_PRESETS)))
        presets = random.sample(TRACK_PRESETS, count)

        visibilities = ["private"] * count
        if count >= 3:
            visibilities[1] = "public"
        if count >= 5:
            visibilities[3] = "shared"
        random.shuffle(visibilities)

        created = 0
        for i, preset in enumerate(presets):
            coords, point_params = _generate_track(preset["style"])
            name = f"{DUMMY_NAME_PREFIX}{preset['name']}"
            visibility = visibilities[i]

            settings = {"color": preset["color"]}
            if random.random() < 0.3:
                settings["recent_data_window"] = random.choice(["1h", "1d", "1w"])

            LiveTrack.objects.create(
                id=uuid.uuid4(),
                tracker_secret=secrets.token_urlsafe(32),
                name=name,
                user=user,
                settings=settings,
                visibility=visibility,
                share_params_with_recipients=(visibility != "private" and random.random() < 0.5),
                geometry={"type": "LineString", "coordinates": coords},
                point_params=point_params,
            )
            self.stdout.write(self.style.SUCCESS(
                f"  Created: {name} ({visibility}, {len(coords)} pts, style={preset['style']})"
            ))
            created += 1

        self.stdout.write(self.style.SUCCESS(
            f"Done. Deleted {deleted_count}, created {created} dummy track(s) for {user.email}."
        ))

"""
Create 3 dummy LiveTrack records for development/testing.

Uses the first available user (or --user email). Rerunning (without --delete)
deletes existing dummy tracks by name and recreates them with new random
locations/spans. Use --delete to only remove the dummy tracks.
"""
import random
import secrets
import uuid

from django.apps import apps
from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model

User = get_user_model()

DUMMY_TRACKS = [
    {"name": "Dummy Track A", "color": "#3388ff"},
    {"name": "Dummy Track B", "color": "#e74c3c"},
    {"name": "Dummy Track C", "color": "#2ecc71"},
]

# Continental USA bounds (lon, lat)
USA_LON_MIN, USA_LON_MAX = -125.0, -66.0
USA_LAT_MIN, USA_LAT_MAX = 24.0, 49.0
BASE_TS_MS = 1700000000000


def _random_track_coords():
    """Generate one track: random start in USA, random length (3–14 points), random step size (span)."""
    lon = random.uniform(USA_LON_MIN, USA_LON_MAX)
    lat = random.uniform(USA_LAT_MIN, USA_LAT_MAX)
    num_points = random.randint(3, 14)
    # Step size: some tracks short (e.g. a few km), some long (e.g. tens of km). ~0.01 deg ≈ 1 km.
    step_deg = random.uniform(0.005, 0.08)
    coords = []
    for i in range(num_points):
        coords.append([round(lon, 6), round(lat, 6), BASE_TS_MS + i * 60000])
        if i < num_points - 1:
            lon += random.uniform(-step_deg, step_deg)
            lat += random.uniform(-step_deg, step_deg)
            lon = max(USA_LON_MIN, min(USA_LON_MAX, lon))
            lat = max(USA_LAT_MIN, min(USA_LAT_MAX, lat))
    return coords


class Command(BaseCommand):
    help = "Create 3 dummy LiveTrack records for the first user (or --user email)."

    def add_arguments(self, parser):
        parser.add_argument(
            "--user",
            type=str,
            help="Email of the user to attach the tracks to. Default: first user.",
        )
        parser.add_argument(
            "--delete",
            action="store_true",
            help="Only delete the 3 dummy tracks for the user; do not create any.",
        )

    def handle(self, *args, **options):
        try:
            LiveTrack = apps.get_model("live_track", "LiveTrack")
        except LookupError:
            self.stdout.write(self.style.ERROR("App 'live_track' not found. Enable the Live Track extension and try again."))
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

        dummy_names = [spec["name"] for spec in DUMMY_TRACKS]
        to_delete = LiveTrack.objects.filter(user=user, name__in=dummy_names)
        deleted_count, _ = to_delete.delete()

        if options.get("delete"):
            self.stdout.write(self.style.SUCCESS(f"Done. Deleted {deleted_count} dummy track(s) for {user.email}."))
            return

        created = 0
        for spec in DUMMY_TRACKS:
            coords = _random_track_coords()
            LiveTrack.objects.create(
                id=uuid.uuid4(),
                tracker_secret=secrets.token_urlsafe(32),
                name=spec["name"],
                user=user,
                color=spec["color"],
                geometry={"type": "LineString", "coordinates": coords} if coords else {},
                point_params=[{}] * len(coords) if coords else [],
            )
            self.stdout.write(self.style.SUCCESS(f"  Created: {spec['name']}"))
            created += 1

        self.stdout.write(self.style.SUCCESS(f"Done. Deleted {deleted_count}, created {created} dummy track(s) for {user.email}."))

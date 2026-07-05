"""
Dump a tracker's data (metadata, geometry, point_params) to a JSON file for later replay
via ``replay_tracker``. Per-device credentials (tracker_secret, hauk_password) are
intentionally excluded; replay always mints fresh ones.
"""
import json
import re
import time

from django.apps import apps
from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand
from django.utils import timezone

User = get_user_model()

SCHEMA_VERSION = 1


def _slugify(name: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "_", name.strip()).strip("_").lower()
    return slug or "tracker"


class Command(BaseCommand):
    help = "Dump a tracker's data to a JSON file for later replay via replay_tracker."

    def add_arguments(self, parser):
        parser.add_argument(
            "--email",
            type=str,
            required=True,
            help="Email of the account that owns the tracker.",
        )
        parser.add_argument(
            "--name",
            type=str,
            required=True,
            help="Name of the tracker to dump.",
        )
        parser.add_argument(
            "--output",
            type=str,
            default=None,
            help="Output file path (default: tracker_dump_<name>_<unixtime>.json in the cwd).",
        )

    def handle(self, *args, **options):
        try:
            LiveTrack = apps.get_model("live_track", "LiveTrack")
        except LookupError:
            self.stdout.write(
                self.style.ERROR("App 'live_track' not found. Enable the Live Track extension and try again.")
            )
            return

        email = options["email"]
        name = options["name"]

        user = User.objects.filter(email=email).first()
        if not user:
            self.stdout.write(self.style.ERROR(f"No user with email {email!r}."))
            return

        track = LiveTrack.objects.filter(user=user, name=name).first()
        if not track:
            self.stdout.write(self.style.ERROR(f"No tracker named {name!r} for {email!r}."))
            return

        output_path = options["output"] or f"tracker_dump_{_slugify(name)}_{int(time.time())}.json"

        geometry = track.geometry or {"type": "LineString", "coordinates": []}
        point_params = track.point_params or []

        dump = {
            "schema_version": SCHEMA_VERSION,
            "dumped_at": timezone.now().isoformat(),
            "source": {"email": email, "name": name},
            "tracker": {
                "name": track.name,
                "settings": track.settings or {},
                "visibility": track.visibility,
                "share_params_with_recipients": track.share_params_with_recipients,
                "share_params_with_world": track.share_params_with_world,
                "geometry": geometry,
                "point_params": point_params,
            },
        }

        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(dump, f, indent=2)

        point_count = len(geometry.get("coordinates") or [])
        self.stdout.write(
            self.style.SUCCESS(
                f"Dumped tracker {track.name!r} ({point_count} point(s)) to {output_path}"
            )
        )

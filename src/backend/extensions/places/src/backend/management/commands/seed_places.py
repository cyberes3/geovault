"""
Create dummy places for the current (or given) user to test the places extension.
Varies description length, created_at, updated_at, and last_navigated_at.
"""
import json
from datetime import timedelta

from django.core.management.base import BaseCommand, CommandError
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import GEOSGeometry
from django.utils import timezone

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature

from ...models import PlaceMetadata


# Base coordinates (e.g. Berlin area) – we'll offset slightly per place so they're distinct
BASE_LON, BASE_LAT = 13.404954, 52.520008
OFFSET = 0.002


def make_point(index):
    return [BASE_LON + (index % 5) * OFFSET, BASE_LAT + (index // 5) * OFFSET, 0.0]


# Dummy place definitions: (name, description)
# Description length: none, short, medium, long, very long
PLACE_DEFINITIONS = [
    ("Quick stop", ""),
    ("Park bench", "Quick rest."),
    ("Coffee shop", "A nice spot for a flat white. Opens at 8."),
    (
        "Library",
        "Central library. Quiet study areas on the second floor. "
        "Good WiFi. Open Mon–Fri 9–20, Sat 10–18. Café in the lobby.",
    ),
    (
        "Viewpoint",
        "This is a longer description to test how the UI handles multi-line and "
        "wrapping text. The viewpoint overlooks the river and the old town. "
        "Best at sunset. Bring a jacket – it can get windy. There's a small "
        "kiosk nearby for drinks and snacks. Parking is limited; consider "
        "cycling or taking the bus (lines 12 and 24 stop at the bottom of the hill).",
    ),
    ("Unnamed spot", None),
    ("Train station", "Hauptbahnhof. Connections to regional and long-distance."),
    ("Museum", "Museum of local history. Free on Thursdays after 14:00."),
    ("Fountain", "F"),
    (
        "Restaurant",
        "Italian restaurant. Pizza and pasta. Outdoor seating in summer. "
        "Reservations recommended on weekends. Closed Mondays.",
    ),
    ("Bus stop", ""),
    ("Playground", "Kids playground with swings and slide. Fenced. Bench for parents."),
    (
        "Long description place",
        "This place exists only to have a very long description. "
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
        "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. "
        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. "
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum.",
    ),
    ("Lake", "Small lake. Swimming allowed in designated area. No boats."),
    ("Parking lot", "P+R. Cheap all-day parking. Connects to tram line 4."),
]


class Command(BaseCommand):
    help = "Create dummy places for testing. Varies description length, created/modified/last-navigated times."

    def add_arguments(self, parser):
        parser.add_argument(
            "--user",
            type=str,
            help="Email of the user to create places for. Default: first superuser.",
        )
        parser.add_argument(
            "--count",
            type=int,
            default=None,
            help="Number of places to create (default: all %d)." % len(PLACE_DEFINITIONS),
        )
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="Print what would be created without writing to the database.",
        )

    def handle(self, *args, **options):
        User = get_user_model()
        user_identifier = options["user"]
        count = options.get("count") or len(PLACE_DEFINITIONS)
        dry_run = options["dry_run"]

        if user_identifier:
            user = User.objects.filter(email=user_identifier).first()
            if not user:
                raise CommandError("User with email %r not found." % user_identifier)
        else:
            user = User.objects.filter(is_superuser=True).order_by("id").first()
            if not user:
                user = User.objects.order_by("id").first()
            if not user:
                raise CommandError("No user found. Create a user or pass --user=email.")

        definitions = PLACE_DEFINITIONS[: min(count, len(PLACE_DEFINITIONS))]
        now = timezone.now()

        # Vary created_at over the last 30 days; then updated_at and last_navigated_at
        # so that sort-by-created, sort-by-modified, sort-by-navigated all show variety
        for i, (name, description) in enumerate(definitions):
            days_ago_created = (i * 3) % 31  # 0, 3, 6, ... 30
            created_at = now - timedelta(days=days_ago_created)
            # updated_at: sometimes same as created, sometimes 0–6 days after created (never in future)
            days_after_created = (i % 4) * 2
            updated_at = min(created_at + timedelta(days=days_after_created), now)
            # last_navigated_at: for ~half of places set; otherwise None
            if i % 2 == 0:
                days_ago_nav = (i % 7) + 1
                last_navigated_at = now - timedelta(days=days_ago_nav)
            else:
                last_navigated_at = None

            geojson = {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": make_point(i)},
                "properties": {
                    "name": name,
                    "description": description if description is not None else "",
                },
            }
            normalized = validate_and_normalize_geojson_feature(
                geojson, preserve_system_tags=None, preserve_geojson_hash=False
            )
            geom_dict = normalized["geometry"]
            if geom_dict.get("type") == "Point" and len(geom_dict.get("coordinates", [])) == 2:
                geom_dict["coordinates"] = [*geom_dict["coordinates"], 0]
            geometry = GEOSGeometry(json.dumps(geom_dict))
            geojson_hash = generate_geojson_hash(normalized)

            if dry_run:
                self.stdout.write(
                    "Would create: %r (desc len=%s) created=%s updated=%s last_nav=%s"
                    % (name, len(description or ""), created_at.date(), updated_at.date(), last_navigated_at)
                )
                continue

            feature = FeatureStore.objects.create(
                user=user,
                scope="places",
                geojson=normalized,
                geometry=geometry,
                geojson_hash=geojson_hash,
            )
            FeatureStore.objects.filter(pk=feature.id).update(timestamp=created_at)

            meta = PlaceMetadata.objects.create(
                feature=feature,
                updated_at=updated_at,
                last_navigated_at=last_navigated_at,
            )

            self.stdout.write(
                "Created place id=%s %r (created=%s updated=%s last_nav=%s)"
                % (feature.id, name, created_at.date(), updated_at.date(), last_navigated_at)
            )

        if dry_run:
            self.stdout.write(self.style.SUCCESS("Dry run: would create %d places." % len(definitions)))
        else:
            self.stdout.write(self.style.SUCCESS("Created %d dummy places for %s." % (len(definitions), user.email)))

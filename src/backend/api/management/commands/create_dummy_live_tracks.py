"""
Create dummy LiveTrack records with realistic geometry and point_params for
development/testing.

Creates 10 tracks, 3 groups (2+2+3 track members), world shares (tracks and
groups), direct shares to other users, public visibility, group visibility
(public/shared/private), and group direct shares (LiveTrackGroupShare).
Also creates one extra track, "Stale data test (active but dead)", whose
last point timestamp is ~20 minutes in the past while the track row is new,
for testing live_track / Android stale-data highlighting.
Uses the first available user (or --user email). Rerunning
(without --delete) deletes existing dummy tracks and groups (by name prefix)
and recreates them. Use --delete to only remove dummy data.
"""
import math
import random
import secrets
import time
import uuid

from django.apps import apps
from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand
from django.db.models import Q

User = get_user_model()

EARTH_RADIUS_METERS = 6_371_000
DUMMY_NAME_PREFIX = "Dummy: "
DUMMY_GROUP_PREFIX = "Dummy Group: "
# Extra track (not counted in NUM_TRACKS) for stale-data UI tests
STALE_DATA_TEST_TRACK_NAME = f"{DUMMY_NAME_PREFIX}Stale data test (active but dead)"
# Last point is this many minutes before "now" at create time (must be > 10 for highlight rules)
STALE_LAST_POINT_AGE_MINUTES = 20

NUM_TRACKS = 10
NUM_GROUPS = 3
NUM_WORLD_SHARES = 3
NUM_TRACKS_SHARED_WITH_USERS = 5
NUM_PUBLIC_TRACKS = 3
NUM_GROUPS_SHARED_WITH_USERS = 2
NUM_PUBLIC_GROUPS = 1  # 1 group is public

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
]

GROUP_NAMES = ["Family", "Team", "Public"]

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


def _generate_stale_active_dead_track():
    """
    Two-point line with timestamps ~STALE_LAST_POINT_AGE_MINUTES in the past.
    When saved as a new row, live_track's updated_at is current but last
    data is stale — matches "active but dead" product rules.
    """
    lon_min, lon_max, lat_min, lat_max = random.choice(REGIONS)
    lon0 = round(random.uniform(lon_min, lon_max), 6)
    lat0 = round(random.uniform(lat_min, lat_max), 6)
    age_ms = int(STALE_LAST_POINT_AGE_MINUTES * 60 * 1000)
    now_ms = int(time.time() * 1000)
    ts0 = now_ms - age_ms - 60_000
    ts1 = now_ms - age_ms
    lat1, lon1 = _destination_point(lat0, lon0, random.uniform(0, 360), 30.0)

    coords = [
        [lon0, lat0, float(ts0)],
        [round(lon1, 6), round(lat1, 6), float(ts1)],
    ]
    point_params = [
        {
            "alt": 100.0,
            "spd_kph": 5.0,
            "bearing": 0.0,
            "acc": 10.0,
            "sat": 8,
            "prov": "gps",
            "batt": 80.0,
        },
        {
            "alt": 100.0,
            "spd_kph": 5.0,
            "bearing": 0.0,
            "acc": 10.0,
            "sat": 8,
            "prov": "gps",
            "batt": 80.0,
        },
    ]
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
            "--delete",
            action="store_true",
            help="Only delete existing dummy tracks and groups for target user(s); do not create any.",
        )

    def handle(self, *args, **options):
        try:
            LiveTrack = apps.get_model("live_track", "LiveTrack")
            LiveTrackGroup = apps.get_model("live_track", "LiveTrackGroup")
            LiveTrackGroupMember = apps.get_model("live_track", "LiveTrackGroupMember")
            LiveTrackGroupShare = apps.get_model("live_track", "LiveTrackGroupShare")
            LiveTrackGroupWorldShare = apps.get_model("live_track", "LiveTrackGroupWorldShare")
            LiveTrackShare = apps.get_model("live_track", "LiveTrackShare")
            LiveTrackWorldShare = apps.get_model("live_track", "LiveTrackWorldShare")
            from extensions.live_track.src.backend.helpers import generate_hauk_password
        except LookupError:
            self.stdout.write(self.style.ERROR(
                "App 'live_track' not found. Enable the Live Track extension and try again."
            ))
            return

        selected_user = None
        if options.get("user"):
            selected_user = User.objects.filter(email=options["user"]).first()
            if not selected_user:
                self.stdout.write(self.style.ERROR(f"No user with email {options['user']!r}."))
                return

        all_users = list(User.objects.order_by("pk"))
        if not all_users:
            self.stdout.write(self.style.ERROR("No users in the database. Create at least one user first."))
            return

        target_users = [selected_user] if selected_user else all_users
        for user in target_users:
            to_delete_tracks = LiveTrack.objects.filter(user=user, name__startswith=DUMMY_NAME_PREFIX)
            deleted_tracks, _ = to_delete_tracks.delete()
            to_delete_groups = LiveTrackGroup.objects.filter(
                user=user, name__startswith=DUMMY_GROUP_PREFIX
            )
            deleted_groups, _ = to_delete_groups.delete()
            # Also clean cross-user dummy re-share groups that target this user,
            # even when those groups are owned by a different user.
            cross_user_groups = LiveTrackGroup.objects.filter(
                Q(name=f"{DUMMY_GROUP_PREFIX}Re-share from {user.email}")
                | Q(name=f"{DUMMY_GROUP_PREFIX}{user.email} Shared Picks")
            )
            cross_user_groups.delete()

        if options.get("delete"):
            if selected_user:
                self.stdout.write(self.style.SUCCESS(
                    f"Done. Deleted dummy data for {selected_user.email} (tracks + groups)."
                ))
            else:
                self.stdout.write(self.style.SUCCESS(
                    f"Done. Deleted dummy data for {len(target_users)} user(s) (tracks + groups)."
                ))
            return

        self.stdout.write(
            self.style.NOTICE(
                f"Creating dummy tracks and groups for {len(target_users)} user(s) "
                f"({', '.join(u.email or str(u.pk) for u in target_users)})..."
            )
        )
        user_data = {}
        total_tracks = 0
        total_groups = 0

        try:
            for user in target_users:
                other_users = [u for u in all_users if u.pk != user.pk]

                # Visibility: 3 public, 5 shared (with other users), 2 private
                visibilities = (
                    ["public"] * NUM_PUBLIC_TRACKS
                    + ["shared"] * NUM_TRACKS_SHARED_WITH_USERS
                    + ["private"] * (NUM_TRACKS - NUM_PUBLIC_TRACKS - NUM_TRACKS_SHARED_WITH_USERS)
                )
                random.shuffle(visibilities)

                tracks = []
                for i, preset in enumerate(TRACK_PRESETS[:NUM_TRACKS]):
                    coords, point_params = _generate_track(preset["style"])
                    name = f"{DUMMY_NAME_PREFIX}{preset['name']}"
                    visibility = visibilities[i]
                    # Keep dummy tracks unwindowed so API metadata always includes
                    # last_point; otherwise recent_data_window can age out all points
                    # and Android list views show "waiting for data".
                    settings = {"color": preset["color"]}
                    track = LiveTrack.objects.create(
                        id=uuid.uuid4(),
                        tracker_secret=secrets.token_urlsafe(32),
                        hauk_password=generate_hauk_password(),
                        name=name,
                        user=user,
                        settings=settings,
                        visibility=visibility,
                        share_params_with_recipients=(visibility != "private" and random.random() < 0.5),
                        geometry={"type": "LineString", "coordinates": coords},
                        point_params=point_params,
                    )
                    tracks.append(track)
                    self.stdout.write(self.style.SUCCESS(
                        f"  [{user.email}] Created: {name} ({visibility}, {len(coords)} pts)"
                    ))

                s_coords, s_params = _generate_stale_active_dead_track()
                stale = LiveTrack.objects.create(
                    id=uuid.uuid4(),
                    tracker_secret=secrets.token_urlsafe(32),
                    hauk_password=generate_hauk_password(),
                    name=STALE_DATA_TEST_TRACK_NAME,
                    user=user,
                    settings={"color": "#c0392b"},
                    visibility="private",
                    share_params_with_recipients=False,
                    geometry={"type": "LineString", "coordinates": s_coords},
                    point_params=s_params,
                )
                tracks.append(stale)
                self.stdout.write(self.style.NOTICE(
                    f"  [{user.email}] Stale-data test: {stale.name} (private, 2 pts, "
                    f"last point ≈{STALE_LAST_POINT_AGE_MINUTES}m old — enable highlight in Live Track → Settings)"
                ))

                # Groups: 3 groups, 2 + 2 + 3 track members; visibility: Family=shared, Team=shared, Public=public
                group_track_assignments = [
                    (0, [0, 1]),           # group 0: Family, 2 tracks
                    (1, [2, 3]),           # group 1: Team, 2 tracks
                    (2, [4, 5, 6]),        # group 2: Public, 3 tracks
                ]
                group_visibilities = ["shared", "shared", "public"]  # Family, Team, Public
                groups = []
                for gidx, gname in enumerate(GROUP_NAMES):
                    grp = LiveTrackGroup.objects.create(
                    name=f"{DUMMY_GROUP_PREFIX}{gname} ({user.email})",
                        user=user,
                        visibility=group_visibilities[gidx],
                    )
                    groups.append(grp)
                    for track_idx in group_track_assignments[gidx][1]:
                        LiveTrackGroupMember.objects.create(group=grp, track=tracks[track_idx])
                    self.stdout.write(self.style.SUCCESS(
                        f"  [{user.email}] Group: {grp.name} ({group_visibilities[gidx]}, "
                        f"{len(group_track_assignments[gidx][1])} tracks)"
                    ))

                # World share: a few tracks
                world_share_indices = random.sample(range(NUM_TRACKS), min(NUM_WORLD_SHARES, NUM_TRACKS))
                for idx in world_share_indices:
                    LiveTrackWorldShare.objects.create(
                        track=tracks[idx],
                        share_id=str(uuid.uuid4()),
                    )
                    tracks[idx].share_params_with_world = random.choice([True, False])
                    tracks[idx].save(update_fields=["share_params_with_world"])
                self.stdout.write(self.style.SUCCESS(
                    f"  [{user.email}] World share enabled on {len(world_share_indices)} track(s)."
                ))

                # Group world share: enable for at least one group (e.g. Public)
                group_world_share_idx = NUM_GROUPS - 1
                LiveTrackGroupWorldShare.objects.get_or_create(
                    group=groups[group_world_share_idx],
                    defaults={"share_id": str(uuid.uuid4())},
                )
                self.stdout.write(self.style.SUCCESS(
                    f"  [{user.email}] Group world share: enabled on {groups[group_world_share_idx].name}."
                ))

                user_data[user.pk] = {
                    "user": user,
                    "other_users": other_users,
                    "tracks": tracks,
                    "groups": groups,
                    "visibilities": visibilities,
                }
                total_tracks += len(tracks)
                total_groups += len(groups)

        except Exception as e:
            self.stdout.write(self.style.ERROR(f"Error creating dummy data: {e}"))
            raise

        # Share each user's shared tracks with several other users.
        for payload in user_data.values():
            user = payload["user"]
            other_users = payload["other_users"]
            tracks = payload["tracks"]
            visibilities = payload["visibilities"]

            shared_track_indices = [i for i in range(NUM_TRACKS) if visibilities[i] == "shared"]
            shared_track_indices = shared_track_indices[:NUM_TRACKS_SHARED_WITH_USERS]

            if other_users:
                recipient_count = min(3, len(other_users))
                for i, idx in enumerate(shared_track_indices):
                    recipients = [
                        other_users[(i + shift) % len(other_users)]
                        for shift in range(recipient_count)
                    ]
                    for recipient in recipients:
                        LiveTrackShare.objects.get_or_create(track=tracks[idx], shared_with=recipient)
                self.stdout.write(self.style.SUCCESS(
                    f"  [{user.email}] Shared {len(shared_track_indices)} track(s) "
                    f"with {recipient_count} recipient(s) each."
                ))
            else:
                self.stdout.write(self.style.WARNING(
                    f"  [{user.email}] No other users in DB; skipped direct track shares."
                ))

            payload["shared_track_indices"] = shared_track_indices

        # Share each user's shared groups with other users.
        for payload in user_data.values():
            user = payload["user"]
            other_users = payload["other_users"]
            groups = payload["groups"]

            if other_users:
                recipient_count = min(3, len(other_users))
                for gidx in range(NUM_GROUPS_SHARED_WITH_USERS):
                    recipients = [
                        other_users[(gidx + shift) % len(other_users)]
                        for shift in range(recipient_count)
                    ]
                    for recipient in recipients:
                        LiveTrackGroupShare.objects.get_or_create(
                            group=groups[gidx], shared_with=recipient
                        )
                self.stdout.write(self.style.SUCCESS(
                    f"  [{user.email}] Group direct shares: {NUM_GROUPS_SHARED_WITH_USERS} "
                    f"group(s) shared with {recipient_count} recipient(s) each."
                ))
            else:
                self.stdout.write(self.style.WARNING(
                    f"  [{user.email}] No other users; skipped group direct shares."
                ))

        # Cross-user re-share seed data:
        # owner shares track -> other user adds to group -> shares group back to owner.
        # Keep acceptance pending (no auto-subscriptions), and hide owner-side re-share
        # groups from the owner's group list so this test fixture does not look like
        # an auto-accepted share in normal UI views.
        for payload in user_data.values():
            user = payload["user"]
            other_users = payload["other_users"]
            tracks = payload["tracks"]
            shared_track_indices = payload.get("shared_track_indices") or []

            if not (other_users and shared_track_indices):
                self.stdout.write(self.style.WARNING(
                    f"  [{user.email}] No other users or shared tracks; skipped cross-user re-share."
                ))
                continue

            reshare_track = tracks[shared_track_indices[0]]
            reshare_track.settings = {**(reshare_track.settings or {}), "allow_group_reshare": True}
            reshare_track.save(update_fields=["settings"])

            reshare_user = other_users[0]
            reshare_group = LiveTrackGroup.objects.create(
                name=f"{DUMMY_GROUP_PREFIX}Re-share from {user.email}",
                user=reshare_user,
                visibility="shared",
                hidden=True,
            )
            LiveTrackGroupMember.objects.get_or_create(group=reshare_group, track=reshare_track)
            LiveTrackGroupShare.objects.get_or_create(
                group=reshare_group,
                shared_with=user,
            )
            self.stdout.write(self.style.SUCCESS(
                f"  Cross-user re-share: {user.email} shared '{reshare_track.name}', "
                f"{reshare_user.email} added it to '{reshare_group.name}', and shared group back "
                f"to {user.email} (pending acceptance)."
            ))

        if selected_user:
            self.stdout.write(self.style.SUCCESS(
                f"Done. Created {total_tracks} tracks, {total_groups} groups for {selected_user.email}."
            ))
        else:
            self.stdout.write(self.style.SUCCESS(
                f"Done. Created {total_tracks} tracks, {total_groups} groups for "
                f"{len(target_users)} user(s)."
            ))

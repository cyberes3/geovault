"""
Replay a tracker dump (produced by ``dump_tracker``) into a new tracker on a target
account, streaming points one at a time through the real ingestion path
(``append_point_to_track``) with the same relative timing as the original recording.
Intended for testing Android live-tracking streaming against realistic data.
"""
import json
import secrets
import time
import uuid

from django.apps import apps
from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand
from django.utils import timezone

from ...helpers import generate_hauk_password
from ...ingress_views import append_point_to_track

User = get_user_model()


class Command(BaseCommand):
    help = "Replay a tracker dump file into a new tracker on a target account, streaming points with the original timing."

    def add_arguments(self, parser):
        parser.add_argument(
            "--file",
            type=str,
            required=True,
            help="Path to a dump JSON file produced by dump_tracker.",
        )
        parser.add_argument(
            "--email",
            type=str,
            required=True,
            help="Email of the account to create the new tracker in.",
        )
        parser.add_argument(
            "--speed",
            type=float,
            default=1.0,
            help="Playback speed multiplier (default 1.0 = exact original timing/gaps; 2.0 = twice as fast).",
        )
        parser.add_argument(
            "--share-with",
            type=str,
            action="append",
            default=None,
            help="Email of an account to share the new tracker with (repeatable). Sets the tracker to "
            "visibility=shared and grants params access to make testing easier. The source tracker's "
            "original share state (direct shares, world/internal share links, subscribers) is never copied.",
        )

    def handle(self, *args, **options):
        try:
            LiveTrack = apps.get_model("live_track", "LiveTrack")
        except LookupError:
            self.stdout.write(
                self.style.ERROR("App 'live_track' not found. Enable the Live Track extension and try again.")
            )
            return

        speed = options["speed"]
        if speed <= 0:
            self.stdout.write(self.style.ERROR("--speed must be greater than 0."))
            return

        email = options["email"]
        user = User.objects.filter(email=email).first()
        if not user:
            self.stdout.write(self.style.ERROR(f"No user with email {email!r}."))
            return

        share_with_users = []
        for share_email in options.get("share_with") or []:
            share_user = User.objects.filter(email=share_email).first()
            if not share_user:
                self.stdout.write(self.style.WARNING(f"Skipping --share-with {share_email!r}: no such user."))
                continue
            share_with_users.append(share_user)

        try:
            with open(options["file"], "r", encoding="utf-8") as f:
                dump = json.load(f)
        except OSError as e:
            self.stdout.write(self.style.ERROR(f"Could not read {options['file']!r}: {e}"))
            return
        except json.JSONDecodeError as e:
            self.stdout.write(self.style.ERROR(f"Invalid JSON in {options['file']!r}: {e}"))
            return

        tracker_data = dump.get("tracker")
        if not tracker_data:
            self.stdout.write(self.style.ERROR("Dump file is missing the 'tracker' section."))
            return

        original_name = tracker_data.get("name") or "Tracker"
        geometry = tracker_data.get("geometry") or {"type": "LineString", "coordinates": []}
        coords = list(geometry.get("coordinates") or [])
        point_params = list(tracker_data.get("point_params") or [])
        if len(point_params) < len(coords):
            point_params.extend({} for _ in range(len(coords) - len(point_params)))
        elif len(point_params) > len(coords):
            point_params = point_params[: len(coords)]

        points = list(zip(coords, point_params))
        points.sort(key=lambda cp: cp[0][2] if len(cp[0]) >= 3 else 0)

        timestamp_str = timezone.now().strftime("%Y-%m-%d %H:%M:%S")
        new_name = f"{original_name} {timestamp_str}"

        # The source tracker's share state (visibility, share flags, LiveTrackShare rows,
        # world/internal share links, subscribers) is never copied; sharing for the new
        # tracker is driven solely by --share-with.
        track = LiveTrack.objects.create(
            id=uuid.uuid4(),
            tracker_secret=secrets.token_urlsafe(32),
            hauk_password=generate_hauk_password(),
            name=new_name,
            user=user,
            settings=tracker_data.get("settings") or {},
            visibility="shared" if share_with_users else "private",
            share_params_with_recipients=bool(share_with_users),
            share_params_with_world=False,
            geometry={"type": "LineString", "coordinates": []},
            point_params=[],
        )
        self.stdout.write(self.style.SUCCESS(f"Created tracker: {track.name} ({track.id})"))

        if share_with_users:
            LiveTrackShare = apps.get_model("live_track", "LiveTrackShare")
            LiveTrackShare.objects.bulk_create(
                [LiveTrackShare(track=track, shared_with=share_user) for share_user in share_with_users]
            )
            self.stdout.write(
                self.style.SUCCESS(
                    f"Shared with: {', '.join(u.email for u in share_with_users)}"
                )
            )

        if not points:
            self.stdout.write(self.style.WARNING("Dump has no points; nothing to replay."))
            return

        origin_ts_ms = points[0][0][2]
        now_ms = int(timezone.now().timestamp() * 1000)

        def rebase(ts_ms) -> int:
            return int(now_ms + (ts_ms - origin_ts_ms) / speed)

        self.stdout.write(
            f"Replaying {len(points)} point(s) at speed={speed:.2f}x "
            f"(original span: {(points[-1][0][2] - origin_ts_ms) / 1000.0:.1f}s)"
        )

        count = 0
        prev_rebased_ts_ms = None
        try:
            for coord, params in points:
                lon, lat = coord[0], coord[1]
                orig_ts_ms = coord[2] if len(coord) >= 3 else origin_ts_ms
                rebased_ts_ms = rebase(orig_ts_ms)

                if prev_rebased_ts_ms is not None:
                    delay_seconds = max(0.0, (rebased_ts_ms - prev_rebased_ts_ms) / 1000.0)
                    if delay_seconds > 0:
                        time.sleep(delay_seconds)
                prev_rebased_ts_ms = rebased_ts_ms

                extra = dict(params) if isinstance(params, dict) else {}
                if "starttimestamp" in extra and extra["starttimestamp"] is not None:
                    extra["starttimestamp"] = rebase(extra["starttimestamp"])

                append_point_to_track(track, lat, lon, rebased_ts_ms, extra)
                count += 1
                self.stdout.write(f"  Replayed #{count}/{len(points)} at ({lat:.5f}, {lon:.5f})")
        except KeyboardInterrupt:
            self.stdout.write(self.style.WARNING("\nStopped by user."))

        self.stdout.write(
            self.style.SUCCESS(f"Done. Replayed {count}/{len(points)} point(s) into {track.name!r} ({track.id}).")
        )

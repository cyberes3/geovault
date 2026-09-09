import pytest
from django.contrib.auth import get_user_model

from api.sharing.service import ShareService
from extensions.live_track.src.backend.access import TrackerAccessPolicy, world_visible_group_tracks
from extensions.live_track.src.backend.models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
)
from geo_lib.sharing.access_policy import AccessRole
from geo_lib.sharing.constants import AUDIENCE_WORLD, KIND_LIVE_TRACK
from geo_lib.track.payload import TrackPayloadMode, redact_track_payload

User = get_user_model()


@pytest.mark.django_db
class TestTrackerAccessPolicy:
    def test_world_requires_own_share_link(self):
        owner = User.objects.create_user("owner@example.com", "password")
        track = LiveTrack.objects.create(
            tracker_secret="secret-access-1",
            name="Private Track",
            user=owner,
            visibility="private",
        )
        policy = TrackerAccessPolicy(track)
        assert policy.resolve(None, world_link=True) == AccessRole.NONE
        ShareService.ensure_tracker_link(owner, KIND_LIVE_TRACK, track.id, AUDIENCE_WORLD)
        assert policy.resolve(None, world_link=True) == AccessRole.WORLD
        assert policy.can_read_data(AccessRole.WORLD) is True

    def test_group_world_filters_members_without_world_link(self):
        owner = User.objects.create_user("gowner@example.com", "password")
        visible = LiveTrack.objects.create(
            tracker_secret="secret-access-2",
            name="Visible",
            user=owner,
        )
        hidden = LiveTrack.objects.create(
            tracker_secret="secret-access-3",
            name="Hidden",
            user=owner,
        )
        group = LiveTrackGroup.objects.create(name="Crew", user=owner, visibility="public")
        LiveTrackGroupMember.objects.create(group=group, track=visible)
        LiveTrackGroupMember.objects.create(group=group, track=hidden)
        ShareService.ensure_tracker_link(owner, KIND_LIVE_TRACK, visible.id, AUDIENCE_WORLD)
        names = [t.name for t in world_visible_group_tracks(group)]
        assert names == ["Visible"]

    def test_full_history_is_owner_only(self):
        owner = User.objects.create_user("hist@example.com", "password")
        track = LiveTrack.objects.create(
            tracker_secret="secret-access-4",
            name="History",
            user=owner,
            visibility="public",
        )
        policy = TrackerAccessPolicy(track)
        assert policy.can_read_full_history(AccessRole.OWNER) is True
        assert policy.can_read_full_history(AccessRole.AUTH_PUBLIC) is False
        assert policy.can_read_full_history(AccessRole.WORLD) is False


class TestTrackPayloadRedact:
    def test_world_strips_account_pii(self):
        payload = {
            "id": "t1",
            "name": "Track",
            "color": "#000000",
            "geometry": {"type": "LineString", "coordinates": []},
            "point_params": [],
            "bbox": None,
            "owner_email": "owner@example.com",
            "visibility": "shared",
            "settings": {"hidden": False},
            "shared_with_emails": ["a@example.com"],
            "is_owner": False,
        }
        redacted = redact_track_payload(payload, AccessRole.WORLD)
        assert redacted["name"] == "Track"
        assert "owner_email" not in redacted
        assert "visibility" not in redacted
        assert "settings" not in redacted
        assert "shared_with_emails" not in redacted
        assert "is_owner" not in redacted
        assert TrackPayloadMode.LIST.value == "list"

import pytest
from django.contrib.auth import get_user_model

from extensions.live_track.src.backend.models import LiveTrack, LiveTrackPoint
from extensions.live_track.src.backend.writer import point_writer
from geo_lib.track.store import PointStore
from test_utils.live_track_points import seed_track_points, stored_track_points

User = get_user_model()

TS_OLD = 1_700_000_001_000
TS_MID = 1_700_000_002_000
TS_NEW = 1_700_000_003_000


def _track(suffix: str):
    owner = User.objects.create_user(
        email=f"{suffix}@example.com",
        password="password",
        username=suffix,
    )
    return LiveTrack.objects.create(
        tracker_secret=f"secret-{suffix}",
        name=suffix,
        user=owner,
    )


@pytest.mark.django_db
class TestLiveTrackPointStore:
    def test_live_track_has_no_json_live_store_fields(self):
        field_names = {field.name for field in LiveTrack._meta.get_fields()}
        assert "geometry" not in field_names
        assert "point_params" not in field_names

    def test_persist_and_from_track_roundtrip(self):
        track = _track("roundtrip")
        seed_track_points(
            track,
            [[2.0, 1.0, TS_OLD], [4.0, 3.0, TS_MID]],
            [{"acc": 1}, {"acc": 2}],
        )
        coords, params = stored_track_points(track)
        assert coords == [[2.0, 1.0, TS_OLD], [4.0, 3.0, TS_MID]]
        assert params == [{"acc": 1}, {"acc": 2}]
        assert LiveTrackPoint.objects.filter(track=track).count() == 2

    def test_latest_is_max_timestamp_not_insert_tail(self):
        track = _track("latest-max")
        point_writer.append(track, 1.0, 2.0, TS_NEW, {"acc": 1})
        point_writer.append(track, 3.0, 4.0, TS_OLD, {"acc": 2})
        latest = PointStore.from_track(track).latest()
        assert latest is not None
        assert latest.timestamp_ms == TS_NEW
        assert latest.params["acc"] == 1

    def test_latest_tie_uses_later_insert(self):
        track = _track("latest-tie")
        point_writer.append(track, 1.0, 2.0, TS_OLD, {"acc": 1})
        point_writer.append(track, 3.0, 4.0, TS_OLD, {"acc": 2})
        latest = PointStore.from_track(track).latest()
        assert latest is not None
        assert latest.params["acc"] == 2

    def test_writer_append_does_not_use_json_fields(self):
        track = _track("writer-rows")
        inserted = point_writer.append(track, 1.0, 2.0, TS_MID, {"acc": 9})
        assert inserted is not None
        rows = list(LiveTrackPoint.objects.filter(track=track).order_by("seq"))
        assert len(rows) == 1
        assert rows[0].lon == 2.0
        assert rows[0].lat == 1.0
        assert rows[0].timestamp_ms == TS_MID
        assert rows[0].params["acc"] == 9

    def test_trim_to_latest_keeps_time_max_row(self):
        track = _track("trim")
        seed_track_points(
            track,
            [[2.0, 1.0, TS_NEW], [4.0, 3.0, TS_OLD]],
            [{"acc": 1}, {"acc": 2}],
        )
        locked = point_writer.clear_history(track)
        coords, params = stored_track_points(locked)
        assert coords == [[2.0, 1.0, TS_NEW]]
        assert params == [{"acc": 1}]
        assert LiveTrackPoint.objects.filter(track=track).count() == 1

    def test_wire_projection_is_aligned_coordinates_and_params(self):
        track = _track("wire")
        seed_track_points(
            track,
            [[2.0, 1.0, TS_OLD], [6.0, 5.0, TS_NEW]],
            [{"acc": 1}, {"acc": 3}],
        )
        store = PointStore.from_track(track)
        assert store.as_geometry()["coordinates"] == [[2.0, 1.0, TS_OLD], [6.0, 5.0, TS_NEW]]
        assert store.wire_params() == [{"acc": 1}, {"acc": 3}]

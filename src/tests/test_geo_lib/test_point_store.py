from geo_lib.track.store import PointStore
from geo_lib.track.window import normalize_recent_data_window


TS_NEW = 1_700_000_003_000
TS_OLD = 1_700_000_001_000
TS_MID = 1_700_000_002_000


class TestPointStore:
    def test_append_keeps_coords_and_params_aligned(self):
        store = PointStore([], [])
        first = store.append(2.0, 1.0, TS_OLD, {"acc": 1})
        second = store.append(4.0, 3.0, TS_MID, {"acc": 2})
        assert first is not None and second is not None
        assert len(store.coordinates) == len(store.params) == 2
        assert store.params[0]["acc"] == 1
        assert store.params[1]["acc"] == 2

    def test_latest_is_max_timestamp_not_array_tail(self):
        store = PointStore(
            [[2.0, 1.0, TS_NEW], [4.0, 3.0, TS_OLD]],
            [{"acc": 1}, {"acc": 2}],
        )
        latest = store.latest()
        assert latest is not None
        assert latest.timestamp_ms == TS_NEW
        assert latest.params["acc"] == 1

    def test_latest_tie_uses_later_insert(self):
        store = PointStore(
            [[2.0, 1.0, TS_OLD], [4.0, 3.0, TS_OLD]],
            [{"acc": 1}, {"acc": 2}],
        )
        latest = store.latest()
        assert latest is not None
        assert latest.params["acc"] == 2

    def test_append_many_dedupes_rounded_identity(self):
        store = PointStore([[1.123454, 2.123454, TS_OLD]], [{"acc": 1}])
        inserted = store.append_many(
            [
                {"lon": 1.123446, "lat": 2.123446, "timestamp": TS_OLD, "acc": 9},
                {"lon": 5.0, "lat": 6.0, "timestamp": TS_MID, "acc": 3},
            ]
        )
        assert len(inserted) == 1
        assert inserted[0].props["acc"] == 3

    def test_trim_to_latest_uses_time_max(self):
        store = PointStore(
            [[2.0, 1.0, TS_NEW], [4.0, 3.0, TS_OLD]],
            [{"acc": 1}, {"acc": 2}],
        )
        store.trim_to_latest()
        assert store.coordinates == [[2.0, 1.0, TS_NEW]]
        assert store.params == [{"acc": 1}]

    def test_missing_timestamp_does_not_raise_on_append(self):
        store = PointStore([[2.0, 1.0]], [{}])
        inserted = store.append(4.0, 3.0, TS_MID, {"acc": 2})
        assert inserted is not None
        assert len(store.coordinates) == 2


class TestRecentWindowNormalize:
    def test_empty_string_is_invalid(self):
        try:
            normalize_recent_data_window("")
        except ValueError as exc:
            assert "all" in str(exc)
        else:
            raise AssertionError("expected ValueError")

    def test_all_is_valid(self):
        assert normalize_recent_data_window("all") == "all"
        assert normalize_recent_data_window(None) == "all"

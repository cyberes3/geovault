from extensions.live_track.src.backend import helpers
from extensions.live_track.src.backend.helpers import (
    _filter_coords_by_recent_window,
    latest_coord_by_time,
)


class TestLatestCoordByTime:
    def test_newer_timestamp_wins_when_not_last_in_array(self):
        coords = [
            [1.0, 2.0, 2_000],
            [9.0, 9.0, 1_000],
        ]
        assert latest_coord_by_time(coords) == [1.0, 2.0, 2_000]

    def test_missing_timestamps_use_later_array_order(self):
        coords = [[1.0, 2.0], [3.0, 4.0]]
        assert latest_coord_by_time(coords) == [3.0, 4.0]

    def test_dated_coord_beats_undated_later_coord(self):
        coords = [
            [1.0, 2.0, 1_700_000_000],
            [9.0, 9.0],
        ]
        assert latest_coord_by_time(coords) == [1.0, 2.0, 1_700_000_000]

    def test_empty_returns_none(self):
        assert latest_coord_by_time([]) is None


class TestRecentWindowLatestFallback:
    def test_fallback_uses_time_max_not_array_tail(self, monkeypatch):
        monkeypatch.setattr(helpers.time, "time", lambda: 1_700_000_100)
        coords = [
            [1.0, 2.0, 1_700_000_000_000],
            [9.0, 9.0, 1_699_000_000_000],
        ]
        params = [{"acc": 1}, {"acc": 2}]
        kept, kept_params = _filter_coords_by_recent_window(coords, params, "1min")
        assert kept == [coords[0]]
        assert kept_params == [params[0]]

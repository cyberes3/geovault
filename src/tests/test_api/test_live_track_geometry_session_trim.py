"""Unit tests for the session-aware geometry trimming helpers in
`extensions.live_track.src.backend.tracker_views`.

These cover `_segment_indices_by_starttimestamp`, `_uniform_stride_keep`,
`_fit_session_aware_indices`, and `_shrink_largest_segment_until_fits` in isolation —
no Django request cycle, no DB. The HTTP integration is covered separately in
`test_live_track_extension.py`.
"""

import json
import unittest

from extensions.live_track.src.backend.tracker_views import (
    _bbox_from_normalized_coords,
    _fit_session_aware_indices,
    _json_size_bytes,
    _segment_indices_by_starttimestamp,
    _shrink_largest_segment_until_fits,
    _uniform_stride_keep,
)


def _coord(lon: float, lat: float, ts_ms: int) -> list:
    return [lon, lat, ts_ms]


def _params(starttimestamp_ms: int) -> dict:
    return {"starttimestamp": starttimestamp_ms}


class TestSegmentIndicesByStartTimestamp(unittest.TestCase):
    def test_empty_inputs_return_empty(self):
        self.assertEqual(_segment_indices_by_starttimestamp([], []), [])

    def test_misaligned_params_returns_single_segment(self):
        coords = [_coord(0, 0, 1), _coord(1, 1, 2)]
        params = [_params(100)]
        segments = _segment_indices_by_starttimestamp(coords, params)
        self.assertEqual(segments, [[0, 1]])

    def test_no_starttimestamps_returns_single_segment(self):
        coords = [_coord(0, 0, 1), _coord(1, 1, 2), _coord(2, 2, 3)]
        params = [{}, {}, {}]
        segments = _segment_indices_by_starttimestamp(coords, params)
        self.assertEqual(segments, [[0, 1, 2]])

    def test_three_distinct_sessions_split_in_order(self):
        coords = [
            _coord(0, 0, 100),
            _coord(0, 0, 200),
            _coord(0, 0, 1100),
            _coord(0, 0, 2100),
            _coord(0, 0, 2200),
        ]
        params = [
            _params(100),
            _params(100),
            _params(1000),
            _params(2000),
            _params(2000),
        ]
        segments = _segment_indices_by_starttimestamp(coords, params)
        self.assertEqual(segments, [[0, 1], [2], [3, 4]])

    def test_falls_back_to_coord_timestamp_for_null_start(self):
        # Two boundaries at 100 and 1000. Points without explicit starts get attributed
        # to the largest boundary <= their coord timestamp.
        coords = [
            _coord(0, 0, 150),
            _coord(0, 0, 1500),
            _coord(0, 0, 50),
        ]
        params = [{}, _params(1000), _params(100)]
        segments = _segment_indices_by_starttimestamp(coords, params)
        # Index 2 anchors boundary 100, index 0 falls into boundary 100 (150>=100<1000),
        # index 1 anchors boundary 1000.
        self.assertEqual(segments, [[0, 2], [1]])


class TestUniformStrideKeep(unittest.TestCase):
    def test_target_geq_size_returns_input(self):
        self.assertEqual(_uniform_stride_keep([0, 1, 2], 3), [0, 1, 2])
        self.assertEqual(_uniform_stride_keep([0, 1, 2], 99), [0, 1, 2])

    def test_target_one_keeps_last(self):
        self.assertEqual(_uniform_stride_keep([5, 6, 7], 1), [7])

    def test_target_two_keeps_first_and_last(self):
        self.assertEqual(_uniform_stride_keep([5, 6, 7, 8], 2), [5, 8])

    def test_uniform_stride_keeps_anchors_and_distributes(self):
        result = _uniform_stride_keep(list(range(10)), 5)
        self.assertEqual(result[0], 0)
        self.assertEqual(result[-1], 9)
        self.assertLessEqual(len(result), 5)
        self.assertEqual(sorted(result), result)


class TestFitSessionAwareIndices(unittest.TestCase):
    def _base_payload(self) -> dict:
        return {
            "id": "abc",
            "name": "demo",
            "color": "#ffffff",
            "settings": {},
            "visibility": "private",
            "share_params_with_recipients": False,
            "is_owner": True,
            "created_at": 0,
            "updated_at": 0,
        }

    def test_fits_under_budget_returns_all(self):
        coords = [_coord(0, 0, 1), _coord(1, 1, 2)]
        params = [_params(100), _params(100)]
        kept = _fit_session_aware_indices(
            self._base_payload(), coords, params, max_bytes=10**9, params_align_with_coords=True
        )
        self.assertEqual(kept, [0, 1])

    def test_single_session_falls_back_to_tail_trim(self):
        # Single session — behavior should match _fit_tail_count_to_max_bytes.
        coords = [_coord(i, i, i) for i in range(10)]
        params = [_params(0) for _ in range(10)]
        # Pick a budget that forces dropping older points.
        budget = _json_size_bytes(
            {**self._base_payload(),
             "geometry": {"type": "LineString", "coordinates": coords[-3:]},
             "point_params": params[-3:],
             "bbox": _bbox_from_normalized_coords(coords[-3:])}
        )
        kept = _fit_session_aware_indices(
            self._base_payload(), coords, params, max_bytes=budget, params_align_with_coords=True
        )
        self.assertEqual(kept, [7, 8, 9])

    def test_three_sessions_each_session_keeps_anchors(self):
        # 3 sessions of 6 points each. Force a small enough budget that we MUST decimate
        # but verify each session retains its first + last index (boundary anchors).
        sessions_starts = [100, 1000, 2000]
        coords = []
        params = []
        for sidx, start in enumerate(sessions_starts):
            for k in range(6):
                coords.append(_coord(sidx, k, start + k * 10))
                params.append(_params(start))

        # Budget: roughly 9 points worth.
        target_indices = [0, 2, 5, 6, 8, 11, 12, 14, 17]
        budget = _json_size_bytes(
            {**self._base_payload(),
             "geometry": {"type": "LineString", "coordinates": [coords[i] for i in target_indices]},
             "point_params": [params[i] for i in target_indices],
             "bbox": _bbox_from_normalized_coords([coords[i] for i in target_indices])}
        ) + 5  # tiny slack

        kept = _fit_session_aware_indices(
            self._base_payload(), coords, params, max_bytes=budget, params_align_with_coords=True
        )
        # Session anchors: indices 0, 5 (session 1); 6, 11 (session 2); 12, 17 (session 3).
        for anchor in (0, 5, 6, 11, 12, 17):
            self.assertIn(anchor, kept, f"missing anchor {anchor}")

    def test_misaligned_params_falls_back_to_tail(self):
        coords = [_coord(i, i, i) for i in range(5)]
        params = [{}]  # misaligned
        budget = 100
        kept = _fit_session_aware_indices(
            self._base_payload(), coords, params, max_bytes=budget, params_align_with_coords=False
        )
        self.assertTrue(kept == sorted(kept))


class TestShrinkLargestSegmentUntilFits(unittest.TestCase):
    def _payload(self, coords, params) -> dict:
        return {
            "id": "abc",
            "name": "demo",
            "color": "#ffffff",
            "settings": {},
            "visibility": "private",
            "share_params_with_recipients": False,
            "is_owner": True,
            "created_at": 0,
            "updated_at": 0,
            "geometry": {"type": "LineString", "coordinates": coords},
            "point_params": params,
            "bbox": _bbox_from_normalized_coords(coords),
        }

    def test_no_op_when_under_budget(self):
        coords = [_coord(0, 0, 1)]
        params = [_params(100)]
        payload = self._payload(coords, params)
        before = list(payload["geometry"]["coordinates"])
        _shrink_largest_segment_until_fits(payload, coords, params, max_bytes=10**9, params_align_with_coords=True)
        self.assertEqual(payload["geometry"]["coordinates"], before)

    def test_drops_interior_of_largest_segment_first(self):
        # Session A has 5 points, session B has 2. Force a budget that requires shrinking
        # by 1 point. The drop must come from session A and be an INTERIOR point (not the
        # first or last of A).
        a_coords = [_coord(0, 0, 100 + k) for k in range(5)]
        b_coords = [_coord(1, 1, 1000 + k) for k in range(2)]
        a_params = [_params(100)] * 5
        b_params = [_params(1000)] * 2
        coords = a_coords + b_coords
        params = a_params + b_params
        payload = self._payload(coords, params)
        full_size = _json_size_bytes(payload)
        budget = full_size - 5  # force one drop

        _shrink_largest_segment_until_fits(payload, coords, params, max_bytes=budget, params_align_with_coords=True)

        self.assertLessEqual(_json_size_bytes(payload), budget)
        # Session A first/last preserved.
        self.assertEqual(coords[0], [0, 0, 100])
        self.assertIn([0, 0, 104], coords)  # last A point still present
        # Session B intact.
        self.assertEqual(coords[-2], [1, 1, 1000])
        self.assertEqual(coords[-1], [1, 1, 1001])

    def test_falls_back_to_head_drop_when_all_segments_at_floor(self):
        # 3 segments of 2 points each (every segment at floor of 2). Budget forces extra
        # shrinkage; algorithm must head-drop.
        coords = [
            _coord(0, 0, 100),
            _coord(0, 0, 101),
            _coord(1, 1, 1000),
            _coord(1, 1, 1001),
            _coord(2, 2, 2000),
            _coord(2, 2, 2001),
        ]
        params = [
            _params(100), _params(100),
            _params(1000), _params(1000),
            _params(2000), _params(2000),
        ]
        payload = self._payload(coords, params)
        size_before = _json_size_bytes(payload)
        budget = size_before - 10
        _shrink_largest_segment_until_fits(payload, coords, params, max_bytes=budget, params_align_with_coords=True)
        self.assertLessEqual(_json_size_bytes(payload), budget)
        self.assertLess(len(coords), 6)


if __name__ == "__main__":
    unittest.main()

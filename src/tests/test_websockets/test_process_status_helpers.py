"""
Tests for the pure helper modules extracted from ProcessStatusModule:
`process_status_pagination` (spatial sort + pagination) and
`process_status_duplicates` (hash/geometry duplicate mapping for the import
review UI).

These are plain unit tests against pure functions -- no DB, no WebSocket
connection -- since the modules under test take already-fetched plain data
and return plain data, independent of Django/channels.
"""
from types import SimpleNamespace

from django.test import SimpleTestCase

from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource
from geo_lib.websocket.modules.process_status_duplicates import (
    build_geometry_duplicate_maps,
    build_hash_duplicate_maps,
    build_queue_hash_to_item,
)
from geo_lib.websocket.modules.process_status_pagination import (
    build_other_queue_sorted_indices,
    build_pagination_metadata,
    normalize_page_bounds,
    sort_features_spatially,
)


def _point_feature(lon: float, lat: float, name: str = "", geojson_hash: str = None) -> dict:
    properties = {'name': name}
    if geojson_hash:
        properties['geojson_hash'] = geojson_hash
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [lon, lat]},
        'properties': properties,
    }


class TestSortFeaturesSpatially(SimpleTestCase):
    def test_sorts_north_to_south_west_to_east(self):
        # South point first in input order, north point second -- north should sort first.
        south = _point_feature(0, 10, 'south')
        north = _point_feature(0, 20, 'north')

        sorted_features, original_to_new_index = sort_features_spatially([south, north])

        self.assertEqual([f['properties']['name'] for f in sorted_features], ['north', 'south'])
        # Original index 0 (south) maps to new index 1, original index 1 (north) maps to new index 0
        self.assertEqual(original_to_new_index, {0: 1, 1: 0})

    def test_ties_broken_west_to_east(self):
        east = _point_feature(10, 0, 'east')
        west = _point_feature(-10, 0, 'west')

        sorted_features, _ = sort_features_spatially([east, west])

        self.assertEqual([f['properties']['name'] for f in sorted_features], ['west', 'east'])

    def test_empty_list(self):
        sorted_features, original_to_new_index = sort_features_spatially([])
        self.assertEqual(sorted_features, [])
        self.assertEqual(original_to_new_index, {})


class TestBuildOtherQueueSortedIndices(SimpleTestCase):
    def test_skips_items_with_no_geofeatures(self):
        queue_item = SimpleNamespace(id=1, geofeatures=[])
        result = build_other_queue_sorted_indices([queue_item])
        self.assertEqual(result, {})

    def test_builds_sorted_index_mapping_per_item(self):
        south = _point_feature(0, 10)
        north = _point_feature(0, 20)
        queue_item = SimpleNamespace(id=42, geofeatures=[south, north])

        result = build_other_queue_sorted_indices([queue_item])

        self.assertEqual(result, {42: {0: 1, 1: 0}})


class TestNormalizePageBounds(SimpleTestCase):
    def test_first_page(self):
        page, page_size, start_idx, end_idx = normalize_page_bounds(1)
        self.assertEqual((page, page_size, start_idx, end_idx), (1, 50, 0, 50))

    def test_second_page(self):
        page, page_size, start_idx, end_idx = normalize_page_bounds(2)
        self.assertEqual((page, page_size, start_idx, end_idx), (2, 50, 50, 100))

    def test_clamps_page_below_one(self):
        page, page_size, start_idx, end_idx = normalize_page_bounds(0)
        self.assertEqual((page, page_size, start_idx, end_idx), (1, 50, 0, 50))


class TestBuildPaginationMetadata(SimpleTestCase):
    def test_has_next_and_previous(self):
        metadata = build_pagination_metadata(page=2, page_size=50, total_features=120, end_idx=100)
        self.assertEqual(metadata, {
            'page': 2,
            'page_size': 50,
            'total_features': 120,
            'total_pages': 3,
            'has_next': True,
            'has_previous': True,
        })

    def test_last_page_has_no_next(self):
        metadata = build_pagination_metadata(page=3, page_size=50, total_features=120, end_idx=150)
        self.assertFalse(metadata['has_next'])


class TestBuildQueueHashToItem(SimpleTestCase):
    def test_maps_first_occurrence_of_each_hash(self):
        feature_a = _point_feature(0, 0, 'a', geojson_hash='hash-a')
        feature_b = _point_feature(1, 1, 'b', geojson_hash='hash-b')
        queue_item = SimpleNamespace(id=7, original_filename='other.kml', geofeatures=[feature_a, feature_b])

        result = build_queue_hash_to_item([queue_item])

        self.assertEqual(result['hash-a'], {'queue_item_id': 7, 'queue_item_filename': 'other.kml', 'feature_index': 0})
        self.assertEqual(result['hash-b'], {'queue_item_id': 7, 'queue_item_filename': 'other.kml', 'feature_index': 1})

    def test_first_queue_item_wins_on_duplicate_hash(self):
        feature = _point_feature(0, 0, 'dup', geojson_hash='shared-hash')
        first_item = SimpleNamespace(id=1, original_filename='first.kml', geofeatures=[feature])
        second_item = SimpleNamespace(id=2, original_filename='second.kml', geofeatures=[feature])

        result = build_queue_hash_to_item([first_item, second_item])

        self.assertEqual(result['shared-hash']['queue_item_id'], 1)


class TestBuildHashDuplicateMaps(SimpleTestCase):
    def test_feature_store_duplicate_takes_precedence_over_cross_queue(self):
        feature = _point_feature(0, 0, 'dup', geojson_hash='shared-hash')
        geofeatures = [feature]
        original_to_new_index = {0: 0}

        fs_dups, cq_dups, all_hashes = build_hash_duplicate_maps(
            geofeatures, original_to_new_index, start_idx=0, end_idx=50,
            existing_store_hashes={'shared-hash'}, hash_to_store_id={'shared-hash': 99},
            queue_hash_to_item={'shared-hash': {'queue_item_id': 1, 'queue_item_filename': 'x.kml', 'feature_index': 0}},
            queue_item_sorted_indices={},
        )

        self.assertEqual(len(fs_dups), 1)
        self.assertEqual(fs_dups[0]['feature_store_id'], 99)
        self.assertEqual(cq_dups, [])
        self.assertEqual(all_hashes, {'shared-hash'})

    def test_cross_queue_duplicate_when_not_in_feature_store(self):
        feature = _point_feature(0, 0, 'dup', geojson_hash='queue-hash')
        geofeatures = [feature]
        original_to_new_index = {0: 0}

        fs_dups, cq_dups, all_hashes = build_hash_duplicate_maps(
            geofeatures, original_to_new_index, start_idx=0, end_idx=50,
            existing_store_hashes=set(), hash_to_store_id={},
            queue_hash_to_item={'queue-hash': {'queue_item_id': 5, 'queue_item_filename': 'other.kml', 'feature_index': 3}},
            queue_item_sorted_indices={5: {3: 7}},
        )

        self.assertEqual(fs_dups, [])
        self.assertEqual(len(cq_dups), 1)
        self.assertEqual(cq_dups[0]['global_index'], 7)
        self.assertEqual(cq_dups[0]['queue_item_id'], 5)
        self.assertEqual(all_hashes, {'queue-hash'})

    def test_features_outside_page_bounds_excluded_from_lists_but_tracked_in_all_hashes(self):
        feature = _point_feature(0, 0, 'dup', geojson_hash='shared-hash')
        geofeatures = [feature]
        # new_idx (0) is outside the [start_idx, end_idx) page window
        original_to_new_index = {0: 0}

        fs_dups, cq_dups, all_hashes = build_hash_duplicate_maps(
            geofeatures, original_to_new_index, start_idx=50, end_idx=100,
            existing_store_hashes={'shared-hash'}, hash_to_store_id={},
            queue_hash_to_item={}, queue_item_sorted_indices={},
        )

        self.assertEqual(fs_dups, [])
        self.assertEqual(all_hashes, {'shared-hash'})

    def test_no_duplicate_when_hash_not_found_anywhere(self):
        feature = _point_feature(0, 0, 'unique', geojson_hash='unique-hash')
        fs_dups, cq_dups, all_hashes = build_hash_duplicate_maps(
            [feature], {0: 0}, start_idx=0, end_idx=50,
            existing_store_hashes=set(), hash_to_store_id={},
            queue_hash_to_item={}, queue_item_sorted_indices={},
        )
        self.assertEqual((fs_dups, cq_dups, all_hashes), ([], [], set()))


class TestBuildGeometryDuplicateMaps(SimpleTestCase):
    def test_feature_store_geometry_duplicate(self):
        feature = _point_feature(0, 0, 'geom-dup', geojson_hash='geom-hash')
        geofeatures = [feature]
        original_to_new_index = {0: 0}
        duplicate_features_list = [{
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.GEOMETRY,
            'feature': feature,
            'existing_features': [{'id': 55}],
        }]

        fs_geom, cq_geom, skipped_hashes = build_geometry_duplicate_maps(
            duplicate_features_list, geofeatures, original_to_new_index, start_idx=0, end_idx=50,
            all_hash_duplicate_hashes=set(), queue_item_sorted_indices={},
        )

        self.assertEqual(len(fs_geom), 1)
        self.assertEqual(fs_geom[0]['feature_store_id'], 55)
        self.assertEqual(cq_geom, [])
        self.assertEqual(skipped_hashes, ['geom-hash'])

    def test_cross_queue_geometry_duplicate_uses_sorted_index(self):
        feature = _point_feature(0, 0, 'geom-dup', geojson_hash='geom-hash')
        geofeatures = [feature]
        original_to_new_index = {0: 0}
        duplicate_features_list = [{
            'source': DuplicateSource.CROSS_QUEUE,
            'match_type': DuplicateMatchType.GEOMETRY,
            'feature': feature,
            'existing_features': [{'id': 9, 'name': 'other.kml', 'feature_index': 2}],
        }]

        fs_geom, cq_geom, _ = build_geometry_duplicate_maps(
            duplicate_features_list, geofeatures, original_to_new_index, start_idx=0, end_idx=50,
            all_hash_duplicate_hashes=set(), queue_item_sorted_indices={9: {2: 6}},
        )

        self.assertEqual(fs_geom, [])
        self.assertEqual(len(cq_geom), 1)
        self.assertEqual(cq_geom[0]['queue_item_id'], 9)
        self.assertEqual(cq_geom[0]['queue_item_filename'], 'other.kml')
        self.assertEqual(cq_geom[0]['global_index'], 6)

    def test_hash_duplicates_are_skipped_to_avoid_double_counting(self):
        feature = _point_feature(0, 0, 'already-hash-dup', geojson_hash='already-flagged')
        duplicate_features_list = [{
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.GEOMETRY,
            'feature': feature,
            'existing_features': [],
        }]

        fs_geom, cq_geom, skipped_hashes = build_geometry_duplicate_maps(
            duplicate_features_list, [feature], {0: 0}, start_idx=0, end_idx=50,
            all_hash_duplicate_hashes={'already-flagged'}, queue_item_sorted_indices={},
        )

        self.assertEqual((fs_geom, cq_geom, skipped_hashes), ([], [], []))

    def test_non_geometry_match_type_is_ignored(self):
        feature = _point_feature(0, 0, 'hash-type', geojson_hash='some-hash')
        duplicate_features_list = [{
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.HASH,
            'feature': feature,
            'existing_features': [],
        }]

        fs_geom, cq_geom, skipped_hashes = build_geometry_duplicate_maps(
            duplicate_features_list, [feature], {0: 0}, start_idx=0, end_idx=50,
            all_hash_duplicate_hashes=set(), queue_item_sorted_indices={},
        )

        self.assertEqual((fs_geom, cq_geom, skipped_hashes), ([], [], []))

    def test_missing_fields_are_skipped(self):
        duplicate_features_list = [{'source': None, 'match_type': None, 'feature': None}]

        fs_geom, cq_geom, skipped_hashes = build_geometry_duplicate_maps(
            duplicate_features_list, [], {}, start_idx=0, end_idx=50,
            all_hash_duplicate_hashes=set(), queue_item_sorted_indices={},
        )

        self.assertEqual((fs_geom, cq_geom, skipped_hashes), ([], [], []))

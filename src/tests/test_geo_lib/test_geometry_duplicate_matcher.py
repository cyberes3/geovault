"""
Unit and integration tests for GeometryDuplicateMatcher.
"""
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase

from api.models import FeatureStore, ImportQueue
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection.geometry_matcher import (
    GeometryDuplicateContext,
    GeometryDuplicateMatcher,
)
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource

User = get_user_model()


def _point_feature(lon: float, lat: float, name: str = 'Test') -> dict:
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [lon, lat]},
        'properties': {'name': name},
    }


class TestGeometryDuplicateMatcher(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            email='matcher@example.com',
            password='testpass123',
            username='matcheruser',
        )

    def test_resolve_with_empty_library_matches_still_checks_cross_queue(self):
        """library_matches=[] skips feature-store query but still matches older queue items."""
        older_feature = _point_feature(-105.64053, 38.79543, 'Older')
        newer_feature = _point_feature(
            -105.64053344726562,
            38.79542922973633,
            'Newer',
        )

        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.gpx',
            raw_file='<gpx></gpx>',
            geofeatures=[older_feature],
            imported=False,
        )
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.gpx',
            raw_file='<gpx></gpx>',
            geofeatures=[],
            imported=False,
        )

        context = GeometryDuplicateContext(
            user_id=self.user.id,
            source_filter=None,
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp,
        )
        matcher = GeometryDuplicateMatcher(context)

        duplicate_info = matcher.resolve(newer_feature, library_matches=[])

        self.assertIsNotNone(duplicate_info)
        self.assertEqual(duplicate_info['source'], DuplicateSource.CROSS_QUEUE)
        self.assertEqual(duplicate_info['match_type'], DuplicateMatchType.GEOMETRY)
        self.assertEqual(duplicate_info['existing_features'][0]['id'], older_queue.id)

    def test_resolve_with_empty_library_matches_does_not_consult_feature_store(self):
        """When library is skipped via [], an existing library feature is not a duplicate."""
        stored = _point_feature(-122.4194, 37.7749, 'On map')
        import_feature = _point_feature(-122.4194, 37.7749, 'Import only')

        stored_hash = generate_geojson_hash(stored)
        stored['properties']['geojson_hash'] = stored_hash
        coords = stored['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=stored,
            geojson_hash=stored_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326),
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='solo.gpx',
            raw_file='<gpx></gpx>',
            geofeatures=[import_feature],
            imported=False,
        )

        context = GeometryDuplicateContext(
            user_id=self.user.id,
            source_filter=None,
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp,
        )
        matcher = GeometryDuplicateMatcher(context)

        duplicate_info = matcher.resolve(import_feature, library_matches=[])

        self.assertIsNone(duplicate_info)

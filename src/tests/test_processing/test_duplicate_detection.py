"""
Duplicate detection against the closed kernel.

Covers each of the 4 duplicate kinds and their priority:
1. Library hash
2. Library geometry
3. Draft-queue hash
4. Draft-queue geometry

Priority:
- Hash > Geometry (within the same scope)
- Library > Draft queue (across scopes)
"""
from datetime import timedelta

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import LineString, Point
from django.test import TestCase, TransactionTestCase
from django.utils import timezone

from api.models import FeatureStore, ImportQueue
from geo_lib.duplicates.adapters.import_draft import build_duplicate_index
from geo_lib.duplicates.constants import COORDINATE_TOLERANCE
from geo_lib.duplicates.detector import DuplicateDetector
from geo_lib.duplicates.identity import GeoJsonHash
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash
from tests.test_utils.import_queue import create_draft, queue_with_drafts


User = get_user_model()


def detect(features, user_id, exclude_queue_id=0, uploaded_at=None):
    uploaded_at = uploaded_at or timezone.now()
    index = build_duplicate_index(user_id, features, exclude_queue_id or 0, uploaded_at)
    return DuplicateDetector().detect_two_pass(features, index)


def _digest(feature):
    return GeoJsonHash.of(feature).value


def partition(features, verdicts):
    remaining = []
    duplicates = []
    for feature in features:
        verdict = verdicts[_digest(feature)]
        if verdict.kind == VerdictKind.NONE:
            remaining.append(feature)
        else:
            duplicates.append((feature, verdict))
    return remaining, duplicates


def existing_id(verdict: DuplicateVerdict):
    match = verdict.matches[0]
    if match.feature_store_id is not None:
        return match.feature_store_id
    return match.draft_queue_id


def _linestring_geometry_3d(coordinates: list) -> LineString:
    """FeatureStore.geometry is 3D; build a matching LineString for tests."""
    return LineString(*[(c[0], c[1], 0) for c in coordinates], srid=4326)


def _point_feature(lon: float, lat: float, name: str = 'Test') -> dict:
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [lon, lat]},
        'properties': {'name': name},
    }


class TestDuplicateDetectionIndividual(TestCase):
    """Test each duplicate type individually."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

        self.point_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'},
        }

        self.different_point = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5194, 37.8749]},
            'properties': {'name': 'Different Point', 'description': 'Another point'},
        }

        self.same_coords_different_props = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Different Name', 'description': 'Different description'},
        }

    def test_feature_store_hash_duplicate_only(self):
        """Test 1: Feature store hash duplicate detection."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        store_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash,
        )

        features = [self.point_feature]
        remaining, duplicates = partition(features, detect(features, self.user.id))

        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.LIBRARY)
        self.assertEqual(verdict.kind, VerdictKind.HASH)
        self.assertEqual(existing_id(verdict), store_feature.id)

    def test_feature_store_geometry_duplicate_only(self):
        """Test 2: Feature store geometry duplicate detection."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        coords = self.point_feature['geometry']['coordinates']
        point_geom = Point(coords[0], coords[1], 0, srid=4326)

        store_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash,
            geometry=point_geom,
        )

        features = [self.same_coords_different_props]
        remaining, duplicates = partition(features, detect(features, self.user.id))

        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.LIBRARY)
        self.assertEqual(verdict.kind, VerdictKind.GEOMETRY)
        self.assertEqual(existing_id(verdict), store_feature.id)

    def test_feature_store_geometry_duplicate_2d_vs_3d(self):
        """2D vs 3D geojson with same lon/lat is a geometry duplicate in the library."""
        stored_point = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.64053, 38.79543]},
            'properties': {'name': 'Trailhead', 'description': '2D'},
        }
        import_point = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.64053, 38.79543, 2100.0]},
            'properties': {'name': 'Trailhead', 'description': '3D with elevation'},
        }

        stored_hash = generate_geojson_hash(stored_point)
        stored_point['properties']['geojson_hash'] = stored_hash
        coords = stored_point['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=stored_point,
            geojson_hash=stored_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326),
        )

        remaining, duplicates = partition([import_point], detect([import_point], self.user.id))

        self.assertEqual(len(remaining), 0)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.GEOMETRY)

    def test_cross_queue_hash_duplicate_only(self):
        """Test 3: Cross-queue hash duplicate detection."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[self.point_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        features = [self.point_feature]
        remaining, duplicates = partition(
            features,
            detect(features, self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.DRAFT_QUEUE)
        self.assertEqual(verdict.kind, VerdictKind.HASH)
        self.assertEqual(existing_id(verdict), older_queue.id)

    def test_cross_queue_geometry_duplicate_only(self):
        """Test 4: Cross-queue geometry duplicate detection."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[self.point_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        features = [self.same_coords_different_props]
        remaining, duplicates = partition(
            features,
            detect(features, self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.DRAFT_QUEUE)
        self.assertEqual(verdict.kind, VerdictKind.GEOMETRY)
        self.assertEqual(existing_id(verdict), older_queue.id)

    def test_cross_queue_geometry_duplicate_with_precision(self):
        """Cross-queue geometry duplicates use geometries_match tolerance, not exact JSON keys."""
        feature_low_precision = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.64053, 38.79543]},
            'properties': {'name': "Dick's Peak", 'description': 'Older queue file'},
        }
        feature_high_precision = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.64053344726562, 38.79542922973633]},
            'properties': {'name': "Dick's Peak", 'description': 'Newer queue file'},
        }

        feature_hash = generate_geojson_hash(feature_low_precision)
        feature_low_precision['properties']['geojson_hash'] = feature_hash

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.gpx',
            raw_file='<gpx></gpx>',
            features=[feature_low_precision],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.gpx',
            raw_file='<gpx></gpx>',
            imported=False,
        )

        remaining, duplicates = partition(
            [feature_high_precision],
            detect([feature_high_precision], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(remaining), 0)
        self.assertEqual(len(duplicates), 1)
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.DRAFT_QUEUE)
        self.assertEqual(verdict.kind, VerdictKind.GEOMETRY)
        self.assertEqual(existing_id(verdict), older_queue.id)

    def test_coordinate_precision_edge_case(self):
        """
        Features with the same name and slightly different coordinate precision
        should still be detected as geometry duplicates.

        Uses public test coordinates:
        - File 1: 38.79543, -105.64053 (5 decimal places)
        - File 2: 38.79542922973633, -105.64053344726562 (14 decimal places)

        These are about 0.28 meters apart, within COORDINATE_TOLERANCE of 5e-6 degrees
        (≈0.5 meters).
        """
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.64053, 38.79543]},
            'properties': {'name': "Dick's Peak", 'description': 'First file'},
        }

        feature1_hash = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = feature1_hash

        coords1 = feature1['geometry']['coordinates']
        point_geom1 = Point(coords1[0], coords1[1], 0, srid=4326)

        store_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1,
            geojson_hash=feature1_hash,
            geometry=point_geom1,
        )

        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.64053344726562, 38.79542922973633]},
            'properties': {'name': "Dick's Peak", 'description': 'Second file'},
        }

        remaining, duplicates = partition([feature2], detect([feature2], self.user.id))

        self.assertEqual(
            len(remaining),
            0,
            "Feature with slightly different coordinate precision should be detected as duplicate",
        )
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.LIBRARY)
        self.assertEqual(
            verdict.kind,
            VerdictKind.GEOMETRY,
            "Should be detected as geometry duplicate (not hash, since properties differ)",
        )
        self.assertEqual(existing_id(verdict), store_feature.id)

        coords1_normalized = feature1['geometry']['coordinates']
        coords2_normalized = feature2['geometry']['coordinates']
        self.assertNotEqual(
            coords1_normalized,
            coords2_normalized,
            "Coordinates should be different to test the edge case",
        )

        lat_diff = abs(coords1_normalized[1] - coords2_normalized[1])
        lon_diff = abs(coords1_normalized[0] - coords2_normalized[0])
        lat_meters = lat_diff * 111000
        lon_meters = lon_diff * 111000 * 0.707
        distance_meters = (lat_meters**2 + lon_meters**2)**0.5

        epsilon = 1e-9
        self.assertLess(
            lat_diff,
            COORDINATE_TOLERANCE + epsilon,
            f"Latitude difference ({lat_diff:.2e} degrees, {lat_meters:.3f}m) should be within tolerance ({COORDINATE_TOLERANCE})",
        )
        self.assertLess(
            lon_diff,
            COORDINATE_TOLERANCE + epsilon,
            f"Longitude difference ({lon_diff:.2e} degrees, {lon_meters:.3f}m) should be within tolerance ({COORDINATE_TOLERANCE})",
        )
        self.assertLess(
            distance_meters,
            0.5,
            f"Total distance ({distance_meters:.3f}m) should be less than 0.5 meters",
        )

    def test_overlapping_linestrings_not_geometry_duplicate(self):
        """Repeat hikes on the same trail must not match when paths differ."""
        shared_segment = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
            [-105.64070, 38.79560],
        ]
        stored_line = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': shared_segment},
            'properties': {'name': 'zookeeper 07202025', 'description': 'First hike'},
        }
        longer_line = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': shared_segment + [[-105.64100, 38.79600], [-105.64200, 38.79700]],
            },
            'properties': {'name': 'zookeeper 052526', 'description': 'Second hike'},
        }

        stored_hash = generate_geojson_hash(stored_line)
        stored_line['properties']['geojson_hash'] = stored_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=stored_line,
            geojson_hash=stored_hash,
            geometry=_linestring_geometry_3d(shared_segment),
        )

        remaining, duplicates = partition([longer_line], detect([longer_line], self.user.id))

        self.assertEqual(len(remaining), 1, "Different paths should not be geometry duplicates")
        self.assertEqual(len(duplicates), 0)

    def test_identical_linestring_geometry_duplicate(self):
        """Same path with different properties is a geometry duplicate."""
        coordinates = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
            [-105.64070, 38.79560],
        ]
        stored_line = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': coordinates},
            'properties': {'name': 'Trail A', 'description': 'Original'},
        }
        reimport_line = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': list(coordinates)},
            'properties': {'name': 'Trail B', 'description': 'Re-import'},
        }

        stored_hash = generate_geojson_hash(stored_line)
        stored_line['properties']['geojson_hash'] = stored_hash

        store_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=stored_line,
            geojson_hash=stored_hash,
            geometry=_linestring_geometry_3d(coordinates),
        )

        remaining, duplicates = partition([reimport_line], detect([reimport_line], self.user.id))

        self.assertEqual(len(remaining), 0)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.GEOMETRY)
        self.assertEqual(existing_id(duplicates[0][1]), store_feature.id)

    def test_linestring_coordinate_precision_geometry_duplicate(self):
        """Same path with per-vertex precision differences within tolerance is a duplicate."""
        stored_coords = [
            [-105.64053, 38.79543],
            [-105.64060, 38.79550],
        ]
        precise_coords = [
            [-105.64053344726562, 38.79542922973633],
            [-105.64060000000001, 38.79550000000001],
        ]
        stored_line = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': stored_coords},
            'properties': {'name': 'Trail', 'description': 'Lower precision'},
        }
        precise_line = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': precise_coords},
            'properties': {'name': 'Trail', 'description': 'Higher precision'},
        }

        stored_hash = generate_geojson_hash(stored_line)
        stored_line['properties']['geojson_hash'] = stored_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=stored_line,
            geojson_hash=stored_hash,
            geometry=_linestring_geometry_3d(stored_coords),
        )

        remaining, duplicates = partition([precise_line], detect([precise_line], self.user.id))

        self.assertEqual(len(remaining), 0)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.GEOMETRY)


class TestGeometryDuplicateBatchedPath(TestCase):
    """Geometry duplicate detection against the feature store."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='batched@example.com',
            password='testpass123',
            username='batcheduser',
        )
        self.coords = [-122.4194, 37.7749]

    def _seed_library_point(self):
        stored = _point_feature(self.coords[0], self.coords[1], 'Library point')
        stored_hash = generate_geojson_hash(stored)
        stored['properties']['geojson_hash'] = stored_hash
        return FeatureStore.objects.create(
            user=self.user,
            geojson=stored,
            geojson_hash=stored_hash,
            geometry=Point(self.coords[0], self.coords[1], 0, srid=4326),
        )

    def test_batched_path_detects_feature_store_geometry_duplicate(self):
        """Library geometry duplicates are detected for multiple import features."""
        self._seed_library_point()

        import_features = [
            _point_feature(self.coords[0], self.coords[1], f'Import {i}')
            for i in range(3)
        ]

        remaining, duplicates = partition(import_features, detect(import_features, self.user.id))

        self.assertEqual(len(remaining), 0)
        self.assertEqual(len(duplicates), 3)
        for _feature, verdict in duplicates:
            self.assertEqual(verdict.scope, VerdictScope.LIBRARY)
            self.assertEqual(verdict.kind, VerdictKind.GEOMETRY)


class TestDuplicatePriorityRules(TestCase):
    """Test priority rules: hash > geometry, library > draft queue."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

        self.point_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'},
        }

        self.same_coords_different_props = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Different Name', 'description': 'Different description'},
        }

    def test_hash_over_geometry_same_source_feature_store(self):
        """Test 5: Hash takes precedence over geometry in feature store."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash,
        )

        remaining, duplicates = partition(
            [self.point_feature],
            detect([self.point_feature], self.user.id),
        )

        self.assertEqual(len(duplicates), 1)
        self.assertEqual(
            duplicates[0][1].kind,
            VerdictKind.HASH,
            "Should be marked as hash duplicate, not geometry",
        )

    def test_hash_over_geometry_same_source_cross_queue(self):
        """Test 6: Hash takes precedence over geometry in cross-queue."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[self.point_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        remaining, duplicates = partition(
            [self.point_feature],
            detect([self.point_feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 1)
        self.assertEqual(
            duplicates[0][1].kind,
            VerdictKind.HASH,
            "Should be marked as hash duplicate, not geometry",
        )

    def test_feature_store_over_cross_queue_both_hash(self):
        """Test 7: Feature store hash takes precedence over cross-queue hash."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash,
        )

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[self.point_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        remaining, duplicates = partition(
            [self.point_feature],
            detect([self.point_feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 1, "Feature store should detect hash duplicate")
        self.assertEqual(duplicates[0][1].scope, VerdictScope.LIBRARY)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.HASH)
        self.assertEqual(len(remaining), 0, "No features should remain after two-pass")

    def test_feature_store_over_cross_queue_both_geometry(self):
        """Test 8: Feature store geometry takes precedence over cross-queue geometry."""
        feature_hash1 = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash1

        coords = self.point_feature['geometry']['coordinates']
        point_geom = Point(coords[0], coords[1], 0, srid=4326)

        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash1,
            geometry=point_geom,
        )

        older_feature = self.same_coords_different_props.copy()
        feature_hash2 = generate_geojson_hash(older_feature)
        older_feature['properties']['geojson_hash'] = feature_hash2

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[older_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        third_version = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Third Version', 'description': 'Yet another one'},
        }

        remaining, duplicates = partition(
            [third_version],
            detect([third_version], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 1, "Feature store should detect geometry duplicate")
        self.assertEqual(duplicates[0][1].scope, VerdictScope.LIBRARY)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.GEOMETRY)
        self.assertEqual(len(remaining), 0, "No features should remain after two-pass")

    def test_feature_store_hash_over_cross_queue_geometry(self):
        """Test 9: Feature store hash takes precedence over cross-queue geometry."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash,
        )

        queue_feature = self.same_coords_different_props.copy()
        queue_feature['properties']['geojson_hash'] = generate_geojson_hash(queue_feature)

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[queue_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        remaining, duplicates = partition(
            [self.point_feature],
            detect([self.point_feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0][1].scope, VerdictScope.LIBRARY)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.HASH)
        self.assertEqual(len(remaining), 0)

    def test_feature_store_geometry_over_cross_queue_hash(self):
        """Test 10: Feature store geometry takes precedence over cross-queue hash."""
        fs_feature = self.point_feature.copy()
        fs_hash = generate_geojson_hash(fs_feature)
        fs_feature['properties']['geojson_hash'] = fs_hash

        coords = fs_feature['geometry']['coordinates']
        point_geom = Point(coords[0], coords[1], 0, srid=4326)

        FeatureStore.objects.create(
            user=self.user,
            geojson=fs_feature,
            geojson_hash=fs_hash,
            geometry=point_geom,
        )

        queue_feature = self.same_coords_different_props.copy()
        queue_hash = generate_geojson_hash(queue_feature)
        queue_feature['properties']['geojson_hash'] = queue_hash

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[queue_feature],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        remaining, duplicates = partition(
            [queue_feature],
            detect([queue_feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0][1].scope, VerdictScope.LIBRARY)
        self.assertEqual(duplicates[0][1].kind, VerdictKind.GEOMETRY)
        self.assertEqual(len(remaining), 0)


class TestCrossQueueNavigation(TestCase):
    """Test cross-queue duplicate navigation features."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

    def test_cross_queue_duplicate_includes_feature_index(self):
        """Test 13: Cross-queue duplicates include feature_index for navigation."""
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'},
        }
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2'},
        }
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Feature 3'},
        }

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[feature1, feature2, feature3],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        duplicate_of_feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Duplicate of Feature 2', 'description': 'Different'},
        }

        remaining, duplicates = partition(
            [duplicate_of_feature2],
            detect([duplicate_of_feature2], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 1, "Should detect geometry duplicate")
        _feature, verdict = duplicates[0]
        self.assertEqual(verdict.scope, VerdictScope.DRAFT_QUEUE)
        self.assertEqual(verdict.kind, VerdictKind.GEOMETRY)

        match = verdict.matches[0]
        self.assertIsNotNone(match.spatial_index, "match must include spatial_index")
        self.assertEqual(
            match.spatial_index,
            1,
            "Feature index should be 1 (second feature in older queue)",
        )
        self.assertEqual(existing_id(verdict), older_queue.id)
        self.assertEqual(match.name, 'Feature 2')

    def test_cross_queue_hash_duplicate_includes_feature_index(self):
        """Test 14: Cross-queue hash duplicates also include feature_index."""
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1', 'description': 'First'},
        }
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2', 'description': 'Second'},
        }

        hash1 = generate_geojson_hash(feature1)
        hash2 = generate_geojson_hash(feature2)
        feature1['properties']['geojson_hash'] = hash1
        feature2['properties']['geojson_hash'] = hash2

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[feature1, feature2],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        remaining, hash_duplicates = partition(
            [feature2],
            detect([feature2], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(hash_duplicates), 1, "Should detect hash duplicate")
        match = hash_duplicates[0][1].matches[0]
        self.assertIsNotNone(match.spatial_index, "Hash duplicates must also include spatial_index")
        self.assertEqual(match.spatial_index, 1, "Feature index should be 1 (second feature)")


class TestSourceIsolation(TestCase):
    """Two-pass isolation: library matches beat draft-queue, and draft-only hits stay draft."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

    def test_feature_store_filter_ignores_cross_queue(self):
        """Test 15: A library geometry hit is scoped LIBRARY, not DRAFT_QUEUE."""
        feature_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Store Feature'},
        }
        store_hash = generate_geojson_hash(feature_in_store)
        feature_in_store['properties']['geojson_hash'] = store_hash

        coords = feature_in_store['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_in_store,
            geojson_hash=store_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326),
        )

        feature_in_queue = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Queue Feature', 'description': 'Different'},
        }
        queue_hash = generate_geojson_hash(feature_in_queue)
        feature_in_queue['properties']['geojson_hash'] = queue_hash

        queue_with_drafts(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            features=[feature_in_queue],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Test Feature', 'description': 'Third version'},
        }

        remaining, fs_dups = partition(
            [test_feature],
            detect([test_feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(fs_dups), 1, "Should find 1 feature store duplicate")
        self.assertEqual(
            fs_dups[0][1].scope,
            VerdictScope.LIBRARY,
            "Source must be LIBRARY, not DRAFT_QUEUE",
        )
        for _feature, verdict in fs_dups:
            self.assertNotEqual(
                verdict.scope,
                VerdictScope.DRAFT_QUEUE,
                "library hit must not be reported as a draft-queue duplicate",
            )

    def test_cross_queue_filter_ignores_feature_store(self):
        """Test 16: A draft-only geometry hit is scoped DRAFT_QUEUE, not LIBRARY."""
        feature_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.9, 37.1]},
            'properties': {'name': 'Store Feature'},
        }
        store_hash = generate_geojson_hash(feature_in_store)
        feature_in_store['properties']['geojson_hash'] = store_hash

        coords = feature_in_store['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_in_store,
            geojson_hash=store_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326),
        )

        feature_in_queue = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Queue Feature', 'description': 'Different'},
        }
        queue_hash = generate_geojson_hash(feature_in_queue)
        feature_in_queue['properties']['geojson_hash'] = queue_hash

        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[feature_in_queue],
            imported=False,
        )

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Test Feature', 'description': 'Third'},
        }

        remaining, cq_dups = partition(
            [test_feature],
            detect([test_feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(cq_dups), 1, "Should find 1 cross-queue duplicate")
        self.assertEqual(
            cq_dups[0][1].scope,
            VerdictScope.DRAFT_QUEUE,
            "Source must be DRAFT_QUEUE, not LIBRARY",
        )
        for _feature, verdict in cq_dups:
            self.assertNotEqual(
                verdict.scope,
                VerdictScope.LIBRARY,
                "draft-queue hit must not be reported as a library duplicate",
            )


class TestTimestampOrdering(TestCase):
    """Timestamp-based ordering prevents simultaneous upload conflicts."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

    def test_simultaneous_uploads_only_newer_shows_duplicates(self):
        """Test 17: Two files uploaded simultaneously - only newer one shows duplicates of older."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature', 'description': 'Test'},
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash

        base_time = timezone.now()
        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='file1.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        older_queue.timestamp = base_time
        older_queue.save()

        newer_queue = queue_with_drafts(
            user=self.user,
            original_filename='file2.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        newer_queue.timestamp = base_time + timedelta(seconds=1)
        newer_queue.save()

        remaining_older, dups_older = partition(
            [feature],
            detect([feature], self.user.id, older_queue.id, older_queue.timestamp),
        )

        self.assertEqual(
            len(dups_older),
            0,
            "Older file should NOT see newer file as duplicate "
            "(prevents simultaneous uploads from marking each other)",
        )
        self.assertEqual(
            len(remaining_older),
            1,
            "Feature in older file should remain (not marked as duplicate)",
        )

        remaining_newer, dups_newer = partition(
            [feature],
            detect([feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(dups_newer), 1, "Newer file should detect duplicate in older file")
        self.assertEqual(dups_newer[0][1].scope, VerdictScope.DRAFT_QUEUE)
        self.assertEqual(dups_newer[0][1].kind, VerdictKind.HASH)
        self.assertEqual(existing_id(dups_newer[0][1]), older_queue.id)

    def test_three_sequential_uploads_correct_ordering(self):
        """Test 18: Three files uploaded sequentially - each only sees older ones as duplicates."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Sequential Test', 'description': 'Test'},
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash

        base_time = timezone.now()

        queue1 = queue_with_drafts(
            user=self.user,
            original_filename='file1.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        queue1.timestamp = base_time
        queue1.save()

        queue2 = queue_with_drafts(
            user=self.user,
            original_filename='file2.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        queue2.timestamp = base_time + timedelta(seconds=5)
        queue2.save()

        queue3 = queue_with_drafts(
            user=self.user,
            original_filename='file3.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        queue3.timestamp = base_time + timedelta(seconds=10)
        queue3.save()

        _remaining1, dups1 = partition(
            [feature],
            detect([feature], self.user.id, queue1.id, queue1.timestamp),
        )
        self.assertEqual(len(dups1), 0, "File 1 (oldest) should see no duplicates")

        _remaining2, dups2 = partition(
            [feature],
            detect([feature], self.user.id, queue2.id, queue2.timestamp),
        )
        self.assertEqual(len(dups2), 1, "File 2 should see 1 duplicate (file 1)")
        self.assertEqual(existing_id(dups2[0][1]), queue1.id)

        _remaining3, dups3 = partition(
            [feature],
            detect([feature], self.user.id, queue3.id, queue3.timestamp),
        )
        self.assertEqual(len(dups3), 1, "File 3 should see 1 duplicate")
        self.assertIn(
            existing_id(dups3[0][1]),
            [queue1.id, queue2.id],
            "File 3 should reference either file 1 or file 2",
        )


class TestIntegration(TestCase):
    """Integration tests for the full two-pass detection flow."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

    def test_full_processing_flow_with_all_duplicate_types(self):
        """Test 19: Two-pass detection finds all 4 duplicate types in one run."""
        fs_hash_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'FS Hash Match', 'description': 'Will be hash duplicate'},
        }
        fs_hash = generate_geojson_hash(fs_hash_feature)
        fs_hash_feature['properties']['geojson_hash'] = fs_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=fs_hash_feature,
            geojson_hash=fs_hash,
            geometry=Point(-122.1, 37.7, 0, srid=4326),
        )

        fs_geom_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'FS Geom Match', 'description': 'Different props'},
        }
        fs_geom_hash = generate_geojson_hash(fs_geom_feature)

        FeatureStore.objects.create(
            user=self.user,
            geojson=fs_geom_feature,
            geojson_hash=fs_geom_hash,
            geometry=Point(-122.2, 37.8, 0, srid=4326),
        )

        cq_hash_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'CQ Hash Match', 'description': 'Queue hash'},
        }
        cq_hash = generate_geojson_hash(cq_hash_feature)
        cq_hash_feature['properties']['geojson_hash'] = cq_hash

        cq_geom_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4, 38.0]},
            'properties': {'name': 'CQ Geom Match', 'description': 'Queue geom'},
        }
        cq_geom_hash = generate_geojson_hash(cq_geom_feature)
        cq_geom_feature['properties']['geojson_hash'] = cq_geom_hash

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[cq_hash_feature, cq_geom_feature],
            imported=False,
        )
        older_queue.timestamp = timezone.now()
        older_queue.save()

        test_features = [
            fs_hash_feature.copy(),
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
                'properties': {'name': 'Different Name', 'description': 'Different'},
            },
            cq_hash_feature.copy(),
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4, 38.0]},
                'properties': {'name': 'Another Name', 'description': 'Different'},
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.5, 38.1]},
                'properties': {'name': 'Unique Feature', 'description': 'No duplicate'},
            },
        ]

        newer_queue = queue_with_drafts(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            features=test_features,
            imported=False,
        )

        remaining, all_duplicates = partition(
            test_features,
            detect(test_features, self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        fs_hash_dups = [
            (f, v) for f, v in all_duplicates
            if v.scope == VerdictScope.LIBRARY and v.kind == VerdictKind.HASH
        ]
        fs_geom_dups = [
            (f, v) for f, v in all_duplicates
            if v.scope == VerdictScope.LIBRARY and v.kind == VerdictKind.GEOMETRY
        ]
        cq_hash_dups = [
            (f, v) for f, v in all_duplicates
            if v.scope == VerdictScope.DRAFT_QUEUE and v.kind == VerdictKind.HASH
        ]
        cq_geom_dups = [
            (f, v) for f, v in all_duplicates
            if v.scope == VerdictScope.DRAFT_QUEUE and v.kind == VerdictKind.GEOMETRY
        ]

        self.assertEqual(len(all_duplicates), 4, "Should detect exactly 4 duplicates (1 of each type)")

        self.assertEqual(len(fs_hash_dups), 1, "Should have 1 feature store hash duplicate")
        self.assertEqual(len(fs_geom_dups), 1, "Should have 1 feature store geometry duplicate")

        self.assertEqual(len(cq_hash_dups), 1, "Should have 1 cross-queue hash duplicate")
        self.assertEqual(existing_id(cq_hash_dups[0][1]), older_queue.id)

        self.assertEqual(len(cq_geom_dups), 1, "Should have 1 cross-queue geometry duplicate")
        self.assertEqual(existing_id(cq_geom_dups[0][1]), older_queue.id)

        self.assertEqual(len(remaining), 1, "Should have exactly 1 unique feature remaining")
        self.assertEqual(remaining[0]['properties']['name'], 'Unique Feature')

    def test_integration_skipped_feature_ids_only_geometry(self):
        """Test 20: SkipIntent auto-skips geometry duplicates and blocks hash duplicates."""
        hash_dup_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Hash Dup', 'description': 'Exact match'},
        }
        hash_dup_hash = generate_geojson_hash(hash_dup_feature)
        hash_dup_feature['properties']['geojson_hash'] = hash_dup_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=hash_dup_feature,
            geojson_hash=hash_dup_hash,
            geometry=Point(-122.1, 37.7, 0, srid=4326),
        )

        geom_dup_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Store Geom', 'description': 'Store'},
        }
        store_geom_hash = generate_geojson_hash(geom_dup_in_store)

        FeatureStore.objects.create(
            user=self.user,
            geojson=geom_dup_in_store,
            geojson_hash=store_geom_hash,
            geometry=Point(-122.2, 37.8, 0, srid=4326),
        )

        geom_dup_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Geom Dup', 'description': 'Different'},
        }
        geom_dup_hash = generate_geojson_hash(geom_dup_feature)
        geom_dup_feature['properties']['geojson_hash'] = geom_dup_hash

        test_features = [hash_dup_feature, geom_dup_feature]
        verdicts = detect(test_features, self.user.id)
        remaining, fs_dups = partition(test_features, verdicts)

        fs_hash_dups = [pair for pair in fs_dups if pair[1].kind == VerdictKind.HASH]
        fs_geom_dups = [pair for pair in fs_dups if pair[1].kind == VerdictKind.GEOMETRY]
        intent = SkipIntent.from_verdicts(verdicts)
        skipped = intent.to_wire()['skipped']

        self.assertEqual(len(fs_hash_dups), 1, "Should have 1 hash duplicate")
        self.assertEqual(len(fs_geom_dups), 1, "Should have 1 geometry duplicate")

        self.assertEqual(len(skipped), 1, "skipped should only contain geometry duplicates")
        self.assertEqual(skipped[0], geom_dup_hash)
        self.assertNotIn(
            hash_dup_hash,
            skipped,
            "Hash duplicate should NOT be in skipped (it's blocked, not skipped)",
        )
        self.assertIn(hash_dup_hash, intent.blocked)
        self.assertIn(geom_dup_hash, intent.auto_skipped_geometry)


class TestComplexScenarios(TestCase):
    """Test complex real-world scenarios with multiple duplicates."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )

    def test_multiple_features_mixed_duplicates(self):
        """Test 11: Multiple features with different duplicate types."""
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Feature 1', 'description': 'First'},
        }
        hash1 = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = hash1

        coords1 = feature1['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature1,
            geojson_hash=hash1,
            geometry=Point(coords1[0], coords1[1], 0, srid=4326),
        )

        feature2_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5194, 37.8749]},
            'properties': {'name': 'Store Feature', 'description': 'In store'},
        }
        hash2_store = generate_geojson_hash(feature2_in_store)

        coords2 = feature2_in_store['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_in_store,
            geojson_hash=hash2_store,
            geometry=Point(coords2[0], coords2[1], 0, srid=4326),
        )

        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5194, 37.8749]},
            'properties': {'name': 'Feature 2', 'description': 'Second'},
        }

        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.6194, 37.9749]},
            'properties': {'name': 'Feature 3', 'description': 'Third'},
        }
        hash3 = generate_geojson_hash(feature3)
        feature3['properties']['geojson_hash'] = hash3

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[feature3],
            imported=False,
        )

        feature4_in_queue = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.7194, 38.0749]},
            'properties': {'name': 'Queue Feature', 'description': 'In queue'},
        }

        create_draft(older_queue, feature4_in_queue, spatial_index=1)

        feature4 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.7194, 38.0749]},
            'properties': {'name': 'Feature 4', 'description': 'Fourth'},
        }

        feature5 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.8194, 38.1749]},
            'properties': {'name': 'Feature 5', 'description': 'Unique'},
        }

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        all_features = [feature1, feature2, feature3, feature4, feature5]
        remaining, duplicates = partition(
            all_features,
            detect(all_features, self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        fs_dups = [(f, v) for f, v in duplicates if v.scope == VerdictScope.LIBRARY]
        cq_dups = [(f, v) for f, v in duplicates if v.scope == VerdictScope.DRAFT_QUEUE]

        self.assertEqual(len(fs_dups), 2, "Should have 2 feature store duplicates")
        self.assertEqual(len(cq_dups), 2, "Should have 2 cross-queue duplicates")
        self.assertEqual(len(remaining), 1, "Should have 1 unique feature")

        fs_hash_dups = [d for d in fs_dups if d[1].kind == VerdictKind.HASH]
        fs_geom_dups = [d for d in fs_dups if d[1].kind == VerdictKind.GEOMETRY]
        self.assertEqual(len(fs_hash_dups), 1, "Should have 1 FS hash duplicate")
        self.assertEqual(len(fs_geom_dups), 1, "Should have 1 FS geometry duplicate")

        cq_hash_dups = [d for d in cq_dups if d[1].kind == VerdictKind.HASH]
        cq_geom_dups = [d for d in cq_dups if d[1].kind == VerdictKind.GEOMETRY]
        self.assertEqual(len(cq_hash_dups), 1, "Should have 1 CQ hash duplicate")
        self.assertEqual(len(cq_geom_dups), 1, "Should have 1 CQ geometry duplicate")

    def test_no_duplicates(self):
        """Test 12: Features with no duplicates should all pass through."""
        features = [
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.1 + i, 37.7 + i]},
                'properties': {'name': f'Feature {i}', 'description': f'Description {i}'},
            }
            for i in range(5)
        ]

        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            imported=False,
        )

        remaining, duplicates = partition(
            features,
            detect(features, self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(duplicates), 0, "Should have no duplicates")
        self.assertEqual(len(remaining), 5, "All 5 features should remain")


class TestSequentialProcessingIntegration(TransactionTestCase):
    """Timestamp ordering used by sequential processing."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='sequential@example.com',
            password='testpass123',
            username='sequential_user',
        )

    def test_timestamp_ordering_enforced_by_sequential_processing(self):
        """Sequential processing enforces timestamp-based duplicate detection."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Sequential Test', 'description': 'Test'},
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash

        base_time = timezone.now()

        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        older_queue.timestamp = base_time
        older_queue.save()

        newer_queue = queue_with_drafts(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            features=[feature],
            imported=False,
        )
        newer_queue.timestamp = base_time + timedelta(seconds=5)
        newer_queue.save()

        _remaining_older, dups_older = partition(
            [feature],
            detect([feature], self.user.id, older_queue.id, older_queue.timestamp),
        )
        _remaining_newer, dups_newer = partition(
            [feature],
            detect([feature], self.user.id, newer_queue.id, newer_queue.timestamp),
        )

        self.assertEqual(len(dups_older), 0, "Older file should not see newer file as duplicate")
        self.assertEqual(len(dups_newer), 1, "Newer file should see older file as duplicate")
        self.assertEqual(existing_id(dups_newer[0][1]), older_queue.id)


class TestEmptyNameDuplicateDetection(TestCase):
    """Empty feature names must not break duplicate detection."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='emptyname@example.com',
            password='testpass123',
            username='emptynameuser',
        )

    def test_duplicate_detection_works_with_empty_names(self):
        """Duplicate detection works when features have empty names."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': '', 'description': 'Test'},
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash

        FeatureStore.objects.create(
            user=self.user,
            geojson=feature,
            geojson_hash=feature_hash,
            geometry=Point(-122.1, 37.7, 0, srid=4326),
        )

        remaining, duplicates = partition([feature], detect([feature], self.user.id))

        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        self.assertEqual(duplicates[0][1].kind, VerdictKind.HASH)

    def test_multiple_empty_names_different_geometry_not_duplicates(self):
        """Multiple features with empty names but different geometry are NOT duplicates."""
        features = [
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
                'properties': {'name': '', 'description': 'First'},
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
                'properties': {'name': '', 'description': 'Second'},
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
                'properties': {'name': '', 'description': 'Third'},
            },
        ]

        for feature in features:
            feature['properties']['geojson_hash'] = generate_geojson_hash(feature)

        remaining, duplicates = partition(features, detect(features, self.user.id))

        self.assertEqual(len(remaining), 3, "All 3 features should remain (no duplicates)")
        self.assertEqual(len(duplicates), 0, "Should have no duplicates")

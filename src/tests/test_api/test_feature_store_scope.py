"""
Regression tests proving extension-scoped `FeatureStore` rows (e.g. `scope='places'`)
are never returned or mutated by main-map endpoints -- the security-relevant fix from
the FeatureStore manager/FeatureService introduction (see api/models.py's
FeatureStoreQuerySet and api/services/feature_service.py).
"""
import json
import uuid

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash

_SCOPE = 'places'


def _feature_geojson(name: str, tags=None) -> dict:
    return {
        'type': 'Feature',
        'geometry': {
            'type': 'Point',
            'coordinates': [-122.4194, 37.7749, 0.0],
        },
        'properties': {
            'name': name,
            'tags': tags or [],
        },
    }


class TestFeatureStoreQuerySet(TestCase):
    """Unit tests for the chainable FeatureStoreQuerySet methods."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(email='a@example.com', password='x', username='a')
        self.other_user = User.objects.create_user(email='b@example.com', password='x', username='b')

        main_geojson = _feature_geojson('Main')
        self.main_feature = FeatureStore.objects.create(
            user=self.user, geojson=main_geojson, geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(main_geojson),
        )
        scoped_geojson = _feature_geojson('Scoped')
        self.scoped_feature = FeatureStore.objects.create(
            user=self.user, geojson=scoped_geojson, geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(scoped_geojson), scope=_SCOPE,
        )
        other_geojson = _feature_geojson('Other users main')
        self.other_user_feature = FeatureStore.objects.create(
            user=self.other_user, geojson=other_geojson, geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(other_geojson),
        )

    def test_main_map_excludes_scoped(self):
        ids = set(FeatureStore.objects.owned_by(self.user).main_map().values_list('id', flat=True))
        self.assertEqual(ids, {self.main_feature.id})

    def test_in_scope_only_returns_that_scope(self):
        ids = set(FeatureStore.objects.owned_by(self.user).in_scope(_SCOPE).values_list('id', flat=True))
        self.assertEqual(ids, {self.scoped_feature.id})

    def test_owned_by_excludes_other_users(self):
        ids = set(FeatureStore.objects.owned_by(self.user).values_list('id', flat=True))
        self.assertNotIn(self.other_user_feature.id, ids)


class TestFeatureScopeIsolationEndpoints(TestCase):
    """
    Endpoint-level regression tests: an extension-scoped feature (e.g. `places`) must
    never be visible to, or mutable by, a main-map endpoint even when the caller
    supplies its exact ID or a tag it happens to share with a main-map feature.
    """

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com', password='testpass123', username='testuser',
        )
        self.client.force_login(self.user)

        main_geojson = _feature_geojson('Main Feature', tags=['shared-tag'])
        self.main_feature = FeatureStore.objects.create(
            user=self.user, geojson=main_geojson, geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(main_geojson),
        )

        # Same tag as the main feature, but scoped -- must stay invisible to every
        # main-map endpoint tested below.
        scoped_geojson = _feature_geojson('Scoped Feature', tags=['shared-tag', 'scoped-only-tag'])
        self.scoped_feature = FeatureStore.objects.create(
            user=self.user, geojson=scoped_geojson, geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(scoped_geojson), scope=_SCOPE,
        )

    def test_get_feature_404s_for_scoped_feature(self):
        response = self.client.get(f'/api/feature/{self.scoped_feature.id}/')
        self.assertEqual(response.status_code, 404)

    def test_delete_feature_404s_for_scoped_feature(self):
        response = self.client.delete(f'/api/feature/{self.scoped_feature.id}/delete/')
        self.assertEqual(response.status_code, 404)
        self.assertTrue(FeatureStore.objects.filter(id=self.scoped_feature.id).exists())

    def test_regenerate_tags_404s_for_scoped_feature(self):
        response = self.client.post(f'/api/feature/{self.scoped_feature.id}/regenerate-tags/')
        self.assertEqual(response.status_code, 404)

    def test_bulk_delete_by_tag_never_deletes_scoped_feature(self):
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'shared-tag'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 1)
        self.assertFalse(FeatureStore.objects.filter(id=self.main_feature.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.scoped_feature.id).exists())

    def test_bulk_operations_by_tag_never_touches_scoped_feature(self):
        bulk_ops = {'bulk_operations': {'pointColor': '#ff0000'}}
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/shared-tag/',
            data=json.dumps(bulk_ops),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['updated_count'], 1)

        self.scoped_feature.refresh_from_db()
        self.assertNotIn('marker-color', self.scoped_feature.geojson['properties'])

    def test_bulk_operations_by_tag_only_matching_scoped_feature_reports_zero(self):
        bulk_ops = {'bulk_operations': {'pointColor': '#ff0000'}}
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/scoped-only-tag/',
            data=json.dumps(bulk_ops),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['updated_count'], 0)

    def test_get_feature_share_404s_for_scoped_feature(self):
        response = self.client.get(f'/api/sharing/features/{self.scoped_feature.id}/')
        self.assertEqual(response.status_code, 404)

    def test_create_feature_share_404s_for_scoped_feature(self):
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps({'share_type': 'feature', 'feature_id': self.scoped_feature.id}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 404)

    def test_create_tag_share_rejects_tag_that_only_matches_scoped_feature(self):
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps({'share_type': 'tag', 'tag': 'scoped-only-tag'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 404)

    def test_create_tag_share_succeeds_for_tag_also_on_main_map_feature(self):
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps({'share_type': 'tag', 'tag': 'shared-tag'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)

    def test_export_kmz_404s_for_scoped_feature_by_id(self):
        response = self.client.get(f'/api/export-kmz?feature={self.scoped_feature.id}')
        self.assertEqual(response.status_code, 404)

    def test_export_kmz_by_tag_that_only_matches_scoped_feature_returns_not_found(self):
        response = self.client.get('/api/export-kmz?tag=scoped-only-tag')
        self.assertEqual(response.status_code, 404)

    def test_get_all_features_excludes_scoped_feature(self):
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        ids = {f['properties']['database_id'] for f in data['data']['features']}
        self.assertIn(self.main_feature.id, ids)
        self.assertNotIn(self.scoped_feature.id, ids)

    def test_data_extent_hint_ignores_scoped_only_feature(self):
        # Move the scoped feature far away; if it leaked into the extent computation the
        # bbox would grow to include it.
        far_geojson = _feature_geojson('Far scoped feature')
        far_scoped = FeatureStore.objects.create(
            user=self.user, geojson=far_geojson, geometry=Point(170.0, 80.0, 0.0),
            geojson_hash=generate_geojson_hash(far_geojson), scope=_SCOPE,
        )
        response = self.client.get('/api/geojson/extent-hint/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        bbox = data['bbox']
        self.assertIsNotNone(bbox)
        # The far scoped feature's coordinates must not be reflected in the bbox.
        self.assertLess(bbox[2], 170.0)
        self.assertLess(bbox[3], 80.0)
        far_scoped.delete()

from django.contrib.auth import get_user_model
from django.test import TestCase

from api.services.feature_service import FeatureService
from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash


def _point_geojson(name: str) -> dict:
    return {
        'type': 'Feature',
        'geometry': {
            'type': 'Point',
            'coordinates': [10.0, 20.0, 0.0],
        },
        'properties': {
            'name': name,
        },
    }


class TestFeatureServiceCreate(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='svc@example.com',
            password='testpass123',
            username='svctest',
        )

    def test_create_without_scope_is_main_map(self):
        geojson = _point_geojson('Main')
        feature = FeatureService.create(self.user, geojson)
        self.assertIsNone(feature.scope)
        self.assertIsNotNone(feature.geometry)
        self.assertAlmostEqual(feature.geometry.x, 10.0)
        self.assertAlmostEqual(feature.geometry.y, 20.0)
        self.assertEqual(feature.geojson_hash, generate_geojson_hash(geojson))
        self.assertTrue(FeatureStore.objects.owned_by(self.user).main_map().filter(id=feature.id).exists())

    def test_create_with_scope(self):
        geojson = _point_geojson('Scoped')
        feature = FeatureService.create(self.user, geojson, scope='places')
        self.assertEqual(feature.scope, 'places')
        self.assertTrue(FeatureStore.objects.owned_by(self.user).in_scope('places').filter(id=feature.id).exists())
        self.assertFalse(FeatureStore.objects.owned_by(self.user).main_map().filter(id=feature.id).exists())

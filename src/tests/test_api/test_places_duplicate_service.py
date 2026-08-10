"""Direct PlaceService duplicate create coverage."""
from django.contrib.auth import get_user_model
from django.test import TestCase

from extensions.places.src.backend.services.place_service import PlaceService, PlaceServiceError

TEST_LON = -10.77
TEST_LAT = 20.77


class TestPlaceServiceDuplicate(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='dupsvc@example.com',
            password='testpass123',
            username='dupsvc',
        )
        self.service = PlaceService()
        self.payload = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [TEST_LON, TEST_LAT, 0.0]},
            'properties': {'name': 'Service Dup Place'},
        }

    def test_create_place_duplicate_raises_409(self):
        self.service.create_place(self.user, self.payload)
        with self.assertRaises(PlaceServiceError) as ctx:
            self.service.create_place(self.user, self.payload)
        self.assertEqual(ctx.exception.status_code, 409)
        self.assertIn('already exists', ctx.exception.message.lower())

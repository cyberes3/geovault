"""
Pytest configuration and fixtures for GeoVault backend tests.
"""
import os
import sys
from pathlib import Path

# Setup paths before Django imports - this is crucial for pytest-django
script_dir = Path(__file__).parent
backend_dir = script_dir.parent / 'backend'
src_dir = script_dir.parent

# Add backend and src to Python path
sys.path.insert(0, str(backend_dir))
sys.path.insert(0, str(src_dir))

# Set Django settings before any Django imports
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

# Note: Django 6.0a1 version parsing bug is fixed by pytest_django_version_fix.py plugin
# No need to patch here - the plugin handles it before any Django code runs

# Now we can import Django and pytest
import pytest
import django

# Initialize Django
if not django.apps.apps.ready:
    django.setup()

from django.conf import settings
from django.contrib.auth import get_user_model
from django.test import Client
from django.contrib.gis.geos import Point

from api.models import FeatureStore, ImportQueue, Collection, TagShare, CollectionShare, UserSettings
from users.models import ApiKey, UserProfile

User = get_user_model()


def pytest_configure():
    """
    Pytest hook that runs before test collection.
    Ensures test database settings are merged before any Django test framework initialization.
    """
    from django.db import connections
    
    # Force merge TEST database settings into default before any connection
    if 'TEST' in settings.DATABASES.get('default', {}):
        test_config = settings.DATABASES['default']['TEST'].copy()
        # Merge TEST settings into default database config
        # This ensures Django connects as the test user for all test operations
        settings.DATABASES['default'].update({
            'NAME': test_config.get('NAME', settings.DATABASES['default']['NAME']),
            'USER': test_config.get('USER', settings.DATABASES['default']['USER']),
            'PASSWORD': test_config.get('PASSWORD', settings.DATABASES['default'].get('PASSWORD', '')),
            'HOST': test_config.get('HOST', settings.DATABASES['default']['HOST']),
            'PORT': test_config.get('PORT', settings.DATABASES['default']['PORT']),
        })
        
        # Close any existing connections to force reconnection with new settings
        connections.close_all()


@pytest.fixture
def user(db):
    """Create a regular test user."""
    return User.objects.create_user(
        email='test@example.com',
        password='testpass123',
        username='testuser'
    )


@pytest.fixture
def admin_user(db):
    """Create an admin test user."""
    admin = User.objects.create_user(
        email='admin@example.com',
        password='adminpass123',
        username='adminuser',
        is_staff=True,
        is_superuser=True
    )
    return admin


@pytest.fixture
def client():
    """Django test client."""
    return Client()


@pytest.fixture
def authenticated_client(client, user):
    """Authenticated Django test client."""
    client.force_login(user)
    return client


@pytest.fixture
def sample_point_feature():
    """Sample GeoJSON Point feature."""
    return {
        'type': 'Feature',
        'geometry': {
            'type': 'Point',
            'coordinates': [-122.4194, 37.7749, 0.0]  # San Francisco, 3D with Z=0.0
        },
        'properties': {
            'name': 'Test Point',
            'description': 'A test point feature',
            'tags': ['test', 'point']
        }
    }


@pytest.fixture
def sample_linestring_feature():
    """Sample GeoJSON LineString feature."""
    return {
        'type': 'Feature',
        'geometry': {
            'type': 'LineString',
            'coordinates': [
                [-122.4194, 37.7749, 0.0],  # 3D coordinates
                [-122.4094, 37.7849, 0.0],
                [-122.3994, 37.7949, 0.0]
            ]
        },
        'properties': {
            'name': 'Test Line',
            'description': 'A test line feature',
            'tags': ['test', 'line'],
            'stroke': '#ff0000',
            'stroke-width': 2
        }
    }


@pytest.fixture
def sample_polygon_feature():
    """Sample GeoJSON Polygon feature."""
    return {
        'type': 'Feature',
        'geometry': {
            'type': 'Polygon',
            'coordinates': [[
                [-122.4194, 37.7749, 0.0],  # 3D coordinates
                [-122.4094, 37.7749, 0.0],
                [-122.4094, 37.7849, 0.0],
                [-122.4194, 37.7849, 0.0],
                [-122.4194, 37.7749, 0.0]
            ]]
        },
        'properties': {
            'name': 'Test Polygon',
            'description': 'A test polygon feature',
            'tags': ['test', 'polygon'],
            'stroke': '#00ff00',
            'fill': '#00ff00',
            'fill-opacity': 0.5
        }
    }


@pytest.fixture
def sample_kml_content():
    """Sample KML file content."""
    return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test Placemark</name>
      <description>Test description</description>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""


@pytest.fixture
def sample_gpx_content():
    """Sample GPX file content."""
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <trk>
    <name>Test Track</name>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>100</ele>
        <time>2023-01-01T00:00:00Z</time>
      </trkpt>
      <trkpt lat="37.7849" lon="-122.4094">
        <ele>200</ele>
        <time>2023-01-01T00:01:00Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""


@pytest.fixture
def feature_store(db, user, sample_point_feature):
    """Create a FeatureStore instance."""
    coords = sample_point_feature['geometry']['coordinates']
    # Ensure 3D Point (add Z=0.0 if only 2D coordinates)
    if len(coords) == 2:
        geometry = Point(coords[0], coords[1], 0.0)
    else:
        geometry = Point(coords[0], coords[1], coords[2])
    return FeatureStore.objects.create(
        user=user,
        geojson=sample_point_feature,
        geometry=geometry
    )


@pytest.fixture
def import_queue(db, user):
    """Create an ImportQueue instance."""
    return ImportQueue.objects.create(
        user=user,
        original_filename='test.kml',
        raw_file='<kml></kml>',
        geofeatures=[]
    )


@pytest.fixture
def collection(db, user):
    """Create a Collection instance."""
    return Collection.objects.create(
        user=user,
        name='Test Collection',
        description='A test collection',
        tags=['test'],
        feature_ids=[]
    )


@pytest.fixture
def api_key(db, user):
    """Create an API key for a user."""
    from users.api_keys import create_user_api_key
    key_obj, raw_key = create_user_api_key(user, 'Test API Key')
    return key_obj, raw_key


@pytest.fixture
def user_settings(db, user):
    """Create UserSettings for a user."""
    return UserSettings.objects.create(
        user=user,
        settings={'map': {'elevation_profile_source': 'api'}},
        hidden_features=[]
    )




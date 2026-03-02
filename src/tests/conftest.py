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

# Load tests/.env if present (e.g. AREAS_SERVER_DATABASE for waterways checks)
_env_file = script_dir / ".env"
if _env_file.exists():
    with open(_env_file, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, _, value = line.partition("=")
                key = key.strip()
                if key:
                    os.environ.setdefault(key, value.strip())

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

import time
import zipfile
from io import BytesIO
from unittest.mock import MagicMock, patch

from django.conf import settings
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.db import connections
from django.test import Client

from api.models import FeatureStore, ImportQueue, Collection, UserSettings
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, status_tracker
from users.api_keys import create_user_api_key
User = get_user_model()


def pytest_configure():
    """
    Pytest hook that runs before test collection.
    Ensures test database settings are merged before any Django test framework initialization.
    """
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


# New fixtures for concurrent and performance tests

@pytest.fixture
def concurrent_users(db):
    """Create multiple test users for concurrent operation testing."""
    users = []
    for i in range(3):
        user = User.objects.create_user(
            email=f'concurrent{i}@example.com',
            password='testpass123',
            username=f'concurrent_user_{i}'
        )
        users.append(user)
    return users


@pytest.fixture
def large_feature_set(db, user):
    """Create a large set of features for performance testing."""
    features = []
    for i in range(1000):
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194 + (i % 100) * 0.001, 37.7749 + (i // 100) * 0.001, 0.0]
            },
            'properties': {
                'name': f'Performance Test Feature {i}',
                'tags': ['performance', f'batch_{i // 100}']
            }
        }
        
        feature = FeatureStore(
            user=user,
            geojson=feature_data,
            geometry=Point(
                feature_data['geometry']['coordinates'][0],
                feature_data['geometry']['coordinates'][1],
                0.0
            ),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        features.append(feature)
    
    # Bulk create for efficiency
    FeatureStore.objects.bulk_create(features, batch_size=100)
    
    return FeatureStore.objects.filter(user=user)


@pytest.fixture
def mock_external_services():
    """
    DEPRECATED: Use conditional_external_api_mocking instead.
    Mock external services (elevation, reverse_geocoding) for error testing.
    """
    mocks = {}
    
    # Mock elevation service
    with patch('geo_lib.processing.elevation_service.get_elevation_for_coordinates') as mock_elevation:
        mock_elevation.return_value = [100.0]  # Default elevation
        mocks['elevation'] = mock_elevation
        
        # Mock reverse_geocoding service
        with patch('geo_lib.processing.reverse_geocoding.reverse_geocode') as mock_geocode:
            mock_geocode.return_value = {'address': 'Test Address'}
            mocks['geocode'] = mock_geocode
            
            yield mocks


@pytest.fixture(autouse=True)
def conditional_external_api_mocking():
    """
    Conditionally mock external APIs based on config.yaml settings.
    
    - Elevation API: Mocked if ELEVATION_API_ENABLED is False in config, otherwise real calls with timeout handling
    - Geocoding: Always mocked (not ready yet per requirements)
    - Logs warnings on external API timeouts/failures without failing tests
    """
    logger = get_tagged_logger(__name__)
    patches = []
    
    # Mock reverse geocoding: areas server returns (AreasQueryResponse, error) from fixtures.
    # Single-point (reverse_geocode_coordinates) uses query_areas_server (GET).
    def mock_query_areas_server(latitude: float, longitude: float):
        from tests.fixtures.geocoding_responses import get_areas_fixture
        from geo_lib.reverse_geocoding.areas_server_models import AreasQueryResponse
        areas = get_areas_fixture(latitude, longitude)
        if areas is not None:
            return (AreasQueryResponse.model_validate(areas), None)
        return (AreasQueryResponse.empty(), None)

    areas_server_patch = patch(
        'geo_lib.reverse_geocoding.location_tags.query_areas_server',
        side_effect=mock_query_areas_server,
    )
    areas_server_patch.start()
    patches.append(areas_server_patch)

    # Batch path (batch_reverse_geocode_coordinates) uses POST; return one AreasQueryResponse per point.
    def mock_query_areas_server_batch(points):
        from tests.fixtures.geocoding_responses import get_areas_fixture
        from geo_lib.reverse_geocoding.areas_server_models import AreasQueryResponse
        results = []
        for lat, lon in points:
            areas = get_areas_fixture(lat, lon)
            if areas is not None:
                results.append(AreasQueryResponse.model_validate(areas))
            else:
                results.append(AreasQueryResponse.empty())
        return (results, None)

    areas_server_batch_patch = patch(
        'geo_lib.reverse_geocoding.location_tags.query_areas_server_batch',
        side_effect=mock_query_areas_server_batch,
    )
    areas_server_batch_patch.start()
    patches.append(areas_server_batch_patch)

    # Mock IP geolocation service
    geocoding_patch2 = patch('geo_lib.ip_geolocation.get_geolocation_service')
    mock_ip_geo = geocoding_patch2.start()
    mock_ip_geo_service = MagicMock()
    mock_ip_geo_service.get_location_from_ip.return_value = None
    mock_ip_geo_service.reader = None  # Indicate database not available
    mock_ip_geo.return_value = mock_ip_geo_service
    patches.append(geocoding_patch2)
    
    # Always mock elevation API with real responses
    # Mock the requests.post call to the elevation API with hardcoded real responses
    logger.info("Mocking elevation API with real response data")
    elevation_patch = patch('geo_lib.processing.elevation_service.requests.post')
    mock_elevation_post = elevation_patch.start()
    
    def mock_elevation_response(url, json=None, data=None, headers=None, timeout=None):
        """
        Mock elevation API responses based on coordinates.
        Returns real elevation data captured from racemap API.
        
        Elevation data format: array of [lat, lon] pairs -> array of elevation values in meters
        
        """
        # Use json parameter if available
        if not json or not isinstance(json, list):
            # Return empty list for invalid requests
            response = MagicMock()
            response.status_code = 200
            response.json.return_value = []
            return response
        
        # Elevation lookup table with real data from racemap API (captured Dec 2025)
        # Format: (lat, lon) -> elevation in meters
        elevation_data = {
            # San Francisco area
            (37.7749, -122.4194): 16,
            (37.7849, -122.4094): 14,
            (37.7949, -122.3994): 3,
            # Blue Hills, Massachusetts area
            (42.2095, -71.1190): 82,
            (42.2181, -71.1127): 95,
            (42.2088, -71.1079): 110,
            # Nebraska test locations (approximate elevations)
            (42.7286, -102.4171): 1050,
            (41.6935, -101.3844): 860,
            (41.7292, -102.8719): 1100,
            # Colorado test locations (approximate elevations)
            (39.746, -104.844): 1655,   # Aurora, CO
            (39.2216, -105.9327): 2950,  # Near Fairplay, CO
            (40.3428, -105.6836): 2750,  # Rocky Mountain NP
            (40.2514, -105.8239): 2540,  # Grand Lake
            (39.0, -105.0): 2700,        # Park County
        }
        
        # Process each coordinate in the request
        elevations = []
        for coord in json:
            if not isinstance(coord, (list, tuple)) or len(coord) < 2:
                elevations.append(None)
                continue
            
            lat = round(float(coord[0]), 4)
            lon = round(float(coord[1]), 4)
            
            # Try exact match first
            elevation = elevation_data.get((lat, lon))
            
            # If no exact match, try nearby match (within 0.01 degrees ~1km)
            if elevation is None:
                for (data_lat, data_lon), data_elev in elevation_data.items():
                    if abs(data_lat - lat) < 0.01 and abs(data_lon - lon) < 0.01:
                        elevation = data_elev
                        break
            
            # If still no match, use a default based on latitude (rough approximation)
            if elevation is None:
                if 30 <= lat <= 50:  # US latitudes
                    elevation = int(100 + (lat - 30) * 50)  # Varies 100m to 1100m
                else:
                    elevation = 100  # Default fallback
            
            elevations.append(elevation)
        
        # Create mock response
        response = MagicMock()
        response.status_code = 200
        response.json.return_value = elevations
        return response
    
    mock_elevation_post.side_effect = mock_elevation_response
    patches.append(elevation_patch)
    
    yield
    
    # Clean up patches
    for p in patches:
        p.stop()


# E2E Import Test Fixtures

@pytest.fixture
def test_files_dir():
    """Get path to files directory."""
    return Path(__file__).parent / 'files'


@pytest.fixture
def load_test_kml(test_files_dir):
    """Load Test Items.kml from disk."""
    kml_path = test_files_dir / 'Test Items.kml'
    with open(kml_path, 'rb') as f:
        return f.read()


@pytest.fixture
def load_test_gpx(test_files_dir):
    """Load blue_hills.gpx from disk."""
    gpx_path = test_files_dir / 'blue_hills.gpx'
    with open(gpx_path, 'rb') as f:
        return f.read()


@pytest.fixture
def load_fells_loop_gpx(test_files_dir):
    """Load fells_loop.gpx from disk."""
    gpx_path = test_files_dir / 'fells_loop.gpx'
    with open(gpx_path, 'rb') as f:
        return f.read()


@pytest.fixture
def load_google_earth_kml(test_files_dir):
    """Load Google Earth KML Samples.kml from disk."""
    kml_path = test_files_dir / 'Google Earth KML Samples.kml'
    with open(kml_path, 'rb') as f:
        return f.read()


@pytest.fixture
def create_test_kmz():
    """Create a KMZ file (ZIP with doc.kml inside) from KML content."""
    def _create_kmz(kml_content: bytes) -> bytes:
        """Create KMZ file from KML content."""
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
        return zip_buffer.getvalue()
    
    return _create_kmz


@pytest.fixture
def wait_for_job_completion():
    """Helper to wait for async job completion with timeout."""
    def _wait(job_id: str, timeout: float = 30.0, poll_interval: float = 0.5) -> dict:
        """
        Wait for job to complete.
        
        Args:
            job_id: Job ID to wait for
            timeout: Maximum time to wait in seconds
            poll_interval: Time between status checks
            
        Returns:
            Final job status dict
            
        Raises:
            TimeoutError: If job doesn't complete within timeout
        """
        start_time = time.time()
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED, ProcessingStatus.FAILED]:
                return job_status
            
            time.sleep(poll_interval)
        
        raise TimeoutError(f"Job {job_id} did not complete within {timeout} seconds")
    
    return _wait


@pytest.fixture
def wait_for_processing(wait_for_job_completion):
    """Wait for file processing job to complete."""
    def _wait(job_id: str, timeout: float = 30.0) -> dict:
        """Wait for processing job and verify success."""
        job_status = wait_for_job_completion(job_id, timeout)
        
        if job_status['status'] == ProcessingStatus.FAILED:
            error_msg = job_status.get('error_message', job_status.get('message', 'Unknown error'))
            raise RuntimeError(f"Processing failed: {error_msg}")
        
        return job_status
    
    return _wait


@pytest.fixture
def wait_for_import(wait_for_job_completion):
    """Wait for import job to complete."""
    def _wait(job_id: str, timeout: float = 30.0) -> dict:
        """Wait for import job and verify success."""
        job_status = wait_for_job_completion(job_id, timeout)
        
        if job_status['status'] == ProcessingStatus.FAILED:
            error_msg = job_status.get('error_message', job_status.get('message', 'Unknown error'))
            raise RuntimeError(f"Import failed: {error_msg}")
        
        return job_status
    
    return _wait



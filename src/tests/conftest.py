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
        
        from geo_lib.feature_id import generate_feature_hash
        
        feature = FeatureStore(
            user=user,
            geojson=feature_data,
            geometry=Point(
                feature_data['geometry']['coordinates'][0],
                feature_data['geometry']['coordinates'][1],
                0.0
            ),
            geojson_hash=generate_feature_hash(feature_data)
        )
        features.append(feature)
    
    # Bulk create for efficiency
    FeatureStore.objects.bulk_create(features, batch_size=100)
    
    return FeatureStore.objects.filter(user=user)


@pytest.fixture
def mock_external_services():
    """
    DEPRECATED: Use conditional_external_api_mocking instead.
    Mock external services (elevation, geocoding) for error testing.
    """
    from unittest.mock import patch, MagicMock
    
    mocks = {}
    
    # Mock elevation service
    with patch('geo_lib.processing.elevation_service.get_elevation_for_coordinates') as mock_elevation:
        mock_elevation.return_value = [100.0]  # Default elevation
        mocks['elevation'] = mock_elevation
        
        # Mock geocoding service
        with patch('geo_lib.processing.geocoding.reverse_geocode') as mock_geocode:
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
    import logging
    import requests
    from unittest.mock import patch, MagicMock
    from website.settings_utils import get_required_setting
    
    logger = logging.getLogger(__name__)
    patches = []
    
    # Always mock geocoding services (not ready yet)
    # Mock reverse geocoding service
    geocoding_patch1 = patch('geo_lib.geolocation.reverse_geocode.get_reverse_geocoding_service')
    mock_geocoding = geocoding_patch1.start()
    mock_geocoding_service = MagicMock()
    mock_geocoding_service.reverse_geocode.return_value = None  # Return None to simulate disabled
    mock_geocoding.return_value = mock_geocoding_service
    patches.append(geocoding_patch1)
    
    # Mock IP geolocation service
    geocoding_patch2 = patch('geo_lib.geolocation.ip_service.get_geolocation_service')
    mock_ip_geo = geocoding_patch2.start()
    mock_ip_geo_service = MagicMock()
    mock_ip_geo_service.get_location_from_ip.return_value = None
    mock_ip_geo_service.reader = None  # Indicate database not available
    mock_ip_geo.return_value = mock_ip_geo_service
    patches.append(geocoding_patch2)
    
    # Conditionally mock elevation API based on config
    elevation_enabled = False
    try:
        elevation_enabled = get_required_setting('ELEVATION_API_ENABLED')
    except Exception as e:
        logger.warning(f"Could not read ELEVATION_API_ENABLED from config: {e}")
    
    if not elevation_enabled:
        logger.info("Elevation API disabled in config - mocking elevation service")
        elevation_patch = patch('geo_lib.processing.elevation_service.fill_missing_elevations')
        mock_elevation_fill = elevation_patch.start()
        # Return the geojson unchanged (no elevation filling)
        mock_elevation_fill.side_effect = lambda geojson, log: geojson
        patches.append(elevation_patch)
    else:
        logger.info("Elevation API enabled in config - using real elevation service with timeout handling")
        # Wrap real elevation calls with timeout/error handling
        original_fill = None
        try:
            from geo_lib.processing import elevation_service
            original_fill = elevation_service.fill_missing_elevations
        except ImportError:
            pass
        
        if original_fill:
            def wrapped_fill_elevations(geojson_data, import_log):
                """Wrap elevation API calls with timeout handling."""
                try:
                    return original_fill(geojson_data, import_log)
                except requests.Timeout as e:
                    logger.warning(f"Elevation API timeout: {e}")
                    import_log.add("Elevation API timed out", "Test Wrapper", 2)  # WARNING level
                    return geojson_data  # Return unchanged
                except requests.RequestException as e:
                    logger.warning(f"Elevation API unavailable: {e}")
                    import_log.add("Elevation API unavailable", "Test Wrapper", 2)  # WARNING level
                    return geojson_data  # Return unchanged
                except Exception as e:
                    logger.warning(f"Elevation API error: {e}")
                    import_log.add(f"Elevation API error: {str(e)}", "Test Wrapper", 2)
                    return geojson_data  # Return unchanged
            
            elevation_patch = patch('geo_lib.processing.elevation_service.fill_missing_elevations', wrapped_fill_elevations)
            elevation_patch.start()
            patches.append(elevation_patch)
    
    yield
    
    # Clean up patches
    for p in patches:
        p.stop()


# E2E Import Test Fixtures

@pytest.fixture
def test_files_dir():
    """Get path to test files directory."""
    return Path(__file__).parent / 'test files'


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
    import zipfile
    from io import BytesIO
    
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
    import time
    from geo_lib.processing.status_tracker import status_tracker, ProcessingStatus
    
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
        
        from geo_lib.processing.status_tracker import ProcessingStatus
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
        
        from geo_lib.processing.status_tracker import ProcessingStatus
        if job_status['status'] == ProcessingStatus.FAILED:
            error_msg = job_status.get('error_message', job_status.get('message', 'Unknown error'))
            raise RuntimeError(f"Import failed: {error_msg}")
        
        return job_status
    
    return _wait




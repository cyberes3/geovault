
import pytest
import json
from django.urls import reverse
from django.contrib.gis.geos import Point, Polygon
from api.models import FeatureStore
from api.views.features.bbox_utils import get_features_in_bbox

@pytest.fixture
def tagged_features(user):
    """Create a set of features with various tags for testing."""
    # Feature 1: 'ski', 'city:Telluride'
    f1 = FeatureStore.objects.create(
        user=user,
        geometry=Point(-107.8, 37.9, 0.0),
        geojson={
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-107.8, 37.9, 0.0]},
            'properties': {'tags': ['ski', 'city:Telluride'], 'name': 'Telluride Ski Resort'}
        }
    )
    
    # Feature 2: 'mountain', 'city:Denver'
    f2 = FeatureStore.objects.create(
        user=user,
        geometry=Point(-104.9, 39.7, 0.0),
        geojson={
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-104.9, 39.7, 0.0]},
            'properties': {'tags': ['mountain', 'city:Denver'], 'name': 'Denver Mountains'}
        }
    )
    
    # Feature 3: 'other'
    f3 = FeatureStore.objects.create(
        user=user,
        geometry=Point(-105.0, 40.0, 0.0),
        geojson={
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-105.0, 40.0, 0.0]},
            'properties': {'tags': ['other'], 'name': 'Other Place'}
        }
    )
    
    # Feature 4: System tags 'source-file:test.kml'
    f4 = FeatureStore.objects.create(
        user=user,
        geometry=Point(-106.0, 39.0, 0.0),
        geojson={
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-106.0, 39.0, 0.0]},
            'properties': {
                'tags': [], 
                'system_tags': ['source-file:test.kml'],
                'name': 'Imported Feature'
            }
        }
    )

    return [f1, f2, f3, f4]

@pytest.mark.django_db
class TestTagFiltering:
    
    def test_exact_match(self, user, tagged_features):
        """Test exact tag matching."""
        # Test 'ski' -> Should find f1
        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['ski'])
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Telluride Ski Resort'
        
        # Test 'mountain' -> Should find f2
        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['mountain'])
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Denver Mountains'

    def test_prefix_match(self, user, tagged_features):
        """Test prefix tag matching (ending with :)."""
        # Test 'city:' -> Should find f1 and f2
        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['city:'])
        assert len(result.features) == 2
        names = sorted([f['properties']['name'] for f in result.features])
        assert names == ['Denver Mountains', 'Telluride Ski Resort']
        
        # Test 'source-file:' (system tag) -> Should find f4
        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['source-file:'])
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Imported Feature'

    def test_multiple_tags_and_mode(self, user, tagged_features):
        """Test multiple tags with AND logic."""
        # 'ski' AND 'city:Telluride' -> Should find f1
        result = get_features_in_bbox(
            bbox=None, 
            user_id=user.id, 
            tags=['ski', 'city:Telluride'], 
            match_mode='AND'
        )
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Telluride Ski Resort'
        
        # 'ski' AND 'city:Denver' -> Should find nothing
        result = get_features_in_bbox(
            bbox=None, 
            user_id=user.id, 
            tags=['ski', 'city:Denver'], 
            match_mode='AND'
        )
        assert len(result.features) == 0

    def test_multiple_tags_or_mode(self, user, tagged_features):
        """Test multiple tags with OR logic."""
        # 'ski' OR 'mountain' -> Should find f1 and f2
        result = get_features_in_bbox(
            bbox=None, 
            user_id=user.id, 
            tags=['ski', 'mountain'], 
            match_mode='OR'
        )
        assert len(result.features) == 2
        names = sorted([f['properties']['name'] for f in result.features])
        assert names == ['Denver Mountains', 'Telluride Ski Resort']

    def test_bbox_api_integration(self, authenticated_client, tagged_features):
        """Test the /api/geojson/ endpoint with tag parameters."""
        # Bbox covering all features
        bbox = "-110,35,-100,45"
        
        # Test exact match via API
        url = f"/api/geojson/?bbox={bbox}&zoom=10&tags=ski"
        response = authenticated_client.get(url)
        assert response.status_code == 200
        data = response.json()
        assert len(data['data']['features']) == 1
        assert data['data']['features'][0]['properties']['name'] == 'Telluride Ski Resort'
        
        # Test prefix match + OR mode
        url = f"/api/geojson/?bbox={bbox}&zoom=10&tags=city:&tags=other&match_mode=OR"
        response = authenticated_client.get(url)
        assert response.status_code == 200
        data = response.json()
        # Should match f1 (city:), f2 (city:), and f3 (other)
        assert len(data['data']['features']) == 3

    def test_filter_by_tags_endpoint(self, authenticated_client, tagged_features):
        """Test the /api/features/filter-by-tags/ endpoint (which uses bbox=None internally)."""
        # Test basic filtering
        url = "/api/features/filter-by-tags/?tags=mountain"
        response = authenticated_client.get(url)
        assert response.status_code == 200
        data = response.json()
        assert len(data['data']['features']) == 1
        assert data['data']['features'][0]['properties']['name'] == 'Denver Mountains'
        
        # Test prefix matching
        url = "/api/features/filter-by-tags/?tags=city:"
        response = authenticated_client.get(url)
        assert response.status_code == 200
        data = response.json()
        assert len(data['data']['features']) == 2

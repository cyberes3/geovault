import pytest

from api.services.feature_service import FeatureService
from api.views.features.bbox.execution import get_features_in_bbox


@pytest.fixture
def tagged_features(user):
    FeatureService.create(user, {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
        'properties': {'tags': ['ski', 'city:telluride'], 'name': 'Telluride Ski Resort'},
    })
    FeatureService.create(user, {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [11.0, 21.0, 0.0]},
        'properties': {'tags': ['mountain', 'city:denver'], 'name': 'Denver Mountains'},
    })
    FeatureService.create(user, {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [12.0, 22.0, 0.0]},
        'properties': {'tags': ['other'], 'name': 'Other Place'},
    })
    FeatureService.create(user, {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [13.0, 23.0, 0.0]},
        'properties': {
            'tags': [],
            'system_tags': ['source-file:test.kml'],
            'name': 'Imported Feature',
        },
    })
    return True


@pytest.mark.django_db
class TestTagFiltering:

    def test_exact_match(self, user, tagged_features):
        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['ski'])
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Telluride Ski Resort'

        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['mountain'])
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Denver Mountains'

    def test_prefix_match(self, user, tagged_features):
        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['city:'])
        assert len(result.features) == 2
        names = sorted([feature['properties']['name'] for feature in result.features])
        assert names == ['Denver Mountains', 'Telluride Ski Resort']

        result = get_features_in_bbox(bbox=None, user_id=user.id, tags=['source-file:'])
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Imported Feature'

    def test_multiple_tags_and_mode(self, user, tagged_features):
        result = get_features_in_bbox(
            bbox=None,
            user_id=user.id,
            tags=['ski', 'city:telluride'],
            match_mode='AND',
        )
        assert len(result.features) == 1
        assert result.features[0]['properties']['name'] == 'Telluride Ski Resort'

        result = get_features_in_bbox(
            bbox=None,
            user_id=user.id,
            tags=['ski', 'city:denver'],
            match_mode='AND',
        )
        assert len(result.features) == 0

    def test_multiple_tags_or_mode(self, user, tagged_features):
        result = get_features_in_bbox(
            bbox=None,
            user_id=user.id,
            tags=['ski', 'mountain'],
            match_mode='OR',
        )
        assert len(result.features) == 2
        names = sorted([feature['properties']['name'] for feature in result.features])
        assert names == ['Denver Mountains', 'Telluride Ski Resort']

    def test_bbox_api_integration(self, authenticated_client, tagged_features):
        bbox = "0,0,20,30"
        url = f"/api/geojson/?bbox={bbox}&zoom=10&tags=ski"
        response = authenticated_client.get(url)
        assert response.status_code == 200
        data = response.json()
        assert len(data['data']['features']) == 1
        assert data['data']['features'][0]['properties']['name'] == 'Telluride Ski Resort'

        url = f"/api/geojson/?bbox={bbox}&zoom=10&tags=city:&tags=other&match_mode=OR"
        response = authenticated_client.get(url)
        assert response.status_code == 200
        data = response.json()
        assert len(data['data']['features']) == 3

    def test_filter_by_tags_endpoint_removed(self, authenticated_client, tagged_features):
        response = authenticated_client.get("/api/features/filter-by-tags/?tags=mountain")
        assert response.status_code == 404

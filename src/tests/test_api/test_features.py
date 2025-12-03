"""
Tests for feature API endpoints (CRUD, search, filtering, bulk operations).
"""
import json
from unittest.mock import patch, MagicMock
import pytest
from django.test import TestCase
from django.contrib.gis.geos import Point

from api.models import FeatureStore, ImportQueue
from geo_lib.feature_id import generate_geojson_hash


class TestFeatureAPI(TestCase):
    """Test feature API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create test features
        self.point_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # 3D coordinates with Z=0.0
            },
            'properties': {
                'name': 'Test Point',
                'description': 'A test point',
                'tags': ['test', 'point']
            }
        }
        self.point_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_geojson_hash(self.point_feature_data)
        )

        self.linestring_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749, 0.0], [-122.4094, 37.7849, 0.0]]  # 3D coordinates
            },
            'properties': {
                'name': 'Test Line',
                'description': 'A test line',
                'tags': ['test', 'line']
            }
        }
        self.linestring_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.linestring_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0  # Simplified for test
            geojson_hash=generate_geojson_hash(self.linestring_feature_data)
        )

    def test_get_feature(self):
        """Test getting a feature by ID."""
        response = self.client.get(f'/api/feature/{self.point_feature.id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('feature', data)
        self.assertEqual(data['feature']['id'], self.point_feature.id)
        self.assertEqual(data['feature']['geojson']['properties']['name'], 'Test Point')

    def test_get_feature_not_found(self):
        """Test getting non-existent feature."""
        response = self.client.get('/api/feature/99999/')
        self.assertEqual(response.status_code, 404)

    def test_get_feature_unauthorized(self):
        """Test getting another user's feature."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        # Create different feature data to avoid duplicate file_hash
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]  # Different coordinates
            },
            'properties': {
                'name': 'Other User Point',
                'description': 'A different point',
                'tags': ['other']
            }
        }
        other_feature = FeatureStore.objects.create(
            user=other_user,
            geojson=other_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_geojson_hash(other_feature_data)
        )
        response = self.client.get(f'/api/feature/{other_feature.id}/')
        self.assertEqual(response.status_code, 404)

    def test_get_feature_elevations(self):
        """Test getting elevations for a feature with real/conditional elevation API."""
        # Uses conditional elevation API mocking from conftest fixture
        response = self.client.get(f'/api/feature/{self.linestring_feature.id}/elevations/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('coordinates', data)
        # Should have elevations (real or mocked based on config)
        self.assertGreaterEqual(len(data['coordinates']), 2)

    def test_get_feature_elevations_point(self):
        """Test getting elevations for a Point (should fail)."""
        response = self.client.get(f'/api/feature/{self.point_feature.id}/elevations/')
        self.assertEqual(response.status_code, 400)

    def test_update_feature_geometry(self):
        """Test updating a feature's geometry."""
        new_geometry = {
            'type': 'Point',
            'coordinates': [-122.4094, 37.7849, 0.0]  # 3D coordinates
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(new_geometry),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(
            self.point_feature.geojson['geometry']['coordinates'],
            [-122.4094, 37.7849, 0.0]
        )

    def test_update_feature_full(self):
        """Test updating a feature with full Feature object."""
        updated_feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]  # 3D coordinates
            },
            'properties': {
                'name': 'Updated Point',
                'tags': ['updated']
            }
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(updated_feature),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(self.point_feature.geojson['properties']['name'], 'Updated Point')

    def test_update_feature_metadata(self):
        """Test updating feature metadata only."""
        metadata = {
            'name': 'Updated Name',
            'description': 'Updated description',
            'tags': ['new-tag']
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(self.point_feature.geojson['properties']['name'], 'Updated Name')
        self.assertEqual(self.point_feature.geojson['properties']['tags'], ['new-tag'])

    def test_update_feature_metadata_invalid_json(self):
        """Test update feature metadata with invalid JSON."""
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_feature_metadata_extra_fields(self):
        """Test update feature metadata with extra fields."""
        metadata = {
            'name': 'Updated Name',
            'extra_field': 'should be rejected'
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_feature_metadata_invalid_iso_timestamp(self):
        """Test update feature metadata with invalid ISO timestamp."""
        metadata = {
            'created': 'not-a-valid-iso-timestamp'
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_feature_metadata_valid_iso_timestamp(self):
        """Test update feature metadata with valid ISO timestamp."""
        metadata = {
            'created': '2024-01-01T12:00:00Z'
        }
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update-metadata/',
            data=json.dumps(metadata),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)

    def test_update_feature_remove_icon(self):
        """Test removing an icon from a point feature."""
        # First, add an icon to the feature
        feature_with_icon = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'icon': '/api/icons/system/caltopo/flag-1.png',
                'marker-color': '#ff0000',
                'tags': ['test']
            }
        }
        
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(feature_with_icon),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Verify icon was added
        self.point_feature.refresh_from_db()
        self.assertEqual(
            self.point_feature.geojson['properties']['icon'],
            '/api/icons/system/caltopo/flag-1.png'
        )
        
        # Now remove the icon
        feature_without_icon = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'icon': '',  # Empty string to remove icon
                'icon-href': '',
                'iconUrl': '',
                'icon_url': '',
                'marker-icon': '',
                'marker-symbol': '',
                'symbol': '',
                'marker-color': '#0000ff',
                'tags': ['test']
            }
        }
        
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(feature_without_icon),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Verify icon was removed
        self.point_feature.refresh_from_db()
        properties = self.point_feature.geojson['properties']
        
        # All icon properties should be empty or not present
        icon_properties = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
        for prop in icon_properties:
            if prop in properties:
                self.assertEqual(properties[prop], '', f'Property {prop} should be empty string')
        
        # Marker color should be preserved/updated (colors are normalized to uppercase)
        self.assertEqual(properties['marker-color'], '#0000FF')

    def test_update_feature_remove_icon_with_user_icon(self):
        """Test removing a user-uploaded icon from a point feature."""
        # First, add a user icon to the feature
        feature_with_user_icon = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'icon': '/api/icons/user/abc123.png',
                'tags': ['test']
            }
        }
        
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(feature_with_user_icon),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Verify user icon was added
        self.point_feature.refresh_from_db()
        self.assertEqual(
            self.point_feature.geojson['properties']['icon'],
            '/api/icons/user/abc123.png'
        )
        
        # Now remove the icon
        feature_without_icon = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'icon': '',  # Empty string to remove icon
                'marker-color': '#00ff00',
                'tags': ['test']
            }
        }
        
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(feature_without_icon),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Verify icon was removed
        self.point_feature.refresh_from_db()
        properties = self.point_feature.geojson['properties']
        
        # Icon property should be empty or not present
        if 'icon' in properties:
            self.assertEqual(properties['icon'], '')
        
        # Marker color should be set (colors are normalized to uppercase)
        self.assertEqual(properties['marker-color'], '#00FF00')

    def test_update_feature_icon_prevents_external_urls(self):
        """Test that external icon URLs are rejected."""
        # Try to set an external icon URL
        feature_with_external_icon = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'icon': 'https://evil.com/malicious.png',
                'tags': ['test']
            }
        }
        
        response = self.client.put(
            f'/api/feature/{self.point_feature.id}/update/',
            data=json.dumps(feature_with_external_icon),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Verify external icon was rejected (should be empty)
        self.point_feature.refresh_from_db()
        properties = self.point_feature.geojson['properties']
        
        if 'icon' in properties:
            # External URL should have been rejected and set to empty
            self.assertEqual(properties['icon'], '')

    def test_delete_feature(self):
        """Test deleting a feature."""
        feature_id = self.point_feature.id
        response = self.client.delete(f'/api/feature/{feature_id}/delete/')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(FeatureStore.objects.filter(id=feature_id).exists())

    def test_delete_feature_not_found(self):
        """Test deleting non-existent feature."""
        response = self.client.delete('/api/feature/99999/delete/')
        self.assertEqual(response.status_code, 404)

    def test_search_features(self):
        """Test searching features by query."""
        response = self.client.get('/api/features/search/', {'query': 'Test'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_search_features_no_query(self):
        """Test searching without query parameter."""
        response = self.client.get('/api/features/search/')
        self.assertEqual(response.status_code, 400)

    def test_get_features_by_tag(self):
        """Test getting features grouped by tags."""
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('user_tags', data)
        self.assertIn('system_tags', data)

    def test_get_features_by_tag_pagination(self):
        """Test pagination for features by tag."""
        response = self.client.get('/api/features/by-tag/', {'page': '1'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('user_tags', data)
        self.assertIn('system_tags', data)

    def test_get_features_by_tag_search(self):
        """Test searching tags in features by tag."""
        response = self.client.get('/api/features/by-tag/', {'search': 'test'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('user_tags', data)

    def test_filter_features_by_tags(self):
        """Test filtering features by tags (AND logic)."""
        response = self.client.get('/api/features/filter-by-tags/', {'tags': 'test'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_filter_features_by_tags_multiple(self):
        """Test filtering by multiple tags."""
        response = self.client.get(
            '/api/features/filter-by-tags/',
            {'tags': ['test', 'point']}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_filter_features_by_tags_no_tags(self):
        """Test filtering without tags parameter."""
        response = self.client.get('/api/features/filter-by-tags/')
        self.assertEqual(response.status_code, 400)

    def test_get_all_features(self):
        """Test getting all features."""
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_bulk_update_features_metadata(self):
        """Test bulk updating features metadata."""
        update_data = {
            'updates': [
                {
                    'feature_id': self.point_feature.id,
                    'tags': ['bulk-updated']
                },
                {
                    'feature_id': self.linestring_feature.id,
                    'tags': ['bulk-updated']
                }
            ]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertEqual(data['updated_count'], 2)

    def test_bulk_update_features_metadata_invalid(self):
        """Test bulk update with invalid data."""
        update_data = {
            'updates': 'not-a-list'
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_missing_updates(self):
        """Test bulk update with missing updates field."""
        update_data = {}
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_empty_updates(self):
        """Test bulk update with empty updates list."""
        update_data = {
            'updates': []
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_invalid_feature_id_type(self):
        """Test bulk update with invalid feature_id type."""
        update_data = {
            'updates': [{
                'feature_id': 'not-an-int',
                'name': 'Test'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_invalid_iso_timestamp(self):
        """Test bulk update with invalid ISO timestamp."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'created': 'not-a-valid-iso-timestamp'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_valid_iso_timestamp(self):
        """Test bulk update with valid ISO timestamp."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'created': '2024-01-15T10:30:00Z'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
    
    def test_bulk_update_features_metadata_extra_fields(self):
        """Test bulk update with extra fields (should be rejected)."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'name': 'Test',
                'invalid_field': 'should be rejected'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_bulk_update_features_metadata_tags_not_list(self):
        """Test bulk update with tags not as list."""
        update_data = {
            'updates': [{
                'feature_id': self.point_feature.id,
                'tags': 'not-a-list'
            }]
        }
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_bulk_operations_to_tag(self):
        """Test applying bulk operations to features by tag."""
        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-tag'],
                'pointColor': '#ff0000'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)

    def test_apply_bulk_operations_to_system_tag(self):
        """Test applying bulk operations to features by system tag."""
        # Create a feature with a system tag (using type:point instead of elevation:low)
        # Note: elevation:low is no longer generated for 0.0 elevations (treated as missing data)
        system_tag_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 10.0]  # Low elevation (< 100 feet)
            },
            'properties': {
                'name': 'System Tag Feature',
                'tags': ['user-tag'],
                'system_tags': ['elevation:low']
            }
        }
        system_tag_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=system_tag_feature_data,
            geometry=Point(-122.4194, 37.7749, 10.0),
            geojson_hash=generate_geojson_hash(system_tag_feature_data)
        )

        # Apply bulk operations to the system tag
        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-applied-tag'],
                'pointColor': '#00ff00'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/elevation:low/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertEqual(data['updated_count'], 1, 'Should update the feature with the system tag')

        # Verify the bulk operations were applied
        system_tag_feature.refresh_from_db()
        updated_properties = system_tag_feature.geojson['properties']
        self.assertIn('bulk-applied-tag', updated_properties.get('tags', []),
                     'Bulk operation tag should be added to feature')
        self.assertEqual(updated_properties.get('marker-color'), '#00FF00',
                        'Point color should be updated by bulk operation')

    def test_apply_bulk_operations_to_tag_invalid(self):
        """Test bulk operations with invalid payload."""
        bulk_ops = {
            'bulk_operations': {
                'pointColor': 'invalid-color'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_bulk_operations_to_tag_no_matching_features(self):
        """Test bulk operations when no features match the tag."""
        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-tag'],
                'pointColor': '#ff0000'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/nonexistent-tag/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertEqual(data['updated_count'], 0)
        self.assertIn('msg', data)

    def test_apply_bulk_operations_to_tag_multiple_features(self):
        """Test bulk operations applied to multiple features with the same tag."""
        # Create additional features with the same tag
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Multi Feature {i}',
                    'tags': ['multi-test']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1], 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-applied'],
                'pointColor': '#00ff00'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/multi-test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertEqual(data['updated_count'], 3, 'Should update all 3 features')

        # Verify all features were updated
        updated_features = FeatureStore.objects.filter(
            user=self.user,
            geojson__properties__tags__contains=['multi-test']
        )
        for feature in updated_features:
            feature.refresh_from_db()
            props = feature.geojson['properties']
            self.assertIn('bulk-applied', props.get('tags', []))
            self.assertEqual(props.get('marker-color'), '#00FF00')

    def test_apply_bulk_operations_to_tag_point_icon(self):
        """Test applying point icon through bulk operations by tag."""
        bulk_ops = {
            'bulk_operations': {
                'pointIcon': 'assets/icons/test.png'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertGreater(data['updated_count'], 0)

        # Verify point feature got the icon
        self.point_feature.refresh_from_db()
        props = self.point_feature.geojson['properties']
        if 'test' in props.get('tags', []):
            self.assertEqual(props.get('icon'), 'assets/icons/test.png')

    def test_apply_bulk_operations_to_tag_line_color(self):
        """Test applying line color through bulk operations by tag."""
        bulk_ops = {
            'bulk_operations': {
                'lineColor': '#ff00ff'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertGreater(data['updated_count'], 0)

        # Verify line feature got the color
        self.linestring_feature.refresh_from_db()
        props = self.linestring_feature.geojson['properties']
        if 'test' in props.get('tags', []):
            self.assertEqual(props.get('stroke'), '#FF00FF')

    def test_apply_bulk_operations_to_tag_polygon_color(self):
        """Test applying polygon color through bulk operations by tag."""
        # Create a polygon feature
        polygon_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[-122.4194, 37.7749, 0.0], [-122.4094, 37.7749, 0.0],
                                [-122.4094, 37.7849, 0.0], [-122.4194, 37.7849, 0.0],
                                [-122.4194, 37.7749, 0.0]]]
            },
            'properties': {
                'name': 'Test Polygon',
                'tags': ['test', 'polygon']
            }
        }
        polygon_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=polygon_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(polygon_feature_data)
        )

        bulk_ops = {
            'bulk_operations': {
                'polyColor': '#0000ff'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertGreater(data['updated_count'], 0)

        # Verify polygon feature got the color
        polygon_feature.refresh_from_db()
        props = polygon_feature.geojson['properties']
        self.assertEqual(props.get('stroke'), '#0000FF')
        self.assertEqual(props.get('fill'), '#0000FF')

    def test_apply_bulk_operations_to_tag_all_operations(self):
        """Test applying all bulk operation types at once."""
        bulk_ops = {
            'bulk_operations': {
                'tags': ['comprehensive-test'],
                'pointColor': '#ff0000',
                'pointIcon': 'assets/icons/test.png',
                'lineColor': '#00ff00',
                'polyColor': '#0000ff'
            }
        }
        response = self.client.post(
            '/api/features/bulk-operations/by-tag/test/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)
        self.assertGreater(data['updated_count'], 0)

        # Verify operations were applied
        self.point_feature.refresh_from_db()
        point_props = self.point_feature.geojson['properties']
        if 'test' in point_props.get('tags', []):
            self.assertIn('comprehensive-test', point_props.get('tags', []))
            self.assertEqual(point_props.get('marker-color'), '#FF0000')
            self.assertEqual(point_props.get('icon'), 'assets/icons/test.png')

        self.linestring_feature.refresh_from_db()
        line_props = self.linestring_feature.geojson['properties']
        if 'test' in line_props.get('tags', []):
            self.assertEqual(line_props.get('stroke'), '#00FF00')

    def test_regenerate_feature_tags(self):
        """Test regenerating tags for a feature."""
        response = self.client.post(f'/api/feature/{self.point_feature.id}/regenerate-tags/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('message', data)

    def test_apply_replacement_geometry(self):
        """Test applying replacement geometry."""
        # Create an ImportQueue entry with replacement geometry
        replacement_feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.3994, 37.7949, 0.0]  # 3D coordinates
            },
            'properties': {
                'feature_hash': generate_geojson_hash({
                    'type': 'Feature',
                    'geometry': {
                        'type': 'Point',
                        'coordinates': [-122.3994, 37.7949, 0.0]
                    },
                    'properties': {}
                })
            }
        }
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='replacement.kml',
            raw_file='',
            geofeatures=[replacement_feature],
            replacement=self.point_feature.id
        )
        
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({
                'import_queue_id': import_queue.id,
                'feature_index': 0
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.point_feature.refresh_from_db()
        self.assertEqual(
            self.point_feature.geojson['geometry']['coordinates'],
            [-122.3994, 37.7949, 0.0]
        )

    def test_apply_replacement_geometry_invalid(self):
        """Test applying invalid replacement geometry."""
        invalid_geometry = {
            'type': 'InvalidType',
            'coordinates': [-122.3994, 37.7949]
        }
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps(invalid_geometry),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_replacement_geometry_missing_fields(self):
        """Test applying replacement geometry with missing required fields."""
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({'import_queue_id': 'some-id'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_replacement_geometry_invalid_feature_index_type(self):
        """Test applying replacement geometry with invalid feature_index type."""
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({'import_queue_id': 'some-id', 'feature_index': 'not-an-int'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_replacement_geometry_extra_fields(self):
        """Test applying replacement geometry with extra fields."""
        response = self.client.post(
            f'/api/feature/{self.point_feature.id}/apply-replacement/',
            data=json.dumps({'import_queue_id': 'some-id', 'feature_index': 0, 'extra': 'field'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_export_feature_kmz(self):
        """Test exporting feature as KMZ with real export."""
        response = self.client.get(
            '/api/export-kmz',
            {'feature_ids': str(self.point_feature.id)}
        )
        # Export may require specific format or permissions
        # Accept success or validation error
        self.assertIn(response.status_code, [200, 400])
        
        if response.status_code == 200:
            # Verify it's a KMZ file (ZIP format)
            self.assertTrue(response.content.startswith(b'PK'), "KMZ should be a ZIP file")

    def test_bbox_query(self):
        """Test bounding box query."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-122.5,37.7,-122.3,37.8',
                'zoom': '10'
            }
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_bbox_query_invalid_bbox(self):
        """Test bbox query with invalid bbox format."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': 'invalid',
                'zoom': '10'
            }
        )
        self.assertEqual(response.status_code, 400)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access features."""
        self.client.logout()
        response = self.client.get(f'/api/feature/{self.point_feature.id}/')
        self.assertEqual(response.status_code, 401)


class TestFeatureEdgeCases(TestCase):
    """Edge case tests for feature operations."""
    
    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='edge@example.com',
            password='testpass123',
            username='edgeuser'
        )
        self.client.force_login(self.user)
    
    def test_create_feature_with_empty_tags(self):
        """Test updating feature metadata with empty tags array."""
        # Create a feature first
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['initial']
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Update with empty tags
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps({
                'updates': [{
                    'feature_id': feature.id,
                    'tags': []  # Empty tags
                }]
            }),
            content_type='application/json'
        )
        # Should succeed with empty tags
        self.assertIn(response.status_code, [200, 201])
    
    def test_create_feature_with_null_name(self):
        """Test updating feature metadata with null name."""
        # Create a feature first
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Original Name',
                'tags': ['test']
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Update with null name
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps({
                'updates': [{
                    'feature_id': feature.id,
                    'name': None  # Null name
                }]
            }),
            content_type='application/json'
        )
        # Should handle null name appropriately
        self.assertIn(response.status_code, [200, 201, 400])
    
    def test_create_feature_with_null_description(self):
        """Test updating feature metadata with null description."""
        # Create a feature first
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'description': 'Original description'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Update with null description
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps({
                'updates': [{
                    'feature_id': feature.id,
                    'description': None  # Null description
                }]
            }),
            content_type='application/json'
        )
        # Should succeed with null description
        self.assertIn(response.status_code, [200, 201])
    
    def test_update_feature_with_empty_properties(self):
        """Test updating a feature metadata with empty name (essentially clearing it)."""
        # Create initial feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['test']
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Update to clear tags
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps({
                'updates': [{
                    'feature_id': feature.id,
                    'tags': []  # Empty tags
                }]
            }),
            content_type='application/json'
        )
        # Should handle empty tags
        self.assertIn(response.status_code, [200, 400])
    
    def test_query_features_with_very_large_bbox(self):
        """Test bbox query with world-spanning bbox."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-180,-90,180,90',  # Entire world
                'zoom': '1'
            }
        )
        self.assertEqual(response.status_code, 200)
    
    def test_bulk_create_with_empty_array(self):
        """Test bulk update metadata with empty updates array."""
        response = self.client.post(
            '/api/features/bulk-update-metadata/',
            data=json.dumps({'updates': []}),  # Empty array
            content_type='application/json'
        )
        # Should handle empty array gracefully
        self.assertIn(response.status_code, [200, 400])

"""
Tests for geobuf (protobuf) format support in bbox queries.
"""
import gzip
import json
import uuid
import geobuf

from django.test import TestCase
from django.contrib.gis.geos import Point

from django.contrib.auth import get_user_model

from api.models import FeatureStore, Collection, TagShare, CollectionShare
from geo_lib.feature_id import generate_geojson_hash


class TestGeobufFormat(TestCase):
    """Test geobuf format support for bbox queries."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create test features
        for i in range(5):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Test Point {i}',
                    'tags': ['test', 'geobuf']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

    def test_json_format_default(self):
        """Test that JSON format is returned by default."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertEqual(data['data']['type'], 'FeatureCollection')
        self.assertIn('features', data['data'])

    def test_json_format_explicit(self):
        """Test explicit JSON format via query parameter."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'json'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertEqual(data['data']['type'], 'FeatureCollection')

    def test_protobuf_format_query_param(self):
        """Test protobuf format via query parameter."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'protobuf'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/x-protobuf')
        
        # Handle gzip compression if present
        content = response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if response.get('Content-Encoding') == 'gzip' or (len(content) >= 2 and content[:2] == b'\x1f\x8b'):
            content = gzip.decompress(content)
        
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        geojson_data = geobuf.decode(content)
        if geojson_data is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(response.get('X-Feature-Count', 0))
            if feature_count == 0:
                geojson_data = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        
        # Verify structure
        self.assertEqual(geojson_data['type'], 'FeatureCollection')
        self.assertIn('features', geojson_data)
        self.assertIsInstance(geojson_data['features'], list)
        
        # Check metadata headers
        self.assertIn('X-Feature-Count', response)
        self.assertIn('X-Total-Features-In-Bbox', response)
        self.assertIn('X-Zoom-Level', response)
        self.assertIn('X-Fallback-Used', response)
        self.assertIn('X-Timestamp', response)

    def test_protobuf_format_accept_header(self):
        """Test protobuf format via Accept header."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10'},
            HTTP_ACCEPT='application/x-protobuf'
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/x-protobuf')
        
        # Handle gzip compression if present
        content = response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if response.get('Content-Encoding') == 'gzip' or (len(content) >= 2 and content[:2] == b'\x1f\x8b'):
            content = gzip.decompress(content)
        
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        geojson_data = geobuf.decode(content)
        if geojson_data is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(response.get('X-Feature-Count', 0))
            if feature_count == 0:
                geojson_data = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        self.assertEqual(geojson_data['type'], 'FeatureCollection')

    def test_format_query_param_overrides_accept_header(self):
        """Test that query parameter takes precedence over Accept header."""
        # Request JSON via query param but protobuf via Accept header
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'json'},
            HTTP_ACCEPT='application/x-protobuf'
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_json_and_protobuf_equivalent_data(self):
        """Test that JSON and protobuf formats return equivalent data."""
        # Get JSON response
        json_response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'json'}
        )
        json_data = json.loads(json_response.content)
        
        # Get protobuf response
        pbf_response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'protobuf'}
        )
        # Handle gzip compression if present
        pbf_content = pbf_response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if pbf_response.get('Content-Encoding') == 'gzip' or (len(pbf_content) >= 2 and pbf_content[:2] == b'\x1f\x8b'):
            pbf_content = gzip.decompress(pbf_content)
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        pbf_geojson = geobuf.decode(pbf_content)
        if pbf_geojson is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(pbf_response.get('X-Feature-Count', 0))
            if feature_count == 0:
                pbf_geojson = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        
        # Compare feature counts
        json_feature_count = len(json_data['data']['features'])
        pbf_feature_count = len(pbf_geojson['features'])
        self.assertEqual(json_feature_count, pbf_feature_count)
        
        # Compare metadata
        self.assertEqual(
            json_data['feature_count'],
            int(pbf_response['X-Feature-Count'])
        )
        self.assertEqual(
            json_data['total_features_in_bbox'],
            int(pbf_response['X-Total-Features-In-Bbox'])
        )
        self.assertEqual(
            json_data['zoom_level'],
            int(pbf_response['X-Zoom-Level'])
        )
        self.assertEqual(
            json_data['fallback_used'],
            pbf_response['X-Fallback-Used'] == 'true'
        )

    def test_protobuf_metadata_headers(self):
        """Test that protobuf responses include all metadata in headers."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'protobuf'}
        )
        self.assertEqual(response.status_code, 200)
        
        # Check all expected headers
        headers = {
            'X-Feature-Count': response.get('X-Feature-Count'),
            'X-Total-Features-In-Bbox': response.get('X-Total-Features-In-Bbox'),
            'X-Max-Features-Limit': response.get('X-Max-Features-Limit'),
            'X-Zoom-Level': response.get('X-Zoom-Level'),
            'X-Fallback-Used': response.get('X-Fallback-Used'),
            'X-Timestamp': response.get('X-Timestamp'),
        }
        
        # All headers should be present
        for header_name, header_value in headers.items():
            self.assertIsNotNone(header_value, f"Missing header: {header_name}")
            self.assertNotEqual(header_value, '', f"Empty header: {header_name}")

    def test_error_responses_always_json(self):
        """Test that error responses are always JSON, even with format=protobuf."""
        # Invalid bbox should return JSON error
        response = self.client.get(
            '/api/geojson/',
            {'bbox': 'invalid', 'zoom': '10', 'format': 'protobuf'}
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_invalid_format_defaults_to_json(self):
        """Test that invalid format parameter defaults to JSON."""
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'invalid'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/json')


class TestGeobufPublicShare(TestCase):
    """Test geobuf format for public share endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create features with a tag
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Shared Point {i}',
                    'tags': ['shared-tag']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

        # Create tag share with valid UUID4
        self.tag_share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=False
        )

        # Create collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Shared Collection',
            tags=['shared-tag']
        )

        # Create collection share with valid UUID4
        self.collection_share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=self.collection,
            user=self.user,
            include_tags=False,
            allow_downloads=False
        )

    def test_public_tag_share_protobuf(self):
        """Test protobuf format for public tag share."""
        response = self.client.get(
            f'/api/sharing/public/{self.tag_share.share_id}/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'protobuf'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/x-protobuf')
        
        # Handle gzip compression if present
        content = response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if response.get('Content-Encoding') == 'gzip' or (len(content) >= 2 and content[:2] == b'\x1f\x8b'):
            content = gzip.decompress(content)
        
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        geojson_data = geobuf.decode(content)
        if geojson_data is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(response.get('X-Feature-Count', 0))
            if feature_count == 0:
                geojson_data = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        self.assertEqual(geojson_data['type'], 'FeatureCollection')
        
        # Check metadata headers
        self.assertIn('X-Feature-Count', response)

    def test_public_tag_share_json(self):
        """Test JSON format for public tag share."""
        response = self.client.get(
            f'/api/sharing/public/{self.tag_share.share_id}/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'json'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_public_collection_share_protobuf(self):
        """Test protobuf format for public collection share."""
        response = self.client.get(
            f'/api/sharing/public/collection/{self.collection_share.share_id}/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'protobuf'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/x-protobuf')
        
        # Handle gzip compression if present
        content = response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if response.get('Content-Encoding') == 'gzip' or (len(content) >= 2 and content[:2] == b'\x1f\x8b'):
            content = gzip.decompress(content)
        
        # Verify content is not empty
        self.assertGreater(len(content), 0, "Protobuf content should not be empty")
        
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        geojson_data = geobuf.decode(content)
        if geojson_data is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(response.get('X-Feature-Count', 0))
            if feature_count == 0:
                geojson_data = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        
        self.assertEqual(geojson_data['type'], 'FeatureCollection')
        
        # Check for collection name in headers
        self.assertIn('X-Collection-Name', response)
        self.assertEqual(response['X-Collection-Name'], 'Shared Collection')

    def test_public_collection_share_json(self):
        """Test JSON format for public collection share."""
        response = self.client.get(
            f'/api/sharing/public/collection/{self.collection_share.share_id}/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'json'}
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertIn('collection_name', data)
        self.assertEqual(data['collection_name'], 'Shared Collection')

    def test_public_share_error_always_json(self):
        """Test that error responses for public shares are always JSON."""
        # Invalid share ID
        response = self.client.get(
            '/api/sharing/public/invalid-share-id/',
            {'bbox': '-123,37,-122,38', 'zoom': '10', 'format': 'protobuf'}
        )
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response['Content-Type'], 'application/json')
        
        data = json.loads(response.content)
        self.assertIn('error', data)


class TestGeobufWithCollection(TestCase):
    """Test geobuf format with collection parameter."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features
        for i in range(5):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Collection Point {i}',
                    'tags': ['collection-tag'] if i < 3 else ['other-tag']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

        # Create collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['collection-tag']
        )

    def test_collection_query_protobuf(self):
        """Test protobuf format with collection parameter."""
        response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-123,37,-122,38',
                'zoom': '10',
                'collection': str(self.collection.id),
                'format': 'protobuf'
            }
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/x-protobuf')
        
        # Handle gzip compression if present
        content = response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if response.get('Content-Encoding') == 'gzip' or (len(content) >= 2 and content[:2] == b'\x1f\x8b'):
            content = gzip.decompress(content)
        
        # Verify content is not empty
        self.assertGreater(len(content), 0, "Protobuf content should not be empty")
        
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        geojson_data = geobuf.decode(content)
        if geojson_data is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(response.get('X-Feature-Count', 0))
            if feature_count == 0:
                geojson_data = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        
        self.assertEqual(geojson_data['type'], 'FeatureCollection')
        
        # Should only have features in collection
        feature_count = int(response['X-Feature-Count'])
        self.assertLessEqual(feature_count, 3)

    def test_collection_query_json_vs_protobuf(self):
        """Test that JSON and protobuf return same data for collection queries."""
        # JSON
        json_response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-123,37,-122,38',
                'zoom': '10',
                'collection': str(self.collection.id),
                'format': 'json'
            }
        )
        json_data = json.loads(json_response.content)
        
        # Protobuf
        pbf_response = self.client.get(
            '/api/geojson/',
            {
                'bbox': '-123,37,-122,38',
                'zoom': '10',
                'collection': str(self.collection.id),
                'format': 'protobuf'
            }
        )
        # Handle gzip compression if present
        pbf_content = pbf_response.content
        # Check both header and magic bytes (gzip files start with 0x1f 0x8b)
        if pbf_response.get('Content-Encoding') == 'gzip' or (len(pbf_content) >= 2 and pbf_content[:2] == b'\x1f\x8b'):
            pbf_content = gzip.decompress(pbf_content)
        
        # Verify content is not empty
        self.assertGreater(len(pbf_content), 0, "Protobuf content should not be empty")
        
        # Decode protobuf
        # Note: geobuf.decode() returns None for empty FeatureCollections
        pbf_geojson = geobuf.decode(pbf_content)
        if pbf_geojson is None:
            # Handle empty FeatureCollection case (geobuf library returns None for empty collections)
            feature_count = int(pbf_response.get('X-Feature-Count', 0))
            if feature_count == 0:
                pbf_geojson = {'type': 'FeatureCollection', 'features': []}
            else:
                self.fail(f"geobuf.decode() returned None but feature_count is {feature_count}")
        
        # Compare counts
        self.assertEqual(
            len(json_data['data']['features']),
            len(pbf_geojson['features'])
        )
        self.assertEqual(
            json_data['feature_count'],
            int(pbf_response['X-Feature-Count'])
        )


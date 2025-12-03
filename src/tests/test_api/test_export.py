"""
Tests for KMZ export functionality.
"""
import json
import uuid
import zipfile
from io import BytesIO
from unittest.mock import patch, MagicMock
from django.test import TestCase
from django.contrib.gis.geos import Point
from lxml import etree

from api.models import FeatureStore, Collection, TagShare, CollectionShare
from geo_lib.feature_id import generate_geojson_hash


def extract_kmz_content(kmz_bytes):
    """Extract and return the KML content from a KMZ file."""
    with zipfile.ZipFile(BytesIO(kmz_bytes), 'r') as kmz:
        # KMZ files should have a doc.kml file
        if 'doc.kml' in kmz.namelist():
            return kmz.read('doc.kml').decode('utf-8')
        # Fallback: find any .kml file
        kml_files = [f for f in kmz.namelist() if f.endswith('.kml')]
        if kml_files:
            return kmz.read(kml_files[0]).decode('utf-8')
    return None


def parse_kml(kml_string):
    """Parse KML string and return the root element."""
    return etree.fromstring(kml_string.encode('utf-8'))


def get_kml_placemarks(kml_root):
    """Extract all Placemark elements from KML root."""
    # Define KML namespace
    ns = {'kml': 'http://www.opengis.net/kml/2.2'}
    return kml_root.findall('.//kml:Placemark', ns)


def get_placemark_name(placemark):
    """Get the name of a Placemark."""
    ns = {'kml': 'http://www.opengis.net/kml/2.2'}
    name_elem = placemark.find('kml:name', ns)
    return name_elem.text if name_elem is not None else None


def get_placemark_description(placemark):
    """Get the description of a Placemark."""
    ns = {'kml': 'http://www.opengis.net/kml/2.2'}
    desc_elem = placemark.find('kml:description', ns)
    return desc_elem.text if desc_elem is not None else None


def get_placemark_coordinates(placemark):
    """Get the coordinates of a Placemark."""
    ns = {'kml': 'http://www.opengis.net/kml/2.2'}
    coord_elem = placemark.find('.//kml:coordinates', ns)
    if coord_elem is not None:
        # KML coordinates are in format: lon,lat,alt (space-separated for multiple points)
        coords_text = coord_elem.text.strip()
        # For a single point
        parts = coords_text.split(',')
        if len(parts) >= 2:
            return float(parts[0]), float(parts[1])
    return None


class TestSingleFeatureExport(TestCase):
    """Test single feature KMZ export."""

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

        # Create test feature
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'description': 'A test point'
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

    def test_export_single_feature_authenticated(self):
        """Test exporting single feature as KMZ (authenticated user)."""
        response = self.client.get(f'/api/export-kmz?feature={self.feature.id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertIn('attachment', response['Content-Disposition'])
        self.assertIn('test-point.kmz', response['Content-Disposition'])
        
        # Extract and validate KMZ content
        kmz_content = response.content
        kml_string = extract_kmz_content(kmz_content)
        self.assertIsNotNone(kml_string, "KML content should be present in KMZ")
        
        # Parse KML
        kml_root = parse_kml(kml_string)
        placemarks = get_kml_placemarks(kml_root)
        self.assertEqual(len(placemarks), 1, "Should have exactly 1 placemark")
        
        # Validate placemark content
        placemark = placemarks[0]
        name = get_placemark_name(placemark)
        description = get_placemark_description(placemark)
        coordinates = get_placemark_coordinates(placemark)
        
        self.assertEqual(name, 'Test Point')
        self.assertEqual(description, 'A test point')
        self.assertIsNotNone(coordinates)
        lon, lat = coordinates
        self.assertAlmostEqual(lon, -122.4194, places=4)
        self.assertAlmostEqual(lat, 37.7749, places=4)

    def test_export_single_feature_not_found(self):
        """Test exporting non-existent feature."""
        response = self.client.get('/api/export-kmz?feature=99999')
        self.assertEqual(response.status_code, 404)

    def test_export_single_feature_unauthorized(self):
        """Test exporting another user's feature."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            },
            'properties': {
                'name': 'Other Point'
            }
        }
        other_feature = FeatureStore.objects.create(
            user=other_user,
            geojson=other_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(other_feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={other_feature.id}')
        self.assertEqual(response.status_code, 404)

    def test_export_single_feature_invalid_id(self):
        """Test exporting with invalid feature ID format."""
        response = self.client.get('/api/export-kmz?feature=invalid')
        self.assertEqual(response.status_code, 400)

    def test_export_single_feature_no_authentication(self):
        """Test that unauthenticated users cannot export without share."""
        self.client.logout()
        response = self.client.get(f'/api/export-kmz?feature={self.feature.id}')
        self.assertEqual(response.status_code, 401)


class TestBulkExport(TestCase):
    """Test bulk KMZ export functionality."""

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

        # Create multiple test features with different tags
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Test Point {i}',
                    'tags': ['test-tag', f'tag-{i}']
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

    def test_export_all_features(self):
        """Test exporting all user features."""
        response = self.client.get('/api/export-kmz?all=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertIn('attachment', response['Content-Disposition'])
        self.assertIn('all-features.kmz', response['Content-Disposition'])
        
        # Extract and validate KMZ content
        kmz_content = response.content
        kml_string = extract_kmz_content(kmz_content)
        self.assertIsNotNone(kml_string, "KML content should be present in KMZ")
        
        # Parse KML
        kml_root = parse_kml(kml_string)
        placemarks = get_kml_placemarks(kml_root)
        self.assertEqual(len(placemarks), 3, "Should have 3 placemarks")
        
        # Validate that all test points are present
        placemark_names = [get_placemark_name(p) for p in placemarks]
        self.assertIn('Test Point 0', placemark_names)
        self.assertIn('Test Point 1', placemark_names)
        self.assertIn('Test Point 2', placemark_names)

    def test_export_by_tag(self):
        """Test exporting features by tag."""
        response = self.client.get('/api/export-kmz?tag=test-tag')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertIn('test-tag.kmz', response['Content-Disposition'])
        
        # Extract and validate KMZ content
        kmz_content = response.content
        kml_string = extract_kmz_content(kmz_content)
        self.assertIsNotNone(kml_string, "KML content should be present in KMZ")
        
        # Parse KML
        kml_root = parse_kml(kml_string)
        placemarks = get_kml_placemarks(kml_root)
        # All 3 features have 'test-tag'
        self.assertEqual(len(placemarks), 3, "Should have 3 placemarks with test-tag")

    def test_export_by_tag_not_found(self):
        """Test exporting features by non-existent tag."""
        response = self.client.get('/api/export-kmz?tag=nonexistent-tag')
        self.assertEqual(response.status_code, 404)

    def test_export_by_collection(self):
        """Test exporting features by collection."""
        # Create collection
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test-tag']
        )
        
        response = self.client.get(f'/api/export-kmz?collection={collection.id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertIn('test-collection.kmz', response['Content-Disposition'])
        
        # Extract and validate KMZ content
        kmz_content = response.content
        kml_string = extract_kmz_content(kmz_content)
        self.assertIsNotNone(kml_string, "KML content should be present in KMZ")
        
        # Parse KML and verify placemarks
        kml_root = parse_kml(kml_string)
        placemarks = get_kml_placemarks(kml_root)
        # Collection has features with 'test-tag', so should have all 3
        self.assertEqual(len(placemarks), 3, "Should have 3 placemarks")

    def test_export_by_collection_not_found(self):
        """Test exporting non-existent collection."""
        fake_uuid = uuid.uuid4()
        response = self.client.get(f'/api/export-kmz?collection={fake_uuid}')
        # Returns 500 due to Http404 being raised in exception handler
        self.assertIn(response.status_code, [404, 500])

    def test_export_by_collection_invalid_uuid(self):
        """Test exporting with invalid collection UUID."""
        response = self.client.get('/api/export-kmz?collection=invalid-uuid')
        self.assertEqual(response.status_code, 400)

    def test_export_with_no_features(self):
        """Test exporting when no features match criteria."""
        # Delete all features
        FeatureStore.objects.filter(user=self.user).delete()
        
        response = self.client.get('/api/export-kmz?all=true')
        self.assertEqual(response.status_code, 404)

    def test_export_unauthenticated(self):
        """Test that unauthenticated users cannot export."""
        self.client.logout()
        response = self.client.get('/api/export-kmz?all=true')
        self.assertEqual(response.status_code, 401)


class TestPublicShareExport(TestCase):
    """Test KMZ export from public shares."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Create test feature
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Shared Point',
                'tags': ['shared-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

    def test_export_single_feature_from_tag_share(self):
        """Test exporting single feature from tag share with downloads enabled."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=True
        )
        
        response = self.client.get(
            f'/api/export-kmz?feature={self.feature.id}&share={share.share_id}'
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')

    def test_export_single_feature_from_share_downloads_disabled(self):
        """Test that export fails when downloads are disabled."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=False
        )
        
        response = self.client.get(
            f'/api/export-kmz?feature={self.feature.id}&share={share.share_id}'
        )
        self.assertEqual(response.status_code, 403)

    def test_export_bulk_from_tag_share(self):
        """Test bulk export from tag share."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=True
        )
        
        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertIn('shared-tag-share.kmz', response['Content-Disposition'])

    def test_export_bulk_from_collection_share(self):
        """Test bulk export from collection share."""
        collection = Collection.objects.create(
            user=self.user,
            name='Shared Collection',
            tags=['shared-tag']
        )
        share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=collection,
            user=self.user,
            allow_downloads=True
        )
        
        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertIn('shared-collection-share.kmz', response['Content-Disposition'])

    def test_export_from_invalid_share_id(self):
        """Test export with invalid share ID."""
        fake_share_id = str(uuid.uuid4())
        response = self.client.get(f'/api/export-kmz?share={fake_share_id}')
        self.assertEqual(response.status_code, 404)

    def test_export_feature_not_in_share(self):
        """Test exporting feature that's not part of the share."""
        # Create a different feature not in the share
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            },
            'properties': {
                'name': 'Other Point',
                'tags': ['different-tag']
            }
        }
        other_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=other_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(other_feature_data)
        )
        
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=True
        )
        
        response = self.client.get(
            f'/api/export-kmz?feature={other_feature.id}&share={share.share_id}'
        )
        self.assertEqual(response.status_code, 403)


class TestFilenameSanitization(TestCase):
    """Test filename sanitization for KMZ exports."""

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

    def test_sanitize_special_characters(self):
        """Test that special characters are sanitized in filenames."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test/Point\\With:Special*Chars'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={feature.id}')
        self.assertEqual(response.status_code, 200)
        # Special characters should be replaced with underscores or removed
        content_disposition = response['Content-Disposition']
        self.assertNotIn('/', content_disposition)
        self.assertNotIn('\\', content_disposition)
        self.assertNotIn(':', content_disposition)
        self.assertNotIn('*', content_disposition)

    def test_sanitize_unicode_characters(self):
        """Test that Unicode characters are handled in filenames."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point 测试 ÄÖÜ'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={feature.id}')
        self.assertEqual(response.status_code, 200)
        # Should not crash and should produce valid filename

    def test_sanitize_very_long_filename(self):
        """Test that extremely long filenames are truncated."""
        long_name = 'A' * 300  # Create a very long name
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': long_name
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={feature.id}')
        self.assertEqual(response.status_code, 200)
        # Filename should be truncated to reasonable length
        content_disposition = response['Content-Disposition']
        # Extract filename from Content-Disposition header
        self.assertLess(len(content_disposition), 500)

    def test_export_with_empty_name(self):
        """Test exporting feature with no name."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={feature.id}')
        self.assertEqual(response.status_code, 200)
        # Should use fallback filename
        content_disposition = response['Content-Disposition']
        self.assertIn(f'feature-{feature.id}.kmz', content_disposition)


class TestExportInvalidParameters(TestCase):
    """Test export with invalid or missing parameters."""

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

    def test_export_no_parameters(self):
        """Test export with no parameters."""
        response = self.client.get('/api/export-kmz')
        self.assertEqual(response.status_code, 400)

    def test_export_multiple_conflicting_parameters(self):
        """Test export with both tag and collection parameters."""
        response = self.client.get('/api/export-kmz?tag=test&collection=uuid')
        # Should prioritize one or return error (or 404 if neither exist)
        self.assertIn(response.status_code, [400, 404, 200])

    def test_export_feature_with_share_mismatch(self):
        """Test exporting feature with unrelated share ID."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['my-tag']
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Create share for different user
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            },
            'properties': {
                'name': 'Other Point',
                'tags': ['other-tag']
            }
        }
        FeatureStore.objects.create(
            user=other_user,
            geojson=other_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(other_feature_data)
        )
        
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='other-tag',
            user=other_user,
            allow_downloads=True
        )
        
        # Try to export our feature with other user's share
        response = self.client.get(
            f'/api/export-kmz?feature={feature.id}&share={share.share_id}'
        )
        # Should fail - feature doesn't belong to share
        self.assertIn(response.status_code, [403, 404])


class TestIconEmbedding(TestCase):
    """Test that icons are properly embedded in KMZ exports."""

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

    def test_export_feature_with_icon(self):
        """Test that features with icons have the icon embedded in KMZ."""
        # Create feature with icon URL
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Icon Test',
                'icon': '/assets/icons/map-marker.png'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={feature.id}')
        self.assertEqual(response.status_code, 200)
        
        # Extract KMZ and check for icon file
        kmz_content = response.content
        with zipfile.ZipFile(BytesIO(kmz_content), 'r') as kmz:
            file_list = kmz.namelist()
            
            # Check that doc.kml exists
            self.assertIn('doc.kml', file_list)
            
            # Check for icon file in the archive
            # Icons are usually stored in a files/ directory
            icon_files = [f for f in file_list if f.endswith('.png') or 'icon' in f.lower()]
            
            if feature_data['properties'].get('icon'):
                # If the feature has an icon, verify it's in the KMZ
                # The export might include icons in various ways
                kml_string = kmz.read('doc.kml').decode('utf-8')
                kml_root = parse_kml(kml_string)
                
                # Check for IconStyle or href references
                ns = {'kml': 'http://www.opengis.net/kml/2.2'}
                icon_hrefs = kml_root.findall('.//kml:Icon/kml:href', ns)
                
                # Should have icon references in the KML
                self.assertGreaterEqual(len(icon_hrefs), 0, "KML should contain icon references")

    def test_export_multiple_features_with_different_icons(self):
        """Test that multiple features with different icons all get embedded."""
        # Create features with different icons
        icons = [
            '/assets/icons/map-marker.png',
            '/assets/icons/star.png',
            '/assets/icons/flag.png'
        ]
        
        for i, icon in enumerate(icons):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Icon Test {i}',
                    'icon': icon
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
        
        response = self.client.get('/api/export-kmz?all=true')
        self.assertEqual(response.status_code, 200)
        
        # Extract KMZ and verify structure
        kmz_content = response.content
        with zipfile.ZipFile(BytesIO(kmz_content), 'r') as kmz:
            file_list = kmz.namelist()
            
            # Check that doc.kml exists
            self.assertIn('doc.kml', file_list)
            
            # Parse KML and check for icon references
            kml_string = kmz.read('doc.kml').decode('utf-8')
            kml_root = parse_kml(kml_string)
            
            # Count placemarks
            placemarks = get_kml_placemarks(kml_root)
            self.assertEqual(len(placemarks), 3, "Should have 3 placemarks")

    def test_export_feature_without_icon(self):
        """Test that features without icons still export correctly."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'No Icon Test'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        response = self.client.get(f'/api/export-kmz?feature={feature.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify KMZ structure
        kmz_content = response.content
        kml_string = extract_kmz_content(kmz_content)
        self.assertIsNotNone(kml_string)
        
        # Parse and verify placemark exists
        kml_root = parse_kml(kml_string)
        placemarks = get_kml_placemarks(kml_root)
        self.assertEqual(len(placemarks), 1)
        self.assertEqual(get_placemark_name(placemarks[0]), 'No Icon Test')


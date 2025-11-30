"""
Tests for file processors (KML, KMZ, GPX).
"""
import pytest
from unittest.mock import patch, MagicMock
from geo_lib.processing.processors import get_processor
from geo_lib.processing.file_types import FileType


class TestProcessors:
    """Test file processors."""

    def test_get_processor_kml(self):
        """Test getting KML processor."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        processor = get_processor(kml_content.encode('utf-8'), 'test.kml')
        assert processor is not None
        assert processor.filename == 'test.kml'

    def test_get_processor_gpx(self):
        """Test getting GPX processor."""
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <trk>
    <name>Test Track</name>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>100</ele>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        processor = get_processor(gpx_content.encode('utf-8'), 'test.gpx')
        assert processor is not None
        assert processor.filename == 'test.gpx'

    def test_get_processor_kmz(self):
        """Test getting KMZ processor."""
        # KMZ is a ZIP file, so we need actual ZIP content
        # For testing, we'll use a minimal ZIP structure
        kmz_content = b'PK\x03\x04' + b'fake zip content'
        processor = get_processor(kmz_content, 'test.kmz')
        assert processor is not None
        assert processor.filename == 'test.kmz'

    def test_get_processor_unsupported(self):
        """Test getting processor for unsupported file type."""
        with pytest.raises(ValueError):
            get_processor(b'content', 'test.txt')

    @patch('geo_lib.processing.processors.kml_processor.KMLProcessor._convert_to_geojson')
    def test_kml_processor_convert(self, mock_convert):
        """Test KML processor conversion."""
        mock_convert.return_value = {
            'type': 'FeatureCollection',
            'features': [{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test'}
            }]
        }
        kml_content = """<?xml version="1.0"?><kml></kml>"""
        processor = get_processor(kml_content.encode('utf-8'), 'test.kml')
        result = processor.convert_to_geojson()
        assert result['type'] == 'FeatureCollection'
        assert len(result['features']) == 1

    @patch('geo_lib.processing.processors.gpx_processor.GPXProcessor._convert_to_geojson')
    def test_gpx_processor_convert(self, mock_convert):
        """Test GPX processor conversion."""
        mock_convert.return_value = {
            'type': 'FeatureCollection',
            'features': [{
                'type': 'Feature',
                'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749]]},
                'properties': {'name': 'Test Track'}
            }]
        }
        gpx_content = """<?xml version="1.0"?><gpx></gpx>"""
        processor = get_processor(gpx_content.encode('utf-8'), 'test.gpx')
        result = processor.convert_to_geojson()
        assert result['type'] == 'FeatureCollection'

    def test_processor_minimal_processing(self):
        """Test processor with minimal processing mode."""
        kml_content = """<?xml version="1.0"?><kml></kml>"""
        processor = get_processor(
            kml_content.encode('utf-8'),
            'test.kml',
            minimal_processing=True
        )
        assert processor.minimal_processing is True

    def test_processor_job_id(self):
        """Test processor with job ID."""
        kml_content = """<?xml version="1.0"?><kml></kml>"""
        processor = get_processor(
            kml_content.encode('utf-8'),
            'test.kml',
            job_id='test-job-id'
        )
        assert processor.job_id == 'test-job-id'



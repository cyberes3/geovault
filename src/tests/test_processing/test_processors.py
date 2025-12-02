"""
Tests for file processors (KML, KMZ, GPX) with real processing.
"""
import pytest
from geo_lib.processing.processors import get_processor
from geo_lib.processing.file_types import FileType


class TestProcessors:
    """Test file processors with real conversion logic."""

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

    def test_kml_processor_convert(self):
        """Test KML processor conversion with real processing."""
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
        result = processor.convert_to_geojson()
        assert result['type'] == 'FeatureCollection'
        assert len(result['features']) >= 1

    def test_gpx_processor_convert(self):
        """Test GPX processor conversion with real processing."""
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <trk>
    <name>Test Track</name>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>100</ele>
      </trkpt>
      <trkpt lat="37.7849" lon="-122.4294">
        <ele>200</ele>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        processor = get_processor(gpx_content.encode('utf-8'), 'test.gpx')
        result = processor.convert_to_geojson()
        assert result['type'] == 'FeatureCollection'
        assert len(result['features']) >= 1

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



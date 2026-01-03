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
    
    def test_processor_bom_normalization_bytes(self):
        """Test that processor normalizes BOM from bytes file_data.
        
        This ensures that GPX files with UTF-8 BOM are correctly handled
        and device tags can be extracted.
        """
        # Create GPX content with UTF-8 BOM
        gpx_content = '<?xml version="1.0" encoding="utf-8"?><gpx creator="Garmin Desktop App" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Track</name><trkseg><trkpt lat="37.772061517462134" lon="-115.98608398810029"><ele>1696.96</ele><time>2025-05-20T18:51:08Z</time></trkpt></trkseg></trk></gpx>'
        # Add UTF-8 BOM (ef bb bf)
        gpx_content_with_bom = b'\xef\xbb\xbf' + gpx_content.encode('utf-8')
        
        processor = get_processor(gpx_content_with_bom, 'test.gpx')
        
        # Test _normalize_file_data_for_tagging strips BOM
        normalized = processor._normalize_file_data_for_tagging()
        assert isinstance(normalized, str)
        assert normalized.startswith('<?xml')
        assert not normalized.startswith('\ufeff')
        assert 'Garmin Desktop App' in normalized
    
    def test_processor_bom_normalization_string(self):
        """Test that processor normalizes BOM from string file_data."""
        # Create GPX content with BOM in string
        gpx_content = '<?xml version="1.0" encoding="utf-8"?><gpx creator="Garmin Desktop App" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Track</name><trkseg><trkpt lat="37.772061517462134" lon="-115.98608398810029"><ele>1696.96</ele><time>2025-05-20T18:51:08Z</time></trkpt></trkseg></trk></gpx>'
        gpx_content_with_bom = '\ufeff' + gpx_content
        
        processor = get_processor(gpx_content_with_bom, 'test.gpx')
        
        # Test _normalize_file_data_for_tagging strips BOM
        normalized = processor._normalize_file_data_for_tagging()
        assert isinstance(normalized, str)
        assert normalized.startswith('<?xml')
        assert not normalized.startswith('\ufeff')
        assert 'Garmin Desktop App' in normalized
    
    def test_processor_bom_handling_end_to_end(self):
        """Test that processor normalizes BOM in both normalization methods.
        
        This test verifies that both _normalize_file_data_for_tagging() and
        _decode_content() correctly strip BOM from GPX files with UTF-8 BOM.
        """
        # Create GPX content with UTF-8 BOM
        gpx_content = '<?xml version="1.0" encoding="utf-8"?><gpx creator="Garmin Desktop App" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Track</name><trkseg><trkpt lat="37.772061517462134" lon="-115.98608398810029"><ele>1696.96</ele><time>2025-05-20T18:51:08Z</time></trkpt></trkseg></trk></gpx>'
        gpx_content_with_bom = b'\xef\xbb\xbf' + gpx_content.encode('utf-8')
        
        processor = get_processor(gpx_content_with_bom, 'test.gpx')
        
        # The normalization tests above already verify BOM handling works
        # This test verifies both normalization methods work correctly
        # Verify _normalize_file_data_for_tagging strips BOM
        normalized = processor._normalize_file_data_for_tagging()
        assert isinstance(normalized, str)
        assert not normalized.startswith('\ufeff'), "BOM should have been stripped by _normalize_file_data_for_tagging"
        assert normalized.startswith('<?xml'), "Content should start with XML declaration after BOM removal"
        assert 'Garmin Desktop App' in normalized, "Normalized content should contain device name"
        
        # Verify _decode_content also strips BOM (used for conversion)
        decoded = processor._decode_content()
        assert isinstance(decoded, str)
        assert not decoded.startswith('\ufeff'), "BOM should have been stripped by _decode_content"
        assert decoded.startswith('<?xml'), "Content should start with XML declaration after BOM removal"
        assert 'Garmin Desktop App' in decoded, "Decoded content should contain device name"



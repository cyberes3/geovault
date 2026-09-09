"""
Tests for FormatReaders (KML, KMZ, GPX) with real conversion.
"""
from types import SimpleNamespace

import pytest

from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.readers.gpx import GpxReader
from geo_lib.importing.readers.kml import KmlReader
from geo_lib.importing.readers.kmz import KmzReader
from geo_lib.importing.readers.registry import read_upload
from geo_lib.importing.session import RawFile
from geo_lib.importing.support import content_decoding
from geo_lib.processing.file_types import FileType, detect_file_type


def _session(data, filename):
    if isinstance(data, str):
        data = data.encode('utf-8')
    return SimpleNamespace(raw_file=RawFile.from_bytes(data), filename=filename)


def _read(data, filename):
    return read_upload(_session(data, filename))[0]


class TestFormatReaders:
    def test_kml_reader_selected(self):
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
        geojson, file_type = read_upload(_session(kml_content, 'test.kml'))
        assert file_type == FileType.KML
        assert geojson['type'] == 'FeatureCollection'
        assert KmlReader().supports('test.kml', FileType.KML)

    def test_gpx_reader_selected(self):
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
        geojson, file_type = read_upload(_session(gpx_content, 'test.gpx'))
        assert file_type == FileType.GPX
        assert geojson['type'] == 'FeatureCollection'
        assert GpxReader().supports('test.gpx', FileType.GPX)

    def test_kmz_reader_selected(self):
        kmz_content = b'PK\x03\x04' + b'fake zip content'
        assert detect_file_type(kmz_content, 'test.kmz') == FileType.KMZ
        assert KmzReader().supports('test.kmz', FileType.KMZ)

    def test_unsupported_file_type(self):
        with pytest.raises(ImportStageError):
            read_upload(_session(b'content', 'test.txt'))

    def test_kml_reader_convert(self):
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
        result = _read(kml_content, 'test.kml')
        assert result['type'] == 'FeatureCollection'
        assert len(result['features']) >= 1

    def test_gpx_reader_convert(self):
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
        result = _read(gpx_content, 'test.gpx')
        assert result['type'] == 'FeatureCollection'
        assert len(result['features']) >= 1

    def test_bom_normalization_bytes(self):
        gpx_content = '<?xml version="1.0" encoding="utf-8"?><gpx creator="Garmin Desktop App" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Track</name><trkseg><trkpt lat="37.7749" lon="-122.4194"><ele>1696.96</ele><time>2025-05-20T18:51:08Z</time></trkpt></trkseg></trk></gpx>'
        gpx_content_with_bom = b'\xef\xbb\xbf' + gpx_content.encode('utf-8')
        normalized = content_decoding.normalize_file_data_for_tagging(
            gpx_content_with_bom, 'test.gpx', FileType.GPX
        )
        assert isinstance(normalized, str)
        assert normalized.startswith('<?xml')
        assert not normalized.startswith('\ufeff')
        assert 'Garmin Desktop App' in normalized

    def test_bom_normalization_string(self):
        gpx_content = '<?xml version="1.0" encoding="utf-8"?><gpx creator="Garmin Desktop App" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Track</name><trkseg><trkpt lat="37.7749" lon="-122.4194"><ele>1696.96</ele><time>2025-05-20T18:51:08Z</time></trkpt></trkseg></trk></gpx>'
        gpx_content_with_bom = '\ufeff' + gpx_content
        normalized = content_decoding.normalize_file_data_for_tagging(
            gpx_content_with_bom, 'test.gpx', FileType.GPX
        )
        assert isinstance(normalized, str)
        assert normalized.startswith('<?xml')
        assert not normalized.startswith('\ufeff')
        assert 'Garmin Desktop App' in normalized

    def test_bom_handling_end_to_end(self):
        gpx_content = '<?xml version="1.0" encoding="utf-8"?><gpx creator="Garmin Desktop App" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Track</name><trkseg><trkpt lat="37.7749" lon="-122.4194"><ele>1696.96</ele><time>2025-05-20T18:51:08Z</time></trkpt></trkseg></trk></gpx>'
        gpx_content_with_bom = b'\xef\xbb\xbf' + gpx_content.encode('utf-8')
        normalized = content_decoding.normalize_file_data_for_tagging(
            gpx_content_with_bom, 'test.gpx', FileType.GPX
        )
        assert isinstance(normalized, str)
        assert not normalized.startswith('\ufeff')
        assert normalized.startswith('<?xml')
        assert 'Garmin Desktop App' in normalized
        decoded = content_decoding.decode_content(gpx_content_with_bom)
        assert isinstance(decoded, str)
        assert not decoded.startswith('\ufeff')
        assert decoded.startswith('<?xml')
        assert 'Garmin Desktop App' in decoded
        result = _read(gpx_content_with_bom, 'test.gpx')
        assert result['type'] == 'FeatureCollection'

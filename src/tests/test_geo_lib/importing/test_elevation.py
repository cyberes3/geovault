"""Elevation fills 2D only and keeps explicit Z=0.0."""

from unittest.mock import patch

from django.test import SimpleTestCase

from geo_lib.processing.elevation_service import fill_missing_elevations
from geo_lib.processing.logging import ImportLog


def _settings(key: str):
    return {
        'ELEVATION_API_ENABLED': True,
        'ELEVATION_API_URL': 'http://example.test/elev',
        'ELEVATION_API_TIMEOUT': 5,
    }[key]


class TestFillMissingElevations(SimpleTestCase):
    @patch('geo_lib.processing.elevation_service.get_required_setting', side_effect=_settings)
    @patch('geo_lib.processing.elevation_service._fetch_elevation_batch_with_retry', return_value=[12.5])
    def test_fills_2d_only(self, _fetch, _settings_mock):
        geojson = {
            'type': 'FeatureCollection',
            'features': [{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0]},
                'properties': {},
            }],
        }
        fill_missing_elevations(geojson, ImportLog())
        self.assertEqual(geojson['features'][0]['geometry']['coordinates'][2], 12.5)

    @patch('geo_lib.processing.elevation_service.get_required_setting', side_effect=_settings)
    @patch('geo_lib.processing.elevation_service._fetch_elevation_batch_with_retry')
    def test_keeps_explicit_zero_elevation(self, fetch, _settings_mock):
        geojson = {
            'type': 'FeatureCollection',
            'features': [{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
                'properties': {},
            }],
        }
        fill_missing_elevations(geojson, ImportLog())
        fetch.assert_not_called()
        self.assertEqual(geojson['features'][0]['geometry']['coordinates'][2], 0.0)

    @patch('geo_lib.processing.elevation_service.get_required_setting', side_effect=_settings)
    @patch('geo_lib.processing.elevation_service._fetch_elevation_batch_with_retry', return_value=[5.0])
    def test_cancel_between_batches_stops_fetch(self, fetch, _settings_mock):
        geojson = {
            'type': 'FeatureCollection',
            'features': [{
                'type': 'Feature',
                'geometry': {
                    'type': 'LineString',
                    'coordinates': [[0.0, 0.0], [1.0, 1.0]],
                },
                'properties': {},
            }],
        }
        fill_missing_elevations(geojson, ImportLog(), is_canceled=lambda: True)
        fetch.assert_not_called()

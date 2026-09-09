import json

from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.session import UploadSession
from geo_lib.importing.support import content_decoding
from geo_lib.processing.file_types import FileType


class GeoJsonReader:
    def supports(self, filename: str, file_type: FileType) -> bool:
        return file_type == FileType.GEOJSON

    def read(self, session: UploadSession) -> dict:
        content = content_decoding.decode_content(session.raw_file.data)
        try:
            geojson_data = json.loads(content)
        except json.JSONDecodeError as exc:
            raise ImportStageError(f"Invalid JSON format: {exc}", unparsable=True) from exc
        if not isinstance(geojson_data, dict):
            raise ImportStageError("GeoJSON must be a JSON object", unparsable=True)
        geojson_type = geojson_data.get('type')
        if geojson_type == 'Feature':
            return {'type': 'FeatureCollection', 'features': [geojson_data]}
        if geojson_type == 'FeatureCollection':
            features = geojson_data.get('features')
            if not isinstance(features, list):
                raise ImportStageError("FeatureCollection 'features' must be an array", unparsable=True)
            return geojson_data
        raise ImportStageError(
            f"GeoJSON type must be 'Feature' or 'FeatureCollection', got '{geojson_type}'",
            unparsable=True,
        )

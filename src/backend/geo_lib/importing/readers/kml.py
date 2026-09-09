from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.readers.kml_prep import apply_kml_times_collection, remove_kml_namespace_prefixes
from geo_lib.importing.session import UploadSession
from geo_lib.importing.support import content_decoding, conversion_runner
from geo_lib.processing.file_types import FileType
from geo_lib.processing.icons.icon_manager import process_geojson_icons
from geo_lib.processing.job_ceiling import calculate_conversion_timeout_seconds
from geo_lib.processing.logging import ImportLog


class KmlReader:
    def supports(self, filename: str, file_type: FileType) -> bool:
        return file_type == FileType.KML

    def read(self, session: UploadSession) -> dict:
        import_log = ImportLog()
        content = content_decoding.decode_content(session.raw_file.data)
        content = remove_kml_namespace_prefixes(content)
        timeout_seconds = calculate_conversion_timeout_seconds(len(session.raw_file.data))
        try:
            geojson_data = conversion_runner.convert_xml_to_geojson(
                content, 'KML', timeout_seconds, session.filename, import_log
            )
        except Exception as exc:
            raise ImportStageError(str(exc), unparsable=True) from exc
        geojson_data = apply_kml_times_collection(geojson_data)
        return process_geojson_icons(
            geojson_data,
            file_type='kml',
            import_log=import_log,
            file_data=None,
        )

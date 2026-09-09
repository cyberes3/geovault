from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.readers.kml_prep import apply_kml_times_collection, remove_kml_namespace_prefixes
from geo_lib.importing.session import UploadSession
from geo_lib.importing.support import conversion_runner
from geo_lib.processing.file_types import FileType
from geo_lib.processing.icons.icon_manager import process_geojson_icons
from geo_lib.processing.job_ceiling import calculate_conversion_timeout_seconds
from geo_lib.processing.logging import ImportLog
from geo_lib.security.secure_file_validator import secure_kmz_to_kml


class KmzReader:
    def supports(self, filename: str, file_type: FileType) -> bool:
        return file_type == FileType.KMZ

    def read(self, session: UploadSession) -> dict:
        import_log = ImportLog()
        try:
            kml_content = secure_kmz_to_kml(session.raw_file.data)
        except Exception as exc:
            raise ImportStageError(f"Failed to extract KML from KMZ: {exc}", unparsable=True) from exc
        kml_content = remove_kml_namespace_prefixes(kml_content)
        timeout_seconds = calculate_conversion_timeout_seconds(len(session.raw_file.data))
        try:
            geojson_data = conversion_runner.convert_xml_to_geojson(
                kml_content, 'KMZ', timeout_seconds, session.filename, import_log
            )
        except ImportStageError:
            raise
        except Exception as exc:
            raise ImportStageError(str(exc), unparsable=True) from exc
        geojson_data = apply_kml_times_collection(geojson_data)
        return process_geojson_icons(
            geojson_data,
            file_type='kmz',
            import_log=import_log,
            file_data=session.raw_file.data,
        )

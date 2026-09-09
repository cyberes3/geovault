from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.session import UploadSession
from geo_lib.importing.support import content_decoding, conversion_runner
from geo_lib.processing.file_types import FileType
from geo_lib.processing.job_ceiling import calculate_conversion_timeout_seconds
from geo_lib.processing.logging import ImportLog


class GpxReader:
    def supports(self, filename: str, file_type: FileType) -> bool:
        return file_type == FileType.GPX

    def read(self, session: UploadSession) -> dict:
        import_log = ImportLog()
        content = content_decoding.decode_content(session.raw_file.data)
        timeout_seconds = calculate_conversion_timeout_seconds(len(session.raw_file.data))
        try:
            return conversion_runner.convert_xml_to_geojson(
                content, 'GPX', timeout_seconds, session.filename, import_log
            )
        except Exception as exc:
            raise ImportStageError(str(exc), unparsable=True) from exc

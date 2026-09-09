from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.readers.geojson import GeoJsonReader
from geo_lib.importing.readers.gpx import GpxReader
from geo_lib.importing.readers.kml import KmlReader
from geo_lib.importing.readers.kmz import KmzReader
from geo_lib.importing.session import UploadSession
from geo_lib.processing.file_types import FileType, detect_file_type

FORMAT_READERS = (KmlReader(), KmzReader(), GpxReader(), GeoJsonReader())


def read_upload(session: UploadSession) -> tuple[dict, FileType]:
    try:
        file_type = detect_file_type(session.raw_file.data, session.filename)
    except ValueError as exc:
        raise ImportStageError(str(exc), unparsable=True) from exc
    for reader in FORMAT_READERS:
        if reader.supports(session.filename, file_type):
            return reader.read(session), file_type
    raise ImportStageError(f"Unsupported file type: {file_type}", unparsable=True)

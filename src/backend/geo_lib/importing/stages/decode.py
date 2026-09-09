from django.core.files.uploadedfile import SimpleUploadedFile

from geo_lib.importing.context import ImportContext
from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.readers.registry import read_upload
from geo_lib.importing.runtime import JobRuntime
from geo_lib.importing.support import content_decoding
from geo_lib.processing.file_types import FileType, detect_file_type
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.security.exceptions import FileValidationError, SecurityError
from geo_lib.security.secure_file_validator import validate_file


class DecodeStage:
    name = 'decode'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        runtime.checkpoint('before decode')
        session = ctx.session
        try:
            file_type = detect_file_type(session.raw_file.data, session.filename)
        except ValueError as exc:
            raise ImportStageError(str(exc), unparsable=True) from exc
        if file_type != FileType.GEOJSON:
            is_valid, validation_error = _validate_upload(session.raw_file.data, session.filename, file_type)
            if not is_valid:
                raise ImportStageError(f"File validation failed: {validation_error}", unparsable=True)
        try:
            geojson_data, detected = read_upload(session)
        except (FileValidationError, SecurityError) as exc:
            raise ImportStageError(str(exc), unparsable=True) from exc
        features = geojson_data.get('features')
        if not isinstance(features, list):
            raise ImportStageError("Conversion returned invalid GeoJSON data", unparsable=True)
        if len(features) == 0:
            raise ImportStageError(
                "File contains no geographic features (placemarks, waypoints, or tracks)",
                unparsable=True,
            )
        ctx.features = features
        ctx.file_type_name = detected.value
        ctx.source_text = content_decoding.normalize_file_data_for_tagging(
            session.raw_file.data, session.filename, detected
        )
        ctx.log.add(f"Decoded {len(features)} raw features", "Decode", DatabaseLogLevel.INFO)


def _validate_upload(file_data: bytes, filename: str, file_type: FileType) -> tuple[bool, str | None]:
    if file_type == FileType.KMZ:
        content_type = 'application/zip'
    else:
        content_type = 'text/xml'
    uploaded = SimpleUploadedFile(name=filename, content=file_data, content_type=content_type)
    return validate_file(uploaded)

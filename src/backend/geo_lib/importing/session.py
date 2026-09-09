import base64
import hashlib
from dataclasses import dataclass
from typing import Literal

from django.contrib.auth.models import User
from django.db import transaction

from api.models import ImportQueue
from geo_lib.utils.secure_path import secure_filename

RawEncoding = Literal['utf8', 'base64']


@dataclass(frozen=True)
class ProcessOptions:
    minimal_processing: bool = False
    import_custom_icons: bool = True


@dataclass(frozen=True)
class RawFile:
    data: bytes
    encoding: RawEncoding
    file_hash: str

    @classmethod
    def from_bytes(cls, data: bytes) -> "RawFile":
        file_hash = hashlib.sha256(data).hexdigest()
        try:
            data.decode('utf-8')
            encoding: RawEncoding = 'utf8'
        except UnicodeDecodeError:
            encoding = 'base64'
        return cls(data=data, encoding=encoding, file_hash=file_hash)

    def persist_text(self) -> str:
        if self.encoding == 'utf8':
            return self.data.decode('utf-8')
        return base64.b64encode(self.data).decode('utf-8')

    @classmethod
    def load(cls, text: str, encoding: str, file_hash: str | None = None) -> "RawFile":
        if encoding == 'base64':
            data = base64.b64decode(text)
        else:
            data = text.encode('utf-8')
        digest = file_hash or hashlib.sha256(data).hexdigest()
        stored: RawEncoding = 'base64' if encoding == 'base64' else 'utf8'
        return cls(data=data, encoding=stored, file_hash=digest)


@dataclass(frozen=True)
class UploadSession:
    queue_id: int
    user_id: int
    filename: str
    raw_file: RawFile
    replacement_feature_id: int | None
    options: ProcessOptions

    @classmethod
    def open(
        cls,
        user_id: int,
        filename: str,
        file_data: bytes,
        replacement_feature_id: int | None = None,
        options: ProcessOptions | None = None,
    ) -> "UploadSession":
        raw_file = RawFile.from_bytes(file_data)
        safe_filename = secure_filename(filename) or 'import'
        process_options = options or ProcessOptions(
            minimal_processing=replacement_feature_id is not None,
        )
        with transaction.atomic():
            user = User.objects.get(id=user_id)
            queue = ImportQueue.objects.create(
                raw_file=raw_file.persist_text(),
                raw_file_encoding=raw_file.encoding,
                file_hash=raw_file.file_hash,
                original_filename=safe_filename,
                user=user,
                replacement=replacement_feature_id,
                queue_status=ImportQueue.STATUS_PROCESSING,
                imported=False,
                unparsable=False,
            )
        return cls(
            queue_id=queue.id,
            user_id=user_id,
            filename=safe_filename,
            raw_file=raw_file,
            replacement_feature_id=replacement_feature_id,
            options=process_options,
        )

    @classmethod
    def from_queue(cls, queue: ImportQueue, options: ProcessOptions | None = None) -> "UploadSession":
        raw_file = RawFile.load(queue.raw_file, queue.raw_file_encoding, queue.file_hash)
        return cls(
            queue_id=queue.id,
            user_id=queue.user_id,
            filename=queue.original_filename,
            raw_file=raw_file,
            replacement_feature_id=queue.replacement,
            options=options or ProcessOptions(minimal_processing=queue.replacement is not None),
        )

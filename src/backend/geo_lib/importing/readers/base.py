from typing import Protocol

from geo_lib.importing.session import UploadSession
from geo_lib.processing.file_types import FileType


class FormatReader(Protocol):
    def supports(self, filename: str, file_type: FileType) -> bool:
        ...

    def read(self, session: UploadSession) -> dict:
        ...

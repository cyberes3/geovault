from dataclasses import dataclass, field
from typing import Any

from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict
from geo_lib.importing.session import UploadSession
from geo_lib.processing.logging import ImportLog


@dataclass
class ImportContext:
    session: UploadSession
    features: list[dict[str, Any]] = field(default_factory=list)
    source_text: str | None = None
    type_summary: str = ''
    verdicts: dict[str, DuplicateVerdict] = field(default_factory=dict)
    skip_intent: SkipIntent = field(default_factory=SkipIntent)
    internal_duplicate_count: int = 0
    log: ImportLog = field(default_factory=ImportLog)
    file_type_name: str = ''

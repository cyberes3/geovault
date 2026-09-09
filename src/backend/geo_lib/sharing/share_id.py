import re
import uuid
from collections.abc import Callable

SHARE_ID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


class ShareId:
    """UUID4 token used as the global share-link identity."""

    def __init__(self, value: str):
        normalized = value.strip().lower()
        if not SHARE_ID_PATTERN.fullmatch(normalized):
            raise ValueError("Share id must be a UUID4")
        self.value = normalized

    def __str__(self) -> str:
        return self.value

    def __eq__(self, other: object) -> bool:
        if isinstance(other, ShareId):
            return self.value == other.value
        return NotImplemented

    def __hash__(self) -> int:
        return hash(self.value)

    @classmethod
    def parse(cls, value: str | None) -> "ShareId | None":
        if not value or not isinstance(value, str):
            return None
        try:
            return cls(value)
        except ValueError:
            return None

    @classmethod
    def generate(cls) -> "ShareId":
        return cls(str(uuid.uuid4()))

    @classmethod
    def generate_unique(cls, exists: Callable[[str], bool]) -> "ShareId":
        candidate = cls.generate()
        while exists(candidate.value):
            candidate = cls.generate()
        return candidate

"""Protected system-tag prefixes. Checks run after lowercase."""

from typing import Iterable

from geo_lib.processing.tagging.const_strings import CONST_INTERNAL_TAGS


class TagValidationError(ValueError):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def protected_prefixes() -> list[str]:
    return [prefix.lower() for prefix in CONST_INTERNAL_TAGS]


def is_protected_tag(tag: str, prefixes: Iterable[str] | None = None) -> bool:
    """True if `tag` equals a prefix or starts with `prefix:` (case-insensitive)."""
    if not isinstance(tag, str):
        return False
    lowered = tag.strip().lower()
    if not lowered:
        return False
    source = prefixes if prefixes is not None else CONST_INTERNAL_TAGS
    for prefix in source:
        if not isinstance(prefix, str):
            continue
        prefix_lower = prefix.strip().lower()
        if not prefix_lower:
            continue
        if lowered == prefix_lower or lowered.startswith(prefix_lower + ':'):
            return True
    return False


def filter_protected_tags(tags: list[str], prefixes: Iterable[str] | None = None) -> list[str]:
    if not isinstance(tags, list):
        return []
    return [tag for tag in tags if isinstance(tag, str) and not is_protected_tag(tag, prefixes)]

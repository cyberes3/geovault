"""Normalized user + system tag value object."""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from typing import Iterable, Mapping

from geo_lib.tags.protected import TagValidationError, is_protected_tag
from website.settings_utils import get_required_setting


def _coerce_tag_list(raw) -> list[str]:
    if not isinstance(raw, list):
        return []
    return [tag for tag in raw if isinstance(tag, str)]


def _has_disallowed_control_chars(tag: str) -> bool:
    return any(ord(char) < 32 and char not in '\t\n\r' for char in tag)


def normalize_tag_text(tag: str) -> str:
    return unicodedata.normalize('NFC', tag.strip()).lower()


@dataclass(frozen=True)
class TagSet:
    user: tuple[str, ...]
    system: tuple[str, ...]

    @classmethod
    def from_properties(cls, properties: Mapping[str, object] | None) -> TagSet:
        if not isinstance(properties, dict):
            return cls(user=(), system=())
        return cls(
            user=tuple(_coerce_tag_list(properties.get('tags'))),
            system=tuple(_coerce_tag_list(properties.get('system_tags'))),
        )

    def apply_to_properties(self, properties: dict) -> dict:
        properties['tags'] = list(self.user)
        properties['system_tags'] = list(self.system)
        return properties

    def union(self) -> tuple[str, ...]:
        seen: dict[str, None] = {}
        for tag in (*self.user, *self.system):
            seen.setdefault(tag, None)
        return tuple(seen)

    def with_user(self, user_tags: Iterable[str]) -> TagSet:
        return TagSet(user=tuple(user_tags), system=self.system)

    def with_system(self, system_tags: Iterable[str]) -> TagSet:
        return TagSet(user=self.user, system=tuple(system_tags))

    @classmethod
    def normalize_user_tags(
        cls,
        tags,
        *,
        reject_protected: bool = True,
        drop_protected: bool = False,
    ) -> tuple[str, ...]:
        """
        Strip, NFC, lowercase, dedupe. Protected check runs after lowercase.

        `reject_protected` raises on a protected tag. `drop_protected` strips them
        (import drafts). They are mutually exclusive.
        """
        if tags is None:
            return ()
        if not isinstance(tags, list):
            raise TagValidationError('tags must be an array')

        tag_max_length = get_required_setting('TAG_MAX_LENGTH')
        seen: dict[str, None] = {}
        for tag in tags:
            if not isinstance(tag, str):
                raise TagValidationError('all tags must be strings')
            normalized = normalize_tag_text(tag)
            if not normalized:
                raise TagValidationError('Tags cannot be empty or contain only whitespace')
            if _has_disallowed_control_chars(normalized) or _has_disallowed_control_chars(tag):
                raise TagValidationError('Tags cannot contain control characters')
            if len(normalized) > tag_max_length:
                raise TagValidationError(
                    f'Tag "{normalized[:50]}..." exceeds maximum length of {tag_max_length} characters'
                )
            if is_protected_tag(normalized):
                if drop_protected:
                    continue
                if reject_protected:
                    raise TagValidationError(
                        'System tags (type, import-year, import-month, feature-year, feature-month, '
                        'source-file, track, elevation, reverse geocoding) cannot be added as user tags'
                    )
            seen.setdefault(normalized, None)
        return tuple(seen)

    @classmethod
    def normalize_system_tags(cls, tags: Iterable[str]) -> tuple[str, ...]:
        seen: dict[str, None] = {}
        for tag in tags:
            if not isinstance(tag, str):
                continue
            normalized = normalize_tag_text(tag)
            if normalized:
                seen.setdefault(normalized, None)
        return tuple(seen)

"""Collection metadata plus rule/pin counts."""

from dataclasses import dataclass
from typing import Sequence
from uuid import UUID


@dataclass(frozen=True)
class CollectionDefinition:
    id: UUID
    user_id: int
    name: str
    description: str | None
    tags: tuple[str, ...]
    feature_ids: tuple[int, ...]
    feature_count: int
    created_at: str
    updated_at: str

    def as_dict(self) -> dict:
        return {
            'id': str(self.id),
            'name': self.name,
            'description': self.description or '',
            'tags': list(self.tags),
            'feature_ids': list(self.feature_ids),
            'feature_count': self.feature_count,
            'created_at': self.created_at,
            'updated_at': self.updated_at,
        }


@dataclass(frozen=True)
class CollectionEditorInput:
    name: str | None
    description: str | None
    description_provided: bool
    tags: Sequence[str] | None
    feature_ids: Sequence[int] | None

from dataclasses import dataclass
from enum import Enum

from geo_lib.duplicates.identity import FeatureRef


class VerdictKind(str, Enum):
    NONE = 'none'
    HASH = 'hash'
    GEOMETRY = 'geometry'


class VerdictScope(str, Enum):
    NONE = 'none'
    LIBRARY = 'library'
    DRAFT_QUEUE = 'draft_queue'


@dataclass(frozen=True)
class DuplicateVerdict:
    kind: VerdictKind
    scope: VerdictScope
    matches: tuple[FeatureRef, ...] = ()

    @property
    def is_blocked(self) -> bool:
        return self.kind == VerdictKind.HASH

    @property
    def is_restorable(self) -> bool:
        return self.kind == VerdictKind.GEOMETRY

    @classmethod
    def none(cls) -> "DuplicateVerdict":
        return cls(kind=VerdictKind.NONE, scope=VerdictScope.NONE, matches=())

    @classmethod
    def resolve(cls, candidates: list["DuplicateVerdict"]) -> "DuplicateVerdict":
        """HASH > GEOMETRY; library > older draft queue."""
        best: DuplicateVerdict | None = None
        for candidate in candidates:
            if candidate.kind == VerdictKind.NONE:
                continue
            if best is None:
                best = candidate
                continue
            if _priority(candidate) < _priority(best):
                best = candidate
        return best if best is not None else cls.none()


def _priority(verdict: DuplicateVerdict) -> tuple[int, int]:
    kind_rank = {
        VerdictKind.HASH: 0,
        VerdictKind.GEOMETRY: 1,
        VerdictKind.NONE: 2,
    }[verdict.kind]
    scope_rank = {
        VerdictScope.LIBRARY: 0,
        VerdictScope.DRAFT_QUEUE: 1,
        VerdictScope.NONE: 2,
    }[verdict.scope]
    return kind_rank, scope_rank

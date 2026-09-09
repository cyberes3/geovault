from pydantic import BaseModel, ConfigDict, Field

from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope


class DuplicateVerdictWire(BaseModel):
    model_config = ConfigDict(extra='forbid')

    kind: str
    scope: str
    blocked: bool
    restorable: bool
    match_feature_store_id: int | None = None
    match_queue_id: int | None = None
    match_spatial_index: int | None = None
    match_name: str = ''
    match_type: str = ''

    @classmethod
    def from_verdict(cls, verdict: DuplicateVerdict) -> "DuplicateVerdictWire":
        match = verdict.matches[0] if verdict.matches else None
        return cls(
            kind=verdict.kind.value,
            scope=verdict.scope.value,
            blocked=verdict.is_blocked,
            restorable=verdict.is_restorable,
            match_feature_store_id=match.feature_store_id if match else None,
            match_queue_id=match.draft_queue_id if match else None,
            match_spatial_index=match.spatial_index if match else None,
            match_name=match.name if match else '',
            match_type=match.geometry_type if match else '',
        )


class SkipIntentWire(BaseModel):
    model_config = ConfigDict(extra='forbid')

    skipped: list[str] = Field(default_factory=list)
    restored: list[str] = Field(default_factory=list)


class DuplicateCountsWire(BaseModel):
    model_config = ConfigDict(extra='forbid')

    hash: int = 0
    geometry: int = 0


def empty_verdict_wire() -> DuplicateVerdictWire:
    return DuplicateVerdictWire(
        kind=VerdictKind.NONE.value,
        scope=VerdictScope.NONE.value,
        blocked=False,
        restorable=False,
    )

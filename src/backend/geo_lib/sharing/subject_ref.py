import uuid
from typing import Any

from geo_lib.sharing.constants import (
    AUDIENCE_WORLD,
    DOMAIN_LIVE_TRACK,
    DOMAIN_MAP,
    KIND_FEATURE,
    KIND_TAG,
    MAP_KINDS,
    SHARE_KINDS,
    TRACKER_KINDS,
)
from geo_lib.sharing.errors import ShareError


def canonicalize_subject_ref(subject_kind: str, value: Any) -> str | int:
    if subject_kind not in SHARE_KINDS:
        raise ShareError(f"Invalid subject_kind: {subject_kind}")
    if subject_kind == KIND_TAG:
        if not isinstance(value, str) or not value.strip():
            raise ShareError("tag subject_ref must be a non-empty string")
        return value.strip()
    if subject_kind == KIND_FEATURE:
        try:
            feature_id = int(value)
        except (TypeError, ValueError) as exc:
            raise ShareError("feature subject_ref must be a positive integer") from exc
        if feature_id <= 0:
            raise ShareError("feature subject_ref must be a positive integer")
        return feature_id
    try:
        return str(uuid.UUID(str(value)))
    except (TypeError, ValueError) as exc:
        raise ShareError(f"{subject_kind} subject_ref must be a UUID") from exc


def domain_for_kind(subject_kind: str) -> str:
    if subject_kind in MAP_KINDS:
        return DOMAIN_MAP
    if subject_kind in TRACKER_KINDS:
        return DOMAIN_LIVE_TRACK
    raise ShareError(f"Invalid subject_kind: {subject_kind}")


def default_audience_for_kind(subject_kind: str) -> str:
    if subject_kind in MAP_KINDS:
        return AUDIENCE_WORLD
    raise ShareError(f"audience is required for {subject_kind}")

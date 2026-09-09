from dataclasses import dataclass

from geo_lib.sharing.constants import KIND_LIVE_TRACK, KIND_LIVE_TRACK_GROUP, TRACKER_KINDS
from geo_lib.sharing.errors import ShareError


@dataclass(frozen=True)
class ShareGrantSpec:
    resource_kind: str
    resource_id: str
    grantee_user_id: int

    def __post_init__(self) -> None:
        if self.resource_kind not in TRACKER_KINDS:
            raise ShareError(f"Invalid grant resource_kind: {self.resource_kind}")
        if not self.resource_id:
            raise ShareError("grant resource_id is required")
        if self.grantee_user_id <= 0:
            raise ShareError("grant grantee_user_id must be a positive integer")


def grant_resource_kind(subject_kind: str) -> str:
    if subject_kind == KIND_LIVE_TRACK:
        return KIND_LIVE_TRACK
    if subject_kind == KIND_LIVE_TRACK_GROUP:
        return KIND_LIVE_TRACK_GROUP
    raise ShareError(f"No grant resource for subject_kind: {subject_kind}")

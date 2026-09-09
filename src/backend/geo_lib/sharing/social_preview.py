import hashlib
from typing import Mapping

from geo_lib.sharing.constants import KIND_COLLECTION, KIND_FEATURE, KIND_TAG

SOCIAL_PREVIEW_CACHE_SECONDS = 60 * 60 * 24 * 30
PREVIEW_LOCK_SECONDS = 60


class PreviewKey:
    """Cache identity for a social preview PNG: token + tile source + extent fingerprint."""

    def __init__(self, token: str, tile_source: str, extent_hash: str):
        if not token:
            raise ValueError("token is required")
        if not tile_source:
            raise ValueError("tile_source is required")
        if not extent_hash:
            raise ValueError("extent_hash is required")
        self.token = token
        self.tile_source = tile_source
        self.extent_hash = extent_hash

    def cache_key(self) -> str:
        return f"social_preview_png:{self.token}:{self.tile_source}:{self.extent_hash}"

    @staticmethod
    def index_key(token: str) -> str:
        return f"social_preview_index:{token}"

    @staticmethod
    def lock_key(token: str) -> str:
        return f"social_preview_lock:{token}"


def hash_extent(extent) -> str:
    if not extent:
        return "none"
    parts = [f"{float(value):.6f}" for value in extent]
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:16]


def metadata_from_share_info(share_info: Mapping[str, object]) -> dict[str, str]:
    share_type = share_info.get("share_type")
    if share_type == KIND_TAG:
        subject = str(share_info.get("tag") or "Unknown Tag")
        return {
            "title": f"Shared Map: {subject}",
            "description": f"Shared map for tag {subject} on GeoVault.",
        }
    if share_type == KIND_COLLECTION:
        subject = str(share_info.get("collection_name") or "Unknown Collection")
        return {
            "title": f"Shared Map: {subject}",
            "description": f"Shared map for collection {subject} on GeoVault.",
        }
    if share_type == KIND_FEATURE:
        subject = str(share_info.get("feature_name") or "Unnamed Feature")
        return {
            "title": f"Shared Map: {subject}",
            "description": f"Shared map for feature {subject} on GeoVault.",
        }
    if share_type == "live_track":
        subject = str(share_info.get("track_name") or "Shared Track")
        return {
            "title": f"Shared Track: {subject}",
            "description": f"Live track {subject} on GeoVault.",
        }
    subject = str(share_info.get("group_name") or "Shared Group")
    return {
        "title": f"Shared Track Group: {subject}",
        "description": f"Live track group {subject} on GeoVault.",
    }

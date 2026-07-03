"""Shared utilities for sharing"""
import re
import uuid
from typing import Optional

from django.db.models import Model

from api.models import CollectionShare, FeatureShare, TagShare

# (model, share_type, select_related-fields) for the 3 share types, in lookup order.
# A single share_id namespace spans all 3 tables, so any share_id maps to at most one
# of these - this is the source of truth for every "find a share by id" touchpoint
# (public info/extent lookups, KMZ download validation, delete).
_SHARE_TYPE_LOOKUP: tuple[tuple[type[Model], str, Optional[tuple[str, ...]]], ...] = (
    (TagShare, 'tag', None),
    (CollectionShare, 'collection', ('collection',)),
    (FeatureShare, 'feature', ('feature',)),
)


def find_share_by_id(share_id: str) -> tuple[Optional[Model], Optional[str]]:
    """
    Look up a share by share_id across all 3 share type tables.

    Does NOT validate share_id format - callers should call validate_share_id() first
    so they can shape their own error response (some distinguish malformed vs.
    not-found, others intentionally don't to avoid leaking share existence).

    Returns:
        (share, share_type) where share_type is 'tag' | 'collection' | 'feature',
        or (None, None) if no matching share exists.
    """
    for model, share_type, select_related in _SHARE_TYPE_LOOKUP:
        qs = model.objects.filter(share_id=share_id)
        if select_related:
            qs = qs.select_related(*select_related)
        share = qs.first()
        if share:
            return share, share_type
    return None, None


def generate_unique_share_id() -> str:
    """
    Generate a UUID4 share_id guaranteed unique across all three share tables (tag,
    collection, feature), which share a single global namespace of share_id values.
    """
    share_id = str(uuid.uuid4())
    while (
        TagShare.objects.filter(share_id=share_id).exists()
        or CollectionShare.objects.filter(share_id=share_id).exists()
        or FeatureShare.objects.filter(share_id=share_id).exists()
    ):
        share_id = str(uuid.uuid4())
    return share_id


def validate_share_id(share_id: str) -> bool:
    """
    Validate share_id format.
    Must be a valid UUID4 format (36 characters with hyphens).
    """
    if not share_id or not isinstance(share_id, str):
        return False
    # UUID4 format: 8-4-4-4-12 hexadecimal characters
    uuid_pattern = r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    return bool(re.match(uuid_pattern, share_id.lower()))


def build_client_share_url(path: str) -> str:
    """
    Build a share URL that clients resolve against their public origin.

    Returning a relative URL avoids leaking internal proxy/backend hosts when
    requests arrive through reverse proxies.
    """
    if not path.startswith("/"):
        return f"/{path}"
    return path


def build_share_url(request, share_id: str) -> str:
    """
    Build a social-preview-capable share path.

    Args:
        request: Django request object (unused, kept for API compatibility)
        share_id: The share ID (UUID4) to include in the path

    Returns:
        Share path (e.g., "/share/map/<id>/")
    """
    return build_client_share_url(f"/share/map/{share_id}/")

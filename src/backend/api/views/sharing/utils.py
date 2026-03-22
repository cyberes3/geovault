"""Shared utilities for sharing"""
import re


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


def build_share_url(request, share_id: str) -> str:
    """
    Build a social-preview-capable share path.

    Args:
        request: Django request object (unused, kept for API compatibility)
        share_id: The share ID (UUID4) to include in the path

    Returns:
        Share path (e.g., "/share/map/<id>/")
    """
    return f"/share/map/{share_id}/"

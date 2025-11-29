"""
Helpers for validating and normalizing styling values (colors, icon URLs).

These helpers are used from multiple places:
- Import/bulk styling (`validate_bulk_operations_payload`, `apply_bulk_operations`)
- Feature update APIs

Rules:
- Colors: allow 3-digit or 6-digit hex with leading '#', e.g. '#0f3', '#00ff30'
- Icon URLs: only allow built-in and uploaded icons:
  - 'assets/...' (system icons)
  - '/api/icons/...' (uploaded/user icons)
"""

import re
from typing import Optional


HEX_COLOR_RE = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")


def is_valid_hex_color(value: object) -> bool:
    """
    Return True if value is a string hex color in #RGB or #RRGGBB form.
    """
    if not isinstance(value, str):
        return False
    value = value.strip()
    if not value:
        return False
    return bool(HEX_COLOR_RE.match(value))


def normalize_hex_color(value: str) -> str:
    """
    Normalize a hex color to uppercase.

    Keeps 3-digit vs 6-digit form as-is; callers can expand if needed.
    Assumes the input has already passed is_valid_hex_color().
    """
    return value.strip().upper()


def is_valid_icon_url(value: object) -> bool:
    """
    Return True if value is an allowed icon URL/path.

    Allowed:
    - 'assets/...'
    - '/api/icons/...'

    Everything else (including external http(s) URLs) is rejected.
    """
    if not isinstance(value, str):
        return False
    value = value.strip()
    if not value:
        return False
    return value.startswith("assets/") or value.startswith("/api/icons/")


def describe_color_format(field_name: str) -> str:
    """
    Helper to produce a consistent error description for color fields.
    """
    return (
        f"bulk_operations.{field_name} must be a valid hex color "
        f"(e.g. #0f3 or #00ff30)"
    )


def describe_icon_format(field_name: str) -> str:
    """
    Helper to produce a consistent error description for icon URL fields.
    """
    return (
        f"bulk_operations.{field_name} must be a built-in or uploaded icon path "
        f"(starting with 'assets/' or '/api/icons/')"
    )



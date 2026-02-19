"""
Icon path resolution utilities for export functionality.
"""

from pathlib import Path
from typing import Optional

from geo_lib.processing.icons.get import parse_user_icon_hash
from geo_lib.utils.secure_path import is_path_under_base, secure_path


def resolve_icon_path(icon_url: str, base_dir: str, icon_storage_dir: str) -> Optional[str]:
    """
    Resolve an API icon URL to a filesystem path relative to base_dir.

    Args:
        icon_url: Icon URL like '/api/icons/system/...' or '/api/icons/user/{hash}.png'
        base_dir: Base directory path (e.g., Django BASE_DIR)
        icon_storage_dir: Directory where user icons are stored

    Returns:
        Relative filesystem path from base_dir, or None if invalid/unresolvable
    """
    if not icon_url or not isinstance(icon_url, str):
        return None

    base_path = Path(base_dir)

    # System icons: /api/icons/system/{path} -> assets/icons/{path}
    if icon_url.startswith("/api/icons/system/"):
        relative_path = icon_url.replace("/api/icons/system/", "assets/icons/")

        # Security: Prevent path traversal
        # Check for any .. sequences or absolute paths
        if ".." in relative_path or Path(relative_path).is_absolute():
            return None

        relative_path = secure_path(relative_path)
        full_path = base_path / relative_path

        # Ensure the resolved path is within base_dir/assets/icons/
        assets_icons_dir = base_path / "assets" / "icons"
        if not is_path_under_base(full_path, assets_icons_dir):
            return None

        if full_path.exists() and full_path.is_file():
            return relative_path
        return None

    # User icons: /api/icons/user/{hash}.png -> {ICON_STORAGE_DIR}/{hash[0:2]}/{hash[2:4]}/{hash}.png
    if icon_url.startswith("/api/icons/user/"):
        icon_hash_with_ext = icon_url.replace("/api/icons/user/", "")
        parsed = parse_user_icon_hash(icon_hash_with_ext)
        if not parsed:
            return None
        hash_part, _extension = parsed

        storage_dir = Path(icon_storage_dir)
        icon_path = storage_dir / hash_part[0:2] / hash_part[2:4] / icon_hash_with_ext

        if icon_path.exists() and icon_path.is_file():
            # Security: Only return path if it's within base_dir
            # Reject icons outside base_dir instead of returning absolute path
            try:
                relative = icon_path.relative_to(base_path)
                if not is_path_under_base(icon_path, base_path):
                    return None
                return str(relative)
            except ValueError:
                # Icon is outside base_dir - reject it for security
                return None
        return None

    return None

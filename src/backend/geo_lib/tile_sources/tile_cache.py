"""
Disk-based tile cache: validates/resolves cache paths (bounded to `TILE_CACHE_DIR`) and
reads/writes/expires cached tile bytes. Pure I/O helpers with no HTTP concerns — used by
`api.views.tiles.tile_proxy` to avoid repeatedly fetching the same tiles from upstream tile
servers.
"""
import os
import traceback
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.secure_path import is_path_under_base, secure_filename
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()

TILE_CACHE_EXTENSIONS = frozenset({'pbf', 'png', 'webp', 'jpg', 'tile'})
MAX_TILE_ZOOM = 30

# OSMF / openmaps.fr policy requires at least this many days of caching for their sources.
_OSMF_MIN_CACHE_EXPIRY_DAYS = 7
OSMF_POLICY_SOURCES = frozenset({'osm', 'opentopomap', 'openhikingmap'})


def cache_expiry_days_for_service(service: Optional[str]) -> int:
    """Effective TILE_CACHE_EXPIRY_DAYS for `service`, enforcing the OSMF 7-day minimum for
    sources that require it (osm, opentopomap, openhikingmap)."""
    expiry_days = get_required_setting('TILE_CACHE_EXPIRY_DAYS')
    if service in OSMF_POLICY_SOURCES:
        expiry_days = max(expiry_days, _OSMF_MIN_CACHE_EXPIRY_DAYS)
    return expiry_days


def get_tile_cache_path(service, z, x, y, extension='tile') -> Optional[Path]:
    """
    Generate the cache file path for a tile.
    Validates z/x/y and extension, resolves the path, and ensures it stays under TILE_CACHE_DIR.

    Args:
        service: The tile service name
        z: Zoom level
        x: Tile X coordinate
        y: Tile Y coordinate
        extension: File extension (default: 'tile' for generic)

    Returns:
        Resolved Path under TILE_CACHE_DIR, or None if validation fails
    """
    try:
        z = int(z)
        x = int(x)
        y = int(y)
    except (TypeError, ValueError):
        return None
    if z < 0 or z > MAX_TILE_ZOOM:
        return None
    max_tile = 2**z
    if x < 0 or x >= max_tile or y < 0 or y >= max_tile:
        return None
    if extension not in TILE_CACHE_EXTENSIONS:
        return None
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    service = secure_filename(service)
    if not service:
        service = "tile_service"
    path = cache_dir / service / str(z) / str(x) / f"{y}.{extension}"
    try:
        resolved = path.resolve()
    except (OSError, RuntimeError):
        return None
    if not is_path_under_base(resolved, cache_dir):
        return None
    return resolved


def is_tile_cached(cache_path, service=None) -> bool:
    """
    Check if a tile is cached and not expired.
    Path must resolve under TILE_CACHE_DIR; only the resolved path is used for I/O.

    Args:
        cache_path: Path to the cached tile file
        service: Optional tile service id; if osm/opentopomap/openhikingmap, enforces OSMF minimum 7-day cache.

    Returns:
        True if cached and valid, False otherwise
    """
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return False
    if not is_path_under_base(resolved, cache_dir):
        return False
    try:
        if not resolved.exists():
            return False
    except PermissionError:
        return False

    try:
        file_mtime = datetime.fromtimestamp(resolved.stat().st_mtime)
        expiry_time = timedelta(days=cache_expiry_days_for_service(service))

        if datetime.now() - file_mtime > expiry_time:
            try:
                resolved.unlink()
            except OSError:
                pass
            return False

        return True
    except OSError:
        return False


def ensure_cache_directory(cache_path) -> bool:
    """
    Ensure the cache directory structure exists with proper permissions.
    Path must resolve under TILE_CACHE_DIR; only the resolved path's parent is used for I/O.

    Args:
        cache_path: Path to the cache file (parent directories will be created)

    Returns:
        True if successful, False otherwise
    """
    cache_base = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return False
    if not is_path_under_base(resolved, cache_base):
        return False
    cache_dir = resolved.parent
    try:
        original_umask = os.umask(0o077)
        try:
            cache_dir.mkdir(parents=True, exist_ok=True)
            os.chmod(cache_dir, 0o700)
        finally:
            os.umask(original_umask)
        return True
    except OSError:
        _logger.error(f"Failed to create cache directory {cache_dir}: {traceback.format_exc()}")
        return False


def save_tile_to_cache(cache_path, tile_data) -> bool:
    """
    Save tile data to cache with proper permissions.
    Path must resolve under TILE_CACHE_DIR; only the resolved path is used for I/O.

    Args:
        cache_path: Path where to save the tile
        tile_data: Binary tile data

    Returns:
        True if successful, False otherwise
    """
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return False
    if not is_path_under_base(resolved, cache_dir):
        return False
    try:
        if not ensure_cache_directory(resolved):
            return False

        original_umask = os.umask(0o177)
        try:
            resolved.write_bytes(tile_data)
            os.chmod(resolved, 0o600)
        finally:
            os.umask(original_umask)

        return True
    except OSError:
        _logger.error(f"Failed to save tile to cache {resolved}: {traceback.format_exc()}")
        return False


def read_tile_from_cache(cache_path) -> Optional[bytes]:
    """
    Read tile data from cache.
    Path must resolve under TILE_CACHE_DIR; only the resolved path is used for I/O.

    Args:
        cache_path: Path to the cached tile file

    Returns:
        Binary tile data, or None if read fails or path invalid
    """
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return None
    if not is_path_under_base(resolved, cache_dir):
        return None
    try:
        return resolved.read_bytes()
    except OSError:
        _logger.error(f"Failed to read tile from cache {resolved}: {traceback.format_exc()}")
        return None

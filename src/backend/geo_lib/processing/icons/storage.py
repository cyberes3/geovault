"""
Icon storage: validates, re-encodes, and persists icon bytes to content-addressed
storage using a SHA-256 hash filename.

This module is the storage/encoding boundary for icons — it knows nothing about
where icon bytes come from (KMZ archives, remote URLs, etc.), only how to safely
turn arbitrary bytes into a stored file. See `icon_manager.py` for the fetch/href
resolution orchestration that calls into this module.
"""

import hashlib
import io
import traceback
from typing import Dict, Optional

from PIL import Image, UnidentifiedImageError

from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting
from geo_lib.processing.icons.get import _get_icon_extension, _get_storage_path
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel

_logger = get_tagged_logger()

# Pillow format name for each supported extension, used to re-encode icons before storage.
_PILLOW_FORMAT_BY_EXTENSION = {
    '.png': 'PNG',
    '.jpg': 'JPEG',
    '.jpeg': 'JPEG',
    '.gif': 'GIF',
    '.bmp': 'BMP',
    '.webp': 'WEBP',
    '.ico': 'ICO',
}


def _reencode_raster_icon(icon_data: bytes, extension: str) -> Optional[bytes]:
    """
    Decode `icon_data` with Pillow and re-encode it into a canonical file of the
    format implied by `extension`.

    This guarantees every icon that reaches storage is a genuine, well-formed raster
    image — not, say, an HTML/script polyglot smuggled in under an image extension —
    and strips any non-image trailer or metadata a crafted file might carry. Returns
    None if `icon_data` isn't a valid image Pillow can decode as that format.
    """
    pillow_format = _PILLOW_FORMAT_BY_EXTENSION.get(extension)
    if pillow_format is None:
        return None
    try:
        with Image.open(io.BytesIO(icon_data)) as img:
            img.load()
            if pillow_format == 'JPEG' and img.mode not in ('RGB', 'L'):
                img = img.convert('RGB')
            output = io.BytesIO()
            img.save(output, format=pillow_format)
            return output.getvalue()
    except (UnidentifiedImageError, OSError, ValueError):
        return None


def _is_valid_icon_type(filename_or_url: str) -> bool:
    """
    Check if the file appears to be a valid image type.

    Args:
        filename_or_url: Filename or URL string

    Returns:
        True if valid image type, False otherwise
    """
    ext = _get_icon_extension(filename_or_url)
    return ext is not None


def store_icon(icon_data: bytes, original_path: str, import_log: ImportLog, stats: Dict[str, int]) -> Optional[str]:
    """
    Store icon using SHA-256 hash as filename.

    Args:
        icon_data: Icon file content as bytes
        original_path: Original icon path/URL for extension detection
        import_log: ImportLog for recording user-visible warnings
        stats: Statistics dictionary for tracking icon processing

    Returns:
        Local URL path for icon (e.g., '/api/icons/user/{hash}.png'), or None if storage fails
    """
    try:
        # Validate size
        if len(icon_data) > get_required_setting('ICON_MAX_SIZE_BYTES'):
            _logger.warning(f"Icon exceeds size limit: {len(icon_data)} bytes")
            import_log.add(
                f"Icon exceeds size limit ({len(icon_data)} bytes): {original_path}",
                "Icon Processing",
                DatabaseLogLevel.WARNING
            )
            stats['failed'] += 1
            return None

        # Get extension
        extension = _get_icon_extension(original_path)
        if not extension:
            _logger.warning(f"Invalid icon extension: {original_path}")
            import_log.add(
                f"Invalid icon extension: {original_path}",
                "Icon Processing",
                DatabaseLogLevel.WARNING
            )
            stats['failed'] += 1
            return None

        # Re-encode via Pillow so only genuine, well-formed raster images ever reach
        # storage (rejects e.g. an HTML/script polyglot uploaded under an image extension).
        icon_data = _reencode_raster_icon(icon_data, extension)
        if icon_data is None:
            _logger.warning(f"Icon data is not a valid image for extension {extension}: {original_path}")
            import_log.add(
                f"Icon is not a valid image file: {original_path}",
                "Icon Processing",
                DatabaseLogLevel.WARNING
            )
            stats['failed'] += 1
            return None

        # Calculate hash (of the re-encoded bytes, since that's what's actually stored)
        icon_hash = hashlib.sha256(icon_data).hexdigest()

        # Get storage path
        storage_path = _get_storage_path(icon_hash, extension)

        # Check if already exists
        if not storage_path.exists():
            # Write icon to storage
            storage_path.write_bytes(icon_data)
            _logger.debug(f"Stored icon: {storage_path}")

        # Return URL path
        result = f"/api/icons/user/{icon_hash}{extension}"
        stats['successful'] += 1
        return result

    except Exception:
        error_msg = f"Failed to store icon: {traceback.format_exc()}"
        _logger.error(error_msg)
        import_log.add(
            f"Failed to store icon from {original_path}",
            "Icon Processing",
            DatabaseLogLevel.ERROR
        )
        stats['failed'] += 1
        return None

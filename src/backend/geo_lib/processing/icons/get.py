import io
import os
import traceback
import zipfile
from pathlib import Path
from typing import Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request

from geo_lib.http.outbound import USER_AGENT
from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.security.ssrf import build_ssrf_safe_opener, is_url_safe_for_fetch
from geo_lib.security.zip_utils import MAX_KMZ_ICON_DECOMPRESSED_BYTES, read_zip_member_bounded

_logger = get_tagged_logger()

# Single source of truth for icon file extensions accepted anywhere in the icon
# pipeline (user uploads, KML/KMZ import, remote fetch) and their outbound Content-Type
# for serving. SVG is intentionally excluded: it's an XML format that can carry
# <script>/event-handler payloads, so serving user- or import-influenced SVG content
# as image/svg+xml is a stored-XSS vector. Every other extension here is a raster
# format that geo_lib.processing.icons.icon_manager.store_icon re-encodes with Pillow
# before it's ever written to disk, so only genuine, well-formed images are stored.
ICON_CONTENT_TYPES = {
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.bmp': 'image/bmp',
    '.webp': 'image/webp',
    '.ico': 'image/x-icon',
}
VALID_ICON_EXTENSIONS = frozenset(ICON_CONTENT_TYPES.keys())


def parse_user_icon_hash(icon_hash: str) -> Optional[Tuple[str, str]]:
    """
    Parse and validate a user-provided icon hash string (hash + extension).

    Use for any request-derived value that will be used to build a filesystem path
    for user icons. Ensures the hash is 64 hex chars and extension is allowlisted.

    Args:
        icon_hash: String like '{64-char-hex}.png' (e.g. from URL path or icon URL).

    Returns:
        (hash_part, extension_with_dot) on success, None if invalid.
    """
    if not icon_hash or len(icon_hash) < 5:
        return None
    if "." not in icon_hash:
        return None
    hash_part, ext = icon_hash.rsplit(".", 1)
    extension = "." + ext
    if len(hash_part) != 64:
        return None
    if extension not in VALID_ICON_EXTENSIONS:
        return None
    try:
        int(hash_part, 16)
    except ValueError:
        return None
    return (hash_part, extension)


def _get_icon_extension(filename_or_url: str) -> Optional[str]:
    """
    Extract file extension from filename or URL.

    Args:
        filename_or_url: Filename or URL string

    Returns:
        Extension with leading dot, or None if not found
    """
    # Parse URL or filename
    parsed = urlparse(filename_or_url)
    path = parsed.path or filename_or_url

    # Extract extension
    ext = os.path.splitext(path)[1].lower()
    return ext if ext in VALID_ICON_EXTENSIONS else None


def _get_storage_path(icon_hash: str, extension: str) -> Path:
    """
    Get storage path for icon using hash-based directory structure.

    Args:
        icon_hash: SHA-256 hash of icon content
        extension: File extension with leading dot

    Returns:
        Path object for icon storage location
    """
    storage_dir = Path(get_required_setting('ICON_STORAGE_DIR'))
    # Create subdirectory structure: {hash[0:2]}/{hash[2:4]}/
    subdir = storage_dir / icon_hash[0:2] / icon_hash[2:4]
    subdir.mkdir(parents=True, exist_ok=True)
    return subdir / f"{icon_hash}{extension}"


def extract_icon_from_kmz(kmz_data: bytes, icon_path: str, import_log: ImportLog) -> Optional[bytes]:
    """
    Extract icon from KMZ ZIP archive.

    Args:
        kmz_data: KMZ file content as bytes
        icon_path: Path to icon within KMZ archive (e.g., 'files/icon.png' or 'icon.png')
        import_log: Optional ImportLog for recording user-visible warnings

    Returns:
        Icon data as bytes, or None if extraction fails
    """
    try:
        # Normalize icon path - remove leading :/ or files/ prefix
        normalized_path = icon_path
        if normalized_path.startswith(':/'):
            normalized_path = normalized_path[2:]
        elif normalized_path.startswith('files/'):
            normalized_path = normalized_path[6:]

        # Open KMZ as ZIP archive
        with zipfile.ZipFile(io.BytesIO(kmz_data), 'r') as zip_file:
            # Build list of paths to try (original, normalized, and variations)
            paths_to_try = []

            # 1. Try original path first (as-is)
            if icon_path:
                paths_to_try.append(icon_path)

            # 2. Try normalized path (without files/ or :/)
            if normalized_path and normalized_path != icon_path:
                paths_to_try.append(normalized_path)

            # 3. Try with files/ prefix if not already present
            if not icon_path.startswith('files/') and not icon_path.startswith(':/'):
                paths_to_try.append(f'files/{icon_path}')

            # Try exact matches first
            for path in paths_to_try:
                if path in zip_file.namelist():
                    return read_zip_member_bounded(zip_file, path, MAX_KMZ_ICON_DECOMPRESSED_BYTES)

            # Try case-insensitive search on all paths
            for path in paths_to_try:
                path_lower = path.lower()
                for entry_name in zip_file.namelist():
                    if entry_name.lower() == path_lower:
                        return read_zip_member_bounded(zip_file, entry_name, MAX_KMZ_ICON_DECOMPRESSED_BYTES)

            # Icon not found
            import_log.add(
                f"Icon not found in KMZ archive: {icon_path}",
                "Icon Processing",
                DatabaseLogLevel.WARNING
            )
            return None

    except zipfile.BadZipFile:
        import_log.add(
            f"Invalid KMZ archive when extracting icon: {icon_path}",
            "Icon Processing",
            DatabaseLogLevel.WARNING
        )
        return None
    except:
        error_msg = f"Failed to extract icon from KMZ: {traceback.format_exc()}"
        _logger.error(error_msg)
        import_log.add(
            f"Failed to extract icon from KMZ archive: {icon_path}",
            "Icon Processing",
            DatabaseLogLevel.WARNING
        )
        return None


def fetch_remote_icon(url: str, timeout: float, import_log: ImportLog) -> Optional[bytes]:
    """
    Fetch icon from remote URL with timeout.
    SSRF-safe: only http/https, blocks private/loopback/link-local IPs; redirects validated.
    """
    if not is_url_safe_for_fetch(url):
        _logger.warning(f"Icon URL not allowed for fetch (SSRF): {url[:80]!r}")
        import_log.add(
            "Icon URL not allowed for security reasons",
            "Icon Processing",
            DatabaseLogLevel.WARNING,
        )
        return None

    try:
        req = Request(url, headers={"User-Agent": USER_AGENT})
        opener = build_ssrf_safe_opener()
        with opener.open(req, timeout=timeout) as response:
            # Check content length if available
            content_length = response.headers.get('Content-Length')
            if content_length:
                size = int(content_length)
                if size > get_required_setting('ICON_MAX_SIZE_BYTES'):
                    _logger.warning(f"Icon exceeds size limit: {url} ({size} bytes)")
                    import_log.add(
                        f"Icon exceeds size limit ({size} bytes): {url}",
                        "Icon Processing",
                        DatabaseLogLevel.WARNING
                    )
                    return None

            # Read data with size limit
            icon_data = b''
            max_size = get_required_setting('ICON_MAX_SIZE_BYTES')
            chunk_size = min(8192, max_size)

            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                icon_data += chunk
                if len(icon_data) > max_size:
                    _logger.warning(f"Icon exceeds size limit during download: {url}")
                    import_log.add(
                        f"Icon exceeds size limit during download: {url}",
                        "Icon Processing",
                        DatabaseLogLevel.WARNING
                    )
                    return None

            return icon_data

    except HTTPError as e:
        _logger.warning(f"HTTP error fetching icon: {url} - {e.code}")
        import_log.add(
            f"HTTP error fetching icon (code {e.code}): {url}",
            "Icon Processing",
            DatabaseLogLevel.WARNING
        )
        return None
    except URLError:
        error_msg = f"URL error fetching icon: {url} - {traceback.format_exc()}"
        _logger.warning(error_msg)
        import_log.add(
            f"URL error fetching icon: {url}",
            "Icon Processing",
            DatabaseLogLevel.WARNING
        )
        return None
    except TimeoutError:
        _logger.warning(f"Timeout fetching icon: {url}")
        import_log.add(
            f"Timeout fetching icon: {url}",
            "Icon Processing",
            DatabaseLogLevel.WARNING
        )
        return None
    except:
        error_msg = f"Failed to fetch remote icon: {url} - {traceback.format_exc()}"
        _logger.error(error_msg)
        import_log.add(
            f"Failed to fetch remote icon: {url}",
            "Icon Processing",
            DatabaseLogLevel.ERROR
        )
        return None

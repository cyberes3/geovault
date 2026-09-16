"""Shared helpers for safely reading members out of untrusted ZIP archives (KMZ)."""
import zipfile

from geo_lib.security.exceptions import SecurityError

# Zip entries declare their own uncompressed size in the central directory, but that
# field can be forged independently of the actual compressed data, so it can't be
# trusted as a size check on its own. This cap bounds the actual decompressed bytes
# read from a KMZ icon member, regardless of what the archive claims.
# Embedded KML uses get_max_file_size(FileType.KML) via read_kmz_kml_member.
MAX_KMZ_ICON_DECOMPRESSED_BYTES = 10 * 1024 * 1024  # 10MB; generous ceiling for an embedded icon image

_READ_CHUNK_BYTES = 64 * 1024


def read_zip_member_bounded(zip_file: zipfile.ZipFile, name: str, max_bytes: int) -> bytes:
    """
    Read a member from an open ZipFile, streaming in chunks and enforcing `max_bytes`
    as a hard cap on the decompressed size actually produced — independent of the
    (spoofable) uncompressed-size field in the archive's central directory.

    Raises:
        SecurityError: If the decompressed content exceeds `max_bytes`.
    """
    chunks = []
    total = 0
    with zip_file.open(name) as member:
        while True:
            chunk = member.read(_READ_CHUNK_BYTES)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                raise SecurityError(
                    f"Decompressed content of '{name}' in ZIP archive exceeds the {max_bytes} byte limit"
                )
            chunks.append(chunk)
    return b"".join(chunks)

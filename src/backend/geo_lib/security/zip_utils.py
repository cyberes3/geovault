"""Shared helpers for safely reading members out of untrusted ZIP archives (KMZ)."""
import zipfile

from geo_lib.security.exceptions import SecurityError

# Zip entries declare their own uncompressed size in the central directory, but that
# field can be forged independently of the actual compressed data, so it can't be
# trusted as a size check on its own. These caps bound the actual decompressed bytes
# read from a KMZ member, regardless of what the archive claims, to guard against
# decompression-bomb payloads (a tiny compressed entry that expands to gigabytes).
MAX_KMZ_KML_DECOMPRESSED_BYTES = 200 * 1024 * 1024  # 200MB; KML is text and compresses well
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

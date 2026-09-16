"""Read an embedded KML document from a KMZ archive, capped at the KML content limit."""
import zipfile

from geo_lib.processing.file_types import FileType, get_max_file_size
from geo_lib.security.exceptions import FileValidationError
from geo_lib.security.zip_utils import read_zip_member_bounded


def read_kmz_kml_member(kmz: zipfile.ZipFile, name: str) -> str:
    """
    Return the UTF-8 KML member `name` from an open KMZ.

    Rejects a declared uncompressed size above the KML content limit, then
    stream-decompresses with that same limit as the hard cap so a forged
    ZipInfo.file_size cannot expand past it.
    """
    kml_size_limit = get_max_file_size(FileType.KML)
    info = kmz.getinfo(name)
    if info.file_size > kml_size_limit:
        kml_size_mb = info.file_size / (1024 * 1024)
        kml_limit_mb = kml_size_limit / (1024 * 1024)
        raise FileValidationError(
            f"Embedded KML file too large: {kml_size_mb:.1f}MB exceeds {kml_limit_mb:.0f}MB limit for KML content"
        )
    return read_zip_member_bounded(kmz, name, kml_size_limit).decode('utf-8')

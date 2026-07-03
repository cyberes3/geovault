"""
Tests for geo_lib.security.zip_utils.read_zip_member_bounded.

This is the primitive that protects KMZ processing (KML extraction and embedded icon
extraction) against decompression-bomb archives: a tiny compressed entry that expands
to gigabytes when read in full. It works by streaming the decompressed output in chunks
and aborting as soon as the running total exceeds the caller-supplied limit, rather than
trusting the (spoofable) uncompressed-size field in the ZIP central directory.
"""
import zipfile
from io import BytesIO

import pytest

from geo_lib.security.exceptions import SecurityError
from geo_lib.security.zip_utils import (
    MAX_KMZ_ICON_DECOMPRESSED_BYTES,
    MAX_KMZ_KML_DECOMPRESSED_BYTES,
    read_zip_member_bounded,
)


def _make_zip(name: str, content: bytes) -> zipfile.ZipFile:
    buf = BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(name, content)
    buf.seek(0)
    return zipfile.ZipFile(buf, 'r')


class TestReadZipMemberBounded:
    def test_reads_small_member_fully(self):
        """Content well under the limit is read back byte-for-byte."""
        content = b'<?xml version="1.0"?><kml></kml>'
        with _make_zip('doc.kml', content) as zf:
            result = read_zip_member_bounded(zf, 'doc.kml', max_bytes=1024)
        assert result == content

    def test_reads_member_exactly_at_limit(self):
        """A member whose decompressed size exactly equals max_bytes is allowed."""
        content = b'A' * 1000
        with _make_zip('doc.kml', content) as zf:
            result = read_zip_member_bounded(zf, 'doc.kml', max_bytes=1000)
        assert result == content

    def test_rejects_member_exceeding_limit_by_one_byte(self):
        """A member one byte over max_bytes is rejected, not silently truncated."""
        content = b'A' * 1001
        with _make_zip('doc.kml', content) as zf:
            with pytest.raises(SecurityError):
                read_zip_member_bounded(zf, 'doc.kml', max_bytes=1000)

    def test_rejects_decompression_bomb(self):
        """
        A small compressed payload that decompresses far beyond max_bytes is rejected
        without ever materializing the full decompressed content in memory — this is
        the actual decompression-bomb scenario the cap defends against, not just a
        large-but-honest file.
        """
        # Highly compressible: 20MB of zeros compresses down to a few KB, but we cap
        # at 1MB so the bomb should be caught almost immediately.
        bomb_content = b'\x00' * (20 * 1024 * 1024)
        with _make_zip('doc.kml', bomb_content) as zf:
            # Sanity check the archive really did compress the bomb down small.
            info = zf.getinfo('doc.kml')
            assert info.compress_size < 1024 * 1024

            with pytest.raises(SecurityError, match=r'exceeds the \d+ byte limit'):
                read_zip_member_bounded(zf, 'doc.kml', max_bytes=1 * 1024 * 1024)

    def test_raises_for_missing_member(self):
        """Reading a name that isn't in the archive raises (KeyError from zipfile), not
        a silent empty result."""
        with _make_zip('doc.kml', b'content') as zf:
            with pytest.raises(KeyError):
                read_zip_member_bounded(zf, 'missing.kml', max_bytes=1024)

    def test_kml_and_icon_limits_are_distinct_and_reasonable(self):
        """The KML cap is generous (text compresses well) while the icon cap is tighter
        (a single embedded image); both must be well under any plausible legitimate size
        while still large enough not to reject real content."""
        assert MAX_KMZ_KML_DECOMPRESSED_BYTES == 200 * 1024 * 1024
        assert MAX_KMZ_ICON_DECOMPRESSED_BYTES == 10 * 1024 * 1024
        assert MAX_KMZ_ICON_DECOMPRESSED_BYTES < MAX_KMZ_KML_DECOMPRESSED_BYTES

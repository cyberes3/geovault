"""
Unit tests for icon path validation (path-injection / py/path-injection).
Tests parse_user_icon_hash and related behavior.
"""
import pytest

from geo_lib.processing.icons.get import parse_user_icon_hash, VALID_ICON_EXTENSIONS


class TestParseUserIconHash:
    """Adversarial and edge-case tests for parse_user_icon_hash."""

    @pytest.mark.parametrize('icon_hash', [
        ('../' * 21 + 'x') + '.png',
        '..\\..\\..\\etc\\passwd.png',
        'a' * 62 + '/.png',
        'a' * 62 + '..png',
    ])
    def test_rejects_path_traversal_like_hash(self, icon_hash):
        """Hash part must be 64 hex chars; path-like segments rejected."""
        assert parse_user_icon_hash(icon_hash) is None

    @pytest.mark.parametrize('icon_hash', [
        'g' * 64 + '.png',
        'a' * 63 + 'z' + '.png',
        'a' * 63 + 'Z' + '.png',
        '0' * 63 + 'x' + '.png',
    ])
    def test_rejects_non_hex_characters(self, icon_hash):
        """Only 0-9 and a-f allowed in hash part."""
        assert parse_user_icon_hash(icon_hash) is None

    @pytest.mark.parametrize('icon_hash', [
        'a' * 63 + '.png',
        'a' * 65 + '.png',
        'a' * 60 + '.png',
    ])
    def test_rejects_wrong_hash_length(self, icon_hash):
        """Hash part must be exactly 64 characters."""
        assert parse_user_icon_hash(icon_hash) is None

    @pytest.mark.parametrize('icon_hash', [
        'a' * 64 + '.exe',
        'a' * 64 + '.php',
        'a' * 64 + '.png.png',
    ])
    def test_rejects_invalid_extension(self, icon_hash):
        """Only allowlisted extensions accepted."""
        assert parse_user_icon_hash(icon_hash) is None

    @pytest.mark.parametrize('icon_hash', [
        '',
        'a',
        'ab.png',
        'no_dot',
        '.png',
    ])
    def test_rejects_empty_or_no_extension(self, icon_hash):
        """Need at least hash + extension and a dot."""
        assert parse_user_icon_hash(icon_hash) is None

    @pytest.mark.parametrize('ext', ['.png', '.jpg', '.jpeg', '.gif', '.ico', '.webp', '.bmp', '.svg'])
    def test_accepts_valid_hex_and_allowlisted_extension(self, ext):
        """Valid 64-char hex + allowlisted extension returns (hash_part, extension)."""
        hash_part = 'a' * 64
        icon_hash = hash_part + ext
        result = parse_user_icon_hash(icon_hash)
        assert result is not None
        h, e = result
        assert h == hash_part
        assert e == ext
        assert e in VALID_ICON_EXTENSIONS

    def test_accepts_uppercase_hex(self):
        """Uppercase A-F is valid hex."""
        result = parse_user_icon_hash('A' * 64 + '.png')
        assert result is not None
        assert result[0] == 'A' * 64
        assert result[1] == '.png'
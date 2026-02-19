"""
Adversarial and unit tests for export icon path resolution (resolve_icon_path).
Ensures path traversal and cross-directory access are rejected via is_path_under_base.
"""
import pytest
import tempfile
from pathlib import Path

from geo_lib.export.icon_resolver import resolve_icon_path


class TestResolveIconPathAdversarial:
    """Adversarial tests: path traversal and paths outside allowed base must return None."""

    def test_system_icon_path_traversal_returns_none(self):
        """System icon URL with .. must not resolve to any path."""
        with tempfile.TemporaryDirectory() as tmpdir:
            base = Path(tmpdir)
            (base / "assets" / "icons").mkdir(parents=True)
            malicious_urls = [
                "/api/icons/system/../../../etc/passwd",
                "/api/icons/system/..%2F..%2F..%2Fetc%2Fpasswd",
                "/api/icons/system/caltopo/../../secret.png",
                "/api/icons/system/./../../outside.png",
            ]
            for url in malicious_urls:
                result = resolve_icon_path(url, str(base), str(base / "data" / "icons"))
                assert result is None, f"Expected None for: {url}"

    def test_system_icon_resolved_path_must_be_under_assets_icons(self):
        """Even after secure_path, resolved path must be under base_dir/assets/icons."""
        with tempfile.TemporaryDirectory() as tmpdir:
            base = Path(tmpdir)
            icons_dir = base / "assets" / "icons"
            icons_dir.mkdir(parents=True)
            # Valid file inside assets/icons
            (icons_dir / "valid.png").write_text("x")
            # Request valid file: should succeed
            result = resolve_icon_path(
                "/api/icons/system/valid.png", str(base), str(base / "data" / "icons"))
            assert result == "assets/icons/valid.png"
            # Request path that would escape (.. in URL form)
            result_bad = resolve_icon_path(
                "/api/icons/system/../valid.png", str(base), str(base / "data" / "icons"))
            assert result_bad is None

    def test_user_icon_invalid_hash_returns_none(self):
        """User icon URL with invalid hash (e.g. path traversal in hash) returns None."""
        with tempfile.TemporaryDirectory() as tmpdir:
            base = Path(tmpdir)
            storage = base / "icons"
            storage.mkdir()
            # parse_user_icon_hash rejects these; resolve_icon_path should return None
            bad_urls = [
                "/api/icons/user/" + "a" * 62 + "../.png",
                "/api/icons/user/" + ".." * 32 + ".png",
            ]
            for url in bad_urls:
                result = resolve_icon_path(url, str(base), str(storage))
                assert result is None, f"Expected None for: {url}"

import pytest

from geo_lib.sharing.social_preview import PreviewKey, hash_extent, metadata_from_share_info


def test_preview_key_includes_token_source_and_extent():
    extent_hash = hash_extent((1.0, 2.0, 3.0, 4.0))
    key = PreviewKey("token-a", "osm", extent_hash)
    assert key.cache_key() == f"social_preview_png:token-a:osm:{extent_hash}"
    assert hash_extent((1.0, 2.0, 3.0, 4.0)) != hash_extent((1.1, 2.0, 3.0, 4.0))


def test_preview_key_requires_every_field():
    with pytest.raises(ValueError):
        PreviewKey("", "osm", "abc")
    with pytest.raises(ValueError):
        PreviewKey("token-a", "", "abc")
    with pytest.raises(ValueError):
        PreviewKey("token-a", "osm", "")


def test_metadata_from_share_info_uses_resolver_fields():
    assert metadata_from_share_info({"share_type": "tag", "tag": "trail"})["title"] == "Shared Map: trail"
    assert metadata_from_share_info({"share_type": "feature", "feature_name": "Camp"})["title"] == "Shared Map: Camp"
    assert metadata_from_share_info({"share_type": "live_track", "track_name": "Hike"})["title"] == "Shared Track: Hike"

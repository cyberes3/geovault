from geo_lib.sharing.share_url import (
    MAP_SOCIAL_PREFIX,
    TRACK_SOCIAL_PREFIX,
    ShareUrl,
)


def test_map_and_track_relative_urls():
    share_id = "f8a918ab-7f53-4ef3-be11-a957c40ebd02"
    assert ShareUrl.map_social(share_id) == f"{MAP_SOCIAL_PREFIX}{share_id}/"
    assert ShareUrl.map_spa(share_id) == f"/#/mapshare?id={share_id}"
    assert ShareUrl.track_social(share_id) == f"{TRACK_SOCIAL_PREFIX}{share_id}/"
    assert ShareUrl.track_spa(share_id) == f"/#/extensions/live-track/share?id={share_id}"
    assert ShareUrl.for_link("map", share_id) == ShareUrl.map_social(share_id)
    assert ShareUrl.for_link("live_track", share_id) == ShareUrl.track_spa(share_id)
    assert ShareUrl.for_link("live_track", share_id, "world") == ShareUrl.track_social(share_id)
    assert ShareUrl.for_link("live_track", share_id, "authenticated") == ShareUrl.track_spa(share_id)


def test_remap_pathname_to_hash():
    share_id = "f8a918ab-7f53-4ef3-be11-a957c40ebd02"
    assert ShareUrl.remap_pathname_to_hash(f"/share/map/{share_id}/") == f"/#/mapshare?id={share_id}"
    assert ShareUrl.remap_pathname_to_hash(f"/share/track/{share_id}/") == (
        f"/#/extensions/live-track/share?id={share_id}"
    )
    assert ShareUrl.remap_pathname_to_hash(f"/share/map/{share_id}/", "#/already") is None
    assert ShareUrl.remap_pathname_to_hash("/not-a-share/") is None


def test_parse_social_paths_require_uuid4():
    share_id = "f8a918ab-7f53-4ef3-be11-a957c40ebd02"
    assert ShareUrl.parse_map_social_path(f"/share/map/{share_id}/") == share_id
    assert ShareUrl.parse_track_social_path(f"/share/track/{share_id}") == share_id
    assert ShareUrl.parse_map_social_path("/share/map/not-a-uuid/") is None

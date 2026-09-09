from types import SimpleNamespace

from geo_lib.sharing.access_policy import (
    AccessContext,
    MapWorldPolicy,
    TrackerInternalPolicy,
    TrackerWorldPolicy,
    policy_for,
    strip_account_pii,
)
from geo_lib.sharing.constants import ACTION_ELEVATIONS, ACTION_FEATURES, ACTION_INFO, ACTION_TRACK


def _link(**overrides):
    values = {
        "token": "f8a918ab-7f53-4ef3-be11-a957c40ebd02",
        "domain": "map",
        "subject_kind": "tag",
        "subject_ref": "public-tag",
        "audience": "world",
        "capabilities": {"allow_downloads": True, "include_tags": True},
        "revoked_at": None,
        "owner_id": 1,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def test_map_world_policy_is_token_possession():
    policy = MapWorldPolicy()
    link = _link()
    assert policy.can_discover(link, AccessContext())
    assert policy.can_read_data(link, AccessContext())
    assert policy.download_allowed(link)
    assert policy.include_user_tags(link)
    assert policy.counts_access(ACTION_FEATURES)
    assert not policy.counts_access(ACTION_INFO)
    assert not policy.counts_access(ACTION_ELEVATIONS)


def test_map_world_policy_rejects_revoked_or_authenticated_audience():
    policy = MapWorldPolicy()
    assert not policy.can_discover(_link(revoked_at="now"), AccessContext())
    assert not policy.can_discover(_link(audience="authenticated"), AccessContext())


def test_tracker_world_policy_redacts_account_fields():
    policy = TrackerWorldPolicy()
    link = _link(domain="live_track", subject_kind="live_track", audience="world")
    assert policy.can_read_data(link, AccessContext())
    redacted = policy.redact({
        "track_name": "Demo",
        "owner_email": "owner@example.com",
        "shared_with_emails": ["a@example.com"],
    })
    assert redacted == {"track_name": "Demo"}


def test_tracker_internal_requires_authenticated_allowed_viewer():
    policy = TrackerInternalPolicy()
    link = _link(domain="live_track", subject_kind="live_track", audience="authenticated")
    guest = AccessContext(user=SimpleNamespace(is_authenticated=False))
    assert not policy.can_discover(link, guest)

    owner = AccessContext(user=SimpleNamespace(is_authenticated=True), is_owner=True)
    assert policy.can_discover(link, owner)

    grantee = AccessContext(user=SimpleNamespace(is_authenticated=True), is_grantee=True)
    assert policy.can_discover(link, grantee)

    catalog = AccessContext(user=SimpleNamespace(is_authenticated=True), visibility="public")
    assert policy.can_discover(link, catalog)

    stranger = AccessContext(user=SimpleNamespace(is_authenticated=True), visibility="shared")
    assert not policy.can_discover(link, stranger)


def test_policy_for_dispatches_on_domain_and_audience():
    assert isinstance(policy_for(_link()), MapWorldPolicy)
    assert isinstance(policy_for(_link(domain="live_track", audience="world")), TrackerWorldPolicy)
    assert isinstance(
        policy_for(_link(domain="live_track", audience="authenticated")),
        TrackerInternalPolicy,
    )


def test_strip_account_pii_walks_nested_payloads():
    payload = {
        "tracks": [{"track_name": "A", "email": "hidden@example.com", "user_id": 9}],
        "owner_id": 3,
        "ok": True,
    }
    assert strip_account_pii(payload) == {"tracks": [{"track_name": "A"}], "ok": True}


def test_tracker_world_counts_track_not_info():
    policy = TrackerWorldPolicy()
    assert policy.counts_access(ACTION_TRACK)
    assert not policy.counts_access(ACTION_INFO)

from api.models import Collection, FeatureStore
from api.sharing.models import ShareLink
from extensions.live_track.src.backend.models import LiveTrack, LiveTrackGroup
from geo_lib.sharing.constants import (
    CAP_ALLOW_DOWNLOADS,
    CAP_INCLUDE_TAGS,
    KIND_COLLECTION,
    KIND_FEATURE,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
    KIND_TAG,
)
from geo_lib.sharing.contracts import ShareListItem
from geo_lib.sharing.share_url import ShareUrl
from pydantic import TypeAdapter

ShareListItemAdapter = TypeAdapter(ShareListItem)


def capabilities_of(link: ShareLink) -> dict:
    caps = link.capabilities or {}
    return {
        "include_tags": bool(caps.get(CAP_INCLUDE_TAGS)),
        "allow_downloads": bool(caps.get(CAP_ALLOW_DOWNLOADS)),
    }


def owner_item_dict(link: ShareLink, *, extra: dict | None = None) -> dict:
    caps = capabilities_of(link)
    payload = {
        "share_type": link.subject_kind,
        "share_id": link.token,
        "url": ShareUrl.for_link(link.domain, link.token, link.audience),
        "created_at": link.created_at.isoformat(),
        "access_count": link.access_count,
        "audience": link.audience,
        "domain": link.domain,
        **caps,
    }
    if extra:
        payload.update(extra)
    return ShareListItemAdapter.validate_python(payload).model_dump(mode="json")


def extra_fields_for_links(links: list[ShareLink]) -> dict[int, dict]:
    extras: dict[int, dict] = {}
    tag_links = [link for link in links if link.subject_kind == KIND_TAG]
    collection_ids = [link.subject_ref for link in links if link.subject_kind == KIND_COLLECTION]
    feature_ids = [link.subject_ref for link in links if link.subject_kind == KIND_FEATURE]
    track_ids = [link.subject_ref for link in links if link.subject_kind == KIND_LIVE_TRACK]
    group_ids = [link.subject_ref for link in links if link.subject_kind == KIND_LIVE_TRACK_GROUP]

    for link in tag_links:
        extras[link.pk] = {"tag": link.subject_ref}

    if collection_ids:
        names = {
            str(collection.id): collection.name
            for collection in Collection.objects.filter(id__in=collection_ids)
        }
        for link in links:
            if link.subject_kind == KIND_COLLECTION:
                extras[link.pk] = {
                    "collection_id": str(link.subject_ref),
                    "collection_name": names.get(str(link.subject_ref), ""),
                }

    if feature_ids:
        features = {
            feature.id: feature
            for feature in FeatureStore.objects.filter(id__in=feature_ids)
        }
        for link in links:
            if link.subject_kind == KIND_FEATURE:
                feature = features.get(int(link.subject_ref))
                extras[link.pk] = {
                    "feature_id": int(link.subject_ref),
                    "feature_name": _feature_name(feature),
                }

    if track_ids or group_ids:
        tracks = {
            str(track.id): track
            for track in LiveTrack.objects.filter(id__in=track_ids)
        } if track_ids else {}
        groups = {
            str(group.id): group
            for group in LiveTrackGroup.objects.filter(id__in=group_ids)
        } if group_ids else {}
        for link in links:
            if link.subject_kind == KIND_LIVE_TRACK:
                track = tracks.get(str(link.subject_ref))
                extras[link.pk] = {
                    "track_id": str(link.subject_ref),
                    "track_name": track.name if track is not None else "",
                }
            elif link.subject_kind == KIND_LIVE_TRACK_GROUP:
                group = groups.get(str(link.subject_ref))
                extras[link.pk] = {
                    "group_id": str(link.subject_ref),
                    "group_name": group.name if group is not None else "",
                }

    return extras


def serialize_owner_links(links: list[ShareLink]) -> list[dict]:
    extras = extra_fields_for_links(links)
    return [owner_item_dict(link, extra=extras.get(link.pk)) for link in links]


def _feature_name(feature) -> str:
    if feature is None:
        return "Unnamed Feature"
    return (feature.geojson or {}).get("properties", {}).get("name") or "Unnamed Feature"

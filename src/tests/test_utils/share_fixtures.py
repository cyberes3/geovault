import uuid

from django.contrib.gis.geos import Point

from api.models import Collection, CollectionFeatureMembership, CollectionTagRule, FeatureStore
from api.sharing.models import ShareGrant, ShareLink
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.tags.tag_writer import TagWriter
from geo_lib.sharing.constants import (
    AUDIENCE_AUTHENTICATED,
    AUDIENCE_WORLD,
    DOMAIN_LIVE_TRACK,
    DOMAIN_MAP,
    KIND_COLLECTION,
    KIND_FEATURE,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
    KIND_TAG,
)


SHARE_TEST_LON = 1.0
SHARE_TEST_LAT = 2.0
SHARE_TEST_BBOX = "0,1,3,4"


def capabilities(*, allow_downloads=False, include_tags=False):
    return {
        "allow_downloads": bool(allow_downloads),
        "include_tags": bool(include_tags),
    }


def create_share_feature(user, *, name="Test Feature", tags=None, lon=SHARE_TEST_LON, lat=SHARE_TEST_LAT, geojson=None, geometry=None):
    if geojson is None:
        geojson = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [lon, lat, 0.0]},
            "properties": {"name": name, "tags": list(tags or [])},
        }
    feature = FeatureStore.objects.create(
        user=user,
        geojson=geojson,
        geometry=geometry or Point(lon, lat, 0.0),
        geojson_hash=generate_geojson_hash(geojson),
    )
    TagWriter.reindex_feature(feature)
    return feature


def index_feature_tags(*features):
    for feature in features:
        TagWriter.reindex_feature(feature)
    return features


def create_owned_collection(user, name, *, tags=None, features=None, description=None):
    collection = Collection.objects.create(user=user, name=name, description=description)
    for tag in tags or []:
        CollectionTagRule.objects.create(collection=collection, tag=tag)
    for feature in features or []:
        CollectionFeatureMembership.objects.create(collection=collection, feature=feature)
    return collection


def create_tag_share(
    user,
    tag,
    *,
    token=None,
    allow_downloads=False,
    include_tags=False,
    access_count=0,
):
    return ShareLink.objects.create(
        token=token or str(uuid.uuid4()),
        owner=user,
        domain=DOMAIN_MAP,
        subject_kind=KIND_TAG,
        subject_ref=tag,
        audience=AUDIENCE_WORLD,
        capabilities=capabilities(allow_downloads=allow_downloads, include_tags=include_tags),
        access_count=access_count,
    )


def create_collection_share(
    user,
    collection,
    *,
    token=None,
    allow_downloads=False,
    include_tags=False,
    access_count=0,
):
    return ShareLink.objects.create(
        token=token or str(uuid.uuid4()),
        owner=user,
        domain=DOMAIN_MAP,
        subject_kind=KIND_COLLECTION,
        subject_ref=str(collection.id),
        audience=AUDIENCE_WORLD,
        capabilities=capabilities(allow_downloads=allow_downloads, include_tags=include_tags),
        access_count=access_count,
    )


def create_feature_share(
    user,
    feature,
    *,
    token=None,
    allow_downloads=False,
    include_tags=False,
    access_count=0,
):
    return ShareLink.objects.create(
        token=token or str(uuid.uuid4()),
        owner=user,
        domain=DOMAIN_MAP,
        subject_kind=KIND_FEATURE,
        subject_ref=feature.id,
        audience=AUDIENCE_WORLD,
        capabilities=capabilities(allow_downloads=allow_downloads, include_tags=include_tags),
        access_count=access_count,
    )


def create_track_link(track, audience, *, token=None, owner=None):
    return ShareLink.objects.create(
        token=token or str(uuid.uuid4()),
        owner=owner or track.user,
        domain=DOMAIN_LIVE_TRACK,
        subject_kind=KIND_LIVE_TRACK,
        subject_ref=str(track.id),
        audience=audience,
        capabilities=capabilities(),
    )


def create_group_link(group, audience, *, token=None, owner=None):
    return ShareLink.objects.create(
        token=token or str(uuid.uuid4()),
        owner=owner or group.user,
        domain=DOMAIN_LIVE_TRACK,
        subject_kind=KIND_LIVE_TRACK_GROUP,
        subject_ref=str(group.id),
        audience=audience,
        capabilities=capabilities(),
    )


def create_track_world_link(track, *, token=None):
    return create_track_link(track, AUDIENCE_WORLD, token=token)


def create_track_internal_link(track, *, token=None):
    return create_track_link(track, AUDIENCE_AUTHENTICATED, token=token)


def create_group_world_link(group, *, token=None):
    return create_group_link(group, AUDIENCE_WORLD, token=token)


def create_group_internal_link(group, *, token=None):
    return create_group_link(group, AUDIENCE_AUTHENTICATED, token=token)


def grant_track(track, user):
    return ShareGrant.objects.create(
        resource_kind=KIND_LIVE_TRACK,
        resource_id=str(track.id),
        grantee_user=user,
    )


def grant_group(group, user):
    return ShareGrant.objects.create(
        resource_kind=KIND_LIVE_TRACK_GROUP,
        resource_id=str(group.id),
        grantee_user=user,
    )


def track_has_grant(track, user) -> bool:
    return ShareGrant.objects.for_track(track.id).filter(grantee_user=user).exists()


def group_has_grant(group, user) -> bool:
    return ShareGrant.objects.for_group(group.id).filter(grantee_user=user).exists()


def track_has_any_grant(track) -> bool:
    return ShareGrant.objects.for_track(track.id).exists()


def group_has_any_grant(group) -> bool:
    return ShareGrant.objects.for_group(group.id).exists()


def track_has_world_link(track) -> bool:
    return ShareLink.objects.for_track(track.id).world().exists()


def track_has_internal_link(track, token=None) -> bool:
    queryset = ShareLink.objects.for_track(track.id).authenticated()
    if token is not None:
        queryset = queryset.filter(token=token)
    return queryset.exists()


def group_has_world_link(group) -> bool:
    return ShareLink.objects.for_group(group.id).world().exists()


def group_has_internal_link(group, token=None) -> bool:
    queryset = ShareLink.objects.for_group(group.id).authenticated()
    if token is not None:
        queryset = queryset.filter(token=token)
    return queryset.exists()

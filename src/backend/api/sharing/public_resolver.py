from django.contrib.gis.db.models.aggregates import Extent
from django.db.models import F
from django.http import JsonResponse
from django.utils import timezone

from api.models import Collection, FeatureStore
from api.services.feature_serialization import geojson_feature_from_instance
from api.sharing.grants import ShareGrantService
from api.sharing.models import ShareLink
from api.utils.format_encoding import create_bbox_response
from api.views.features.bbox.execution import get_features_in_bbox
from api.views.features.bbox.params import _validate_bbox_params
from api.views.features.bbox.response import _build_bbox_response
from api.views.features.retrieval import _extract_coordinates_with_elevation_from_geojson
from extensions.live_track.src.backend.access import world_visible_group_tracks
from extensions.live_track.src.backend.helpers import (
    track_to_response,
    visible_group_track_ids_for_user,
)
from extensions.live_track.src.backend.models import LiveTrack, LiveTrackGroup, LiveTrackGroupSubscription
from geo_lib.collections.membership import CollectionMembership
from geo_lib.sharing.access_policy import AccessContext, policy_for
from geo_lib.sharing.constants import (
    ACTION_FEATURES,
    ACTION_TRACK,
    AUDIENCE_AUTHENTICATED,
    AUDIENCE_WORLD,
    KIND_COLLECTION,
    KIND_FEATURE,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
    KIND_TAG,
    MAP_KINDS,
    TRACKER_KINDS,
)
from geo_lib.sharing.contracts import PublicShare
from geo_lib.sharing.errors import InvalidShareLink, ShareError, ShareForbidden, ShareUnauthorized
from geo_lib.sharing.share_id import ShareId
from geo_lib.tags.tag_query import TagQuery, TagQueryError
from geo_lib.tags.tag_set import TagSet
from pydantic import TypeAdapter

PublicShareAdapter = TypeAdapter(PublicShare)


class PublicShareResolver:
    @staticmethod
    def parse_token(token: str) -> ShareId:
        parsed = ShareId.parse(token)
        if parsed is None:
            raise InvalidShareLink()
        return parsed

    @staticmethod
    def get_active_link(token: str) -> ShareLink:
        parsed = PublicShareResolver.parse_token(token)
        link = ShareLink.objects.active().filter(token=parsed.value).select_related("owner").first()
        if link is None:
            raise InvalidShareLink()
        return link

    @staticmethod
    def authorize(link: ShareLink, user, *, need_data: bool) -> AccessContext:
        policy = policy_for(link)
        context = PublicShareResolver.access_context(link, user)
        if link.audience == AUDIENCE_AUTHENTICATED and not getattr(user, "is_authenticated", False):
            raise ShareUnauthorized()
        allowed = policy.can_read_data(link, context) if need_data else policy.can_discover(link, context)
        if not allowed:
            raise InvalidShareLink()
        return context

    @staticmethod
    def access_context(link: ShareLink, user) -> AccessContext:
        if link.subject_kind not in TRACKER_KINDS:
            return AccessContext(user=user)
        subject = PublicShareResolver._tracker_subject(link)
        is_authenticated = bool(user is not None and getattr(user, "is_authenticated", False))
        is_owner = is_authenticated and subject.user_id == user.id
        is_grantee = ShareGrantService.has_grant(link.subject_kind, link.subject_ref, user)
        return AccessContext(
            user=user,
            visibility=getattr(subject, "visibility", None),
            is_owner=is_owner,
            is_grantee=is_grantee,
        )

    @staticmethod
    def info(token: str, user=None) -> dict:
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, user, need_data=False)
        payload = PublicShareResolver._info_payload(link)
        policy = policy_for(link)
        if link.audience == AUDIENCE_WORLD:
            payload = policy.redact(payload)
        return PublicShareAdapter.validate_python(payload).model_dump(mode="json", exclude_none=True)

    @staticmethod
    def preview_context(token: str, user=None) -> tuple[dict, object]:
        """Resolve info and extent with one ShareLink lookup."""
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, user, need_data=True)
        payload = PublicShareResolver._info_payload(link)
        policy = policy_for(link)
        if link.audience == AUDIENCE_WORLD:
            payload = policy.redact(payload)
        info = PublicShareAdapter.validate_python(payload).model_dump(mode="json", exclude_none=True)
        return info, PublicShareResolver._extent_for_link(link)

    @staticmethod
    def extent(token: str, user=None):
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, user, need_data=True)
        return PublicShareResolver._extent_for_link(link)

    @staticmethod
    def _extent_for_link(link: ShareLink):
        if link.subject_kind == KIND_TAG:
            try:
                query = TagQuery.parse([link.subject_ref], match_mode='OR', prefix=False, scope=None)
            except TagQueryError:
                return None
            return (
                FeatureStore.objects.owned_by(link.owner).main_map()
                .filter(query.to_django_q(link.owner_id))
                .with_geometry()
                .aggregate(extent=Extent("geometry"))
                .get("extent")
            )
        if link.subject_kind == KIND_COLLECTION:
            collection = Collection.objects.filter(id=link.subject_ref, user=link.owner).first()
            if collection is None:
                return None
            resolved = CollectionMembership.resolve(collection)
            if not resolved.exists():
                return None
            return resolved.aggregate(extent=Extent("geometry")).get("extent")
        if link.subject_kind == KIND_FEATURE:
            feature = FeatureStore.objects.filter(id=link.subject_ref, user=link.owner).first()
            if feature is None or feature.geometry is None:
                return None
            return feature.geometry.extent
        return None

    @staticmethod
    def features(request, token: str) -> JsonResponse:
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, request.user, need_data=True)
        if link.subject_kind not in MAP_KINDS:
            raise InvalidShareLink()
        include_tags = policy_for(link).include_user_tags(link)
        if link.subject_kind == KIND_FEATURE:
            feature = FeatureStore.objects.filter(id=link.subject_ref, user=link.owner).first()
            if feature is None:
                raise InvalidShareLink()
            PublicShareResolver.count_access(link, ACTION_FEATURES)
            feature_geojson = geojson_feature_from_instance(
                feature,
                public_safe=True,
                include_tags=include_tags,
            )
            return JsonResponse({
                "type": "FeatureCollection",
                "features": [feature_geojson] if feature_geojson else [],
            })

        validation_result = _validate_bbox_params(request)
        if isinstance(validation_result, JsonResponse):
            return validation_result
        bbox, zoom_level = validation_result
        query_kwargs = {"tags": [link.subject_ref]} if link.subject_kind == KIND_TAG else {
            "collection_id": link.subject_ref,
        }
        extra_fields = {}
        if link.subject_kind == KIND_COLLECTION:
            collection = Collection.objects.filter(id=link.subject_ref).first()
            if collection is None:
                raise InvalidShareLink()
            extra_fields["collection_name"] = collection.name
        query_result = get_features_in_bbox(
            bbox,
            link.owner_id,
            public_safe=True,
            include_tags=include_tags,
            **query_kwargs,
        )
        PublicShareResolver.count_access(link, ACTION_FEATURES)
        response_data = _build_bbox_response(
            query_result.features,
            query_result.total_count,
            zoom_level,
            query_result.fallback_used,
            **extra_fields,
        )
        return create_bbox_response(response_data, request)

    @staticmethod
    def elevations(token: str, feature_ref: str | int | None, user=None) -> dict:
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, user, need_data=True)
        if link.subject_kind not in MAP_KINDS:
            raise InvalidShareLink()
        feature = PublicShareResolver._feature_in_share(link, feature_ref)
        coordinates = _extract_coordinates_with_elevation_from_geojson(feature.geojson)
        if not coordinates:
            raise ShareError("Feature does not contain LineString or MultiLineString geometry", 400)
        coordinates_with_elevations = []
        for lon, lat, elevation in coordinates:
            if elevation is not None and elevation != 0.0:
                coordinates_with_elevations.append([lon, lat, elevation])
            else:
                coordinates_with_elevations.append([lon, lat])
        return {"coordinates": coordinates_with_elevations}

    @staticmethod
    def track(token: str, user=None) -> dict:
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, user, need_data=True)
        if link.subject_kind not in TRACKER_KINDS:
            raise InvalidShareLink()
        for_world = link.audience == AUDIENCE_WORLD
        if link.subject_kind == KIND_LIVE_TRACK:
            track = PublicShareResolver._tracker_subject(link)
            PublicShareResolver.count_access(link, ACTION_TRACK)
            payload = track_to_response(
                track,
                include_secret=False,
                is_owner=False,
                all_data=False,
                for_world_share=for_world,
            )
            return policy_for(link).redact(payload) if for_world else payload

        group = PublicShareResolver._tracker_subject(link)
        if for_world:
            tracks = world_visible_group_tracks(group)
        else:
            is_owner = user is not None and group.user_id == user.id
            is_accepted = is_owner or LiveTrackGroupSubscription.objects.filter(
                user=user, group=group
            ).exists()
            visible_ids = visible_group_track_ids_for_user(
                group, user, is_owner=is_owner, is_accepted=is_accepted
            )
            tracks = list(LiveTrack.objects.filter(id__in=visible_ids).select_related("user").order_by("name"))
        track_payloads = [
            track_to_response(
                track,
                include_secret=False,
                is_owner=False,
                all_data=False,
                for_world_share=for_world,
            )
            for track in tracks
        ]
        PublicShareResolver.count_access(link, ACTION_TRACK)
        payload = {
            "share_type": KIND_LIVE_TRACK_GROUP,
            "group_name": group.name,
            "tracks": track_payloads,
        }
        return policy_for(link).redact(payload) if for_world else payload

    @staticmethod
    def assert_download_allowed(token: str, user=None) -> ShareLink:
        link = PublicShareResolver.get_active_link(token)
        PublicShareResolver.authorize(link, user, need_data=True)
        if link.subject_kind not in MAP_KINDS:
            raise InvalidShareLink()
        if not policy_for(link).download_allowed(link):
            raise ShareForbidden("Access denied")
        return link

    @staticmethod
    def count_access(link: ShareLink, action: str) -> None:
        if not policy_for(link).counts_access(action):
            return
        ShareLink.objects.filter(pk=link.pk).update(
            access_count=F("access_count") + 1,
            last_accessed_at=timezone.now(),
        )

    @staticmethod
    def _info_payload(link: ShareLink) -> dict:
        created_at = link.created_at.isoformat()
        if link.subject_kind == KIND_TAG:
            return {
                "share_type": KIND_TAG,
                "tag": link.subject_ref,
                "created_at": created_at,
                "include_tags": link.include_tags,
                "allow_downloads": link.allow_downloads,
            }
        if link.subject_kind == KIND_COLLECTION:
            collection = Collection.objects.filter(id=link.subject_ref).first()
            return {
                "share_type": KIND_COLLECTION,
                "collection_name": collection.name if collection is not None else "",
                "collection_id": str(link.subject_ref),
                "created_at": created_at,
                "include_tags": link.include_tags,
                "allow_downloads": link.allow_downloads,
            }
        if link.subject_kind == KIND_FEATURE:
            feature = FeatureStore.objects.filter(id=link.subject_ref).first()
            name = "Unnamed Feature"
            if feature is not None:
                name = (feature.geojson or {}).get("properties", {}).get("name") or name
            return {
                "share_type": KIND_FEATURE,
                "feature_name": name,
                "created_at": created_at,
                "include_tags": link.include_tags,
                "allow_downloads": link.allow_downloads,
            }
        subject = PublicShareResolver._tracker_subject(link)
        share_access = "world" if link.audience == AUDIENCE_WORLD else "internal"
        if link.subject_kind == KIND_LIVE_TRACK:
            return {
                "share_type": KIND_LIVE_TRACK,
                "share_access": share_access,
                "track_id": str(subject.id),
                "track_name": subject.name,
                "created_at": created_at,
            }
        return {
            "share_type": KIND_LIVE_TRACK_GROUP,
            "share_access": share_access,
            "group_id": str(subject.id),
            "group_name": subject.name,
            "created_at": created_at,
        }

    @staticmethod
    def _tracker_subject(link: ShareLink):
        if link.subject_kind == KIND_LIVE_TRACK:
            subject = LiveTrack.objects.filter(id=link.subject_ref).select_related("user").first()
        else:
            subject = LiveTrackGroup.objects.filter(id=link.subject_ref).select_related("user").first()
        if subject is None:
            raise InvalidShareLink()
        return subject

    @staticmethod
    def _feature_in_share(link: ShareLink, feature_ref: str | int | None) -> FeatureStore:
        if link.subject_kind == KIND_FEATURE:
            target_id = int(link.subject_ref)
            if feature_ref not in (None, ""):
                try:
                    requested = int(feature_ref)
                except (TypeError, ValueError) as exc:
                    raise InvalidShareLink() from exc
                if requested != target_id:
                    raise InvalidShareLink()
        else:
            if feature_ref in (None, ""):
                raise ShareError("feature_ref is required", 400)
            try:
                target_id = int(feature_ref)
            except (TypeError, ValueError) as exc:
                raise ShareError("feature_ref must be a positive integer", 400) from exc
        feature = FeatureStore.objects.filter(id=target_id, user=link.owner).first()
        if feature is None:
            raise InvalidShareLink()
        if link.subject_kind == KIND_TAG:
            properties = (feature.geojson or {}).get("properties") or {}
            tag_set = TagSet.from_properties(properties)
            try:
                query = TagQuery.parse([link.subject_ref], match_mode='OR', prefix=False, scope=None)
            except TagQueryError as exc:
                raise InvalidShareLink() from exc
            if not query.matches(tag_set):
                raise InvalidShareLink()
        if link.subject_kind == KIND_COLLECTION:
            collection = Collection.objects.filter(id=link.subject_ref, user=link.owner).first()
            if collection is None:
                raise InvalidShareLink()
            if not CollectionMembership.resolve(collection).filter(id=feature.id).exists():
                raise InvalidShareLink()
        return feature

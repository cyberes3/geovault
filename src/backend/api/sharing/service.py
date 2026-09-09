from django.db import IntegrityError
from django.http import Http404

from api.models import Collection, FeatureStore
from api.services.feature_service import FeatureService
from api.sharing.models import ShareLink
from api.sharing.serialize import serialize_owner_links
from api.utils.authorization import get_object_or_404_for_user
from extensions.live_track.src.backend.models import LiveTrack, LiveTrackGroup
from geo_lib.contracts.pagination import PageQuery
from geo_lib.sharing.constants import (
    AUDIENCE_AUTHENTICATED,
    AUDIENCE_WORLD,
    CAP_ALLOW_DOWNLOADS,
    CAP_INCLUDE_TAGS,
    KIND_COLLECTION,
    KIND_FEATURE,
    KIND_LIVE_TRACK,
    KIND_LIVE_TRACK_GROUP,
    KIND_TAG,
    MAP_KINDS,
    SHARE_KINDS,
    TRACKER_KINDS,
)
from geo_lib.sharing.errors import ShareConflict, ShareError, ShareNotFound
from geo_lib.sharing.share_id import ShareId
from geo_lib.sharing.subject_ref import canonicalize_subject_ref, domain_for_kind
from geo_lib.tags.tag_query import TagQuery, TagQueryError
from website.map_share_social.preview_service import invalidate_previews_for_share
from website.map_share_social.preview_warmup import trigger_social_preview_warmup_async
from website.settings_utils import get_required_setting


def _token_exists(token: str) -> bool:
    return ShareLink.objects.filter(token=token).exists()


def default_capabilities(allow_downloads: bool = False, include_tags: bool = False) -> dict:
    return {
        CAP_ALLOW_DOWNLOADS: bool(allow_downloads),
        CAP_INCLUDE_TAGS: bool(include_tags),
    }


class ShareService:
    @staticmethod
    def generate_token() -> str:
        return ShareId.generate_unique(_token_exists).value

    @staticmethod
    def get_owned(user, token: str) -> ShareLink:
        parsed = ShareId.parse(token)
        if parsed is None:
            raise Http404("Share not found")
        link = ShareLink.objects.filter(token=parsed.value, owner=user).first()
        if link is None:
            raise Http404("Share not found")
        return link

    @staticmethod
    def create(user, payload: dict) -> ShareLink:
        share_type = payload.get("share_type")
        if share_type not in SHARE_KINDS:
            raise ShareError(f"Invalid share_type: {share_type}")
        allow_downloads = bool(payload.get("allow_downloads", False))
        include_tags = bool(payload.get("include_tags", False))
        capabilities = default_capabilities(allow_downloads, include_tags)

        if share_type == KIND_TAG:
            return ShareService._create_tag(user, payload.get("tag"), capabilities)
        if share_type == KIND_COLLECTION:
            return ShareService._create_collection(user, payload.get("collection_id"), capabilities)
        if share_type == KIND_FEATURE:
            return ShareService._create_feature(user, payload.get("feature_id"), capabilities)
        if share_type == KIND_LIVE_TRACK:
            return ShareService.ensure_tracker_link(
                user,
                KIND_LIVE_TRACK,
                payload.get("track_id"),
                payload.get("audience"),
                capabilities=capabilities,
            )
        return ShareService.ensure_tracker_link(
            user,
            KIND_LIVE_TRACK_GROUP,
            payload.get("group_id"),
            payload.get("audience"),
            capabilities=capabilities,
        )

    @staticmethod
    def list_owned(user, page_query: PageQuery, filters: dict) -> tuple[list[dict], int]:
        queryset = ShareLink.objects.owned_by(user).active().order_by("-created_at")
        share_type = filters.get("type") or filters.get("share_type")
        if share_type:
            if share_type not in SHARE_KINDS:
                raise ShareError("Invalid type filter")
            queryset = queryset.for_kind(share_type)
        if filters.get("tag"):
            queryset = queryset.for_subject(KIND_TAG, filters["tag"])
        if filters.get("collection_id"):
            queryset = queryset.for_subject(KIND_COLLECTION, filters["collection_id"])
        if filters.get("feature_id") not in (None, ""):
            queryset = queryset.for_feature(int(filters["feature_id"]))
        if filters.get("audience"):
            if filters["audience"] not in {AUDIENCE_WORLD, AUDIENCE_AUTHENTICATED}:
                raise ShareError("Invalid audience filter")
            queryset = queryset.filter(audience=filters["audience"])

        total_items = queryset.count()
        start = (page_query.page - 1) * page_query.page_size
        links = list(queryset[start:start + page_query.page_size])
        return serialize_owner_links(links), total_items

    @staticmethod
    def update(user, token: str, fields: dict) -> ShareLink:
        link = ShareService.get_owned(user, token)
        capabilities = dict(link.capabilities or {})
        if "allow_downloads" in fields and fields["allow_downloads"] is not None:
            capabilities[CAP_ALLOW_DOWNLOADS] = bool(fields["allow_downloads"])
        if "include_tags" in fields and fields["include_tags"] is not None:
            capabilities[CAP_INCLUDE_TAGS] = bool(fields["include_tags"])
        link.capabilities = capabilities
        link.save(update_fields=["capabilities"])
        invalidate_previews_for_share(link)
        if link.subject_kind in MAP_KINDS:
            trigger_social_preview_warmup_async(link.token)
        return link

    @staticmethod
    def delete(user, token: str) -> None:
        link = ShareService.get_owned(user, token)
        invalidate_previews_for_share(link)
        link.delete()

    @staticmethod
    def ensure_tracker_link(
        owner,
        subject_kind: str,
        subject_ref,
        audience: str,
        capabilities: dict | None = None,
    ) -> ShareLink:
        if subject_kind not in TRACKER_KINDS:
            raise ShareError(f"Invalid tracker subject_kind: {subject_kind}")
        if audience not in {AUDIENCE_WORLD, AUDIENCE_AUTHENTICATED}:
            raise ShareError("audience must be world or authenticated")
        ref = canonicalize_subject_ref(subject_kind, subject_ref)
        ShareService._assert_tracker_subject_owned(owner, subject_kind, ref)
        defaults = {
            "owner": owner,
            "domain": domain_for_kind(subject_kind),
            "capabilities": capabilities or default_capabilities(),
            "token": ShareService.generate_token(),
        }
        try:
            link, created = ShareLink.objects.get_or_create(
                subject_kind=subject_kind,
                subject_ref=ref,
                audience=audience,
                defaults=defaults,
            )
        except IntegrityError as exc:
            raise ShareConflict("Share already exists") from exc
        if not created and capabilities is not None:
            merged = dict(link.capabilities or {})
            merged.update(capabilities)
            link.capabilities = merged
            if link.owner_id != owner.id:
                link.owner = owner
                link.save(update_fields=["capabilities", "owner"])
            else:
                link.save(update_fields=["capabilities"])
        elif not created and link.owner_id != owner.id:
            link.owner = owner
            link.save(update_fields=["owner"])
        return link

    @staticmethod
    def delete_tracker_links(subject_kind: str, subject_ref, audience: str | None = None) -> None:
        queryset = ShareLink.objects.for_subject(subject_kind, subject_ref)
        if audience is not None:
            queryset = queryset.filter(audience=audience)
        queryset.delete()

    @staticmethod
    def tracker_link(subject_kind: str, subject_ref, audience: str) -> ShareLink | None:
        return ShareLink.objects.active().for_subject(subject_kind, subject_ref).filter(
            audience=audience
        ).first()

    @staticmethod
    def _create_tag(user, tag: str | None, capabilities: dict) -> ShareLink:
        if not tag or not str(tag).strip():
            raise ShareError("tag is required when share_type is \"tag\"")
        tag = str(tag).strip()
        tag_max_length = get_required_setting("TAG_MAX_LENGTH")
        if len(tag) > tag_max_length:
            raise ShareError(f"Tag name exceeds maximum length of {tag_max_length} characters")
        try:
            query = TagQuery.parse([tag], match_mode="OR", prefix=False, scope=None)
        except TagQueryError as exc:
            raise ShareError(exc.message) from exc
        tag_exists = FeatureStore.objects.owned_by(user).main_map().filter(
            query.to_django_q(user.id)
        ).exists()
        if not tag_exists:
            raise ShareNotFound("Tag not found in your data")
        link = ShareLink.objects.create(
            token=ShareService.generate_token(),
            owner=user,
            domain=domain_for_kind(KIND_TAG),
            subject_kind=KIND_TAG,
            subject_ref=tag,
            audience=AUDIENCE_WORLD,
            capabilities=capabilities,
        )
        trigger_social_preview_warmup_async(link.token)
        return link

    @staticmethod
    def _create_collection(user, collection_id, capabilities: dict) -> ShareLink:
        if collection_id is None:
            raise ShareError("collection_id is required when share_type is \"collection\"")
        collection = get_object_or_404_for_user(Collection, user, id=collection_id)
        link = ShareLink.objects.create(
            token=ShareService.generate_token(),
            owner=user,
            domain=domain_for_kind(KIND_COLLECTION),
            subject_kind=KIND_COLLECTION,
            subject_ref=str(collection.id),
            audience=AUDIENCE_WORLD,
            capabilities=capabilities,
        )
        trigger_social_preview_warmup_async(link.token)
        return link

    @staticmethod
    def _create_feature(user, feature_id, capabilities: dict) -> ShareLink:
        if feature_id is None:
            raise ShareError("feature_id is required when share_type is \"feature\"")
        feature = FeatureService.get_owned_feature_or_404(user, int(feature_id))
        existing = ShareLink.objects.owned_by(user).for_feature(feature.id).first()
        if existing is not None:
            existing.capabilities = capabilities
            existing.save(update_fields=["capabilities"])
            return existing
        try:
            link = ShareLink.objects.create(
                token=ShareService.generate_token(),
                owner=user,
                domain=domain_for_kind(KIND_FEATURE),
                subject_kind=KIND_FEATURE,
                subject_ref=feature.id,
                audience=AUDIENCE_WORLD,
                capabilities=capabilities,
            )
        except IntegrityError as exc:
            raise ShareConflict("Share already exists") from exc
        trigger_social_preview_warmup_async(link.token)
        return link

    @staticmethod
    def _assert_tracker_subject_owned(owner, subject_kind: str, subject_ref) -> None:
        if subject_kind == KIND_LIVE_TRACK:
            track = LiveTrack.objects.filter(id=subject_ref, user=owner).first()
            if track is None:
                raise ShareNotFound("Track not found")
            return
        group = LiveTrackGroup.objects.filter(id=subject_ref, user=owner).first()
        if group is None:
            raise ShareNotFound("Group not found")

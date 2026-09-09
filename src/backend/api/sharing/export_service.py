from typing import Optional, Union
from uuid import UUID

from django.http import HttpResponse, JsonResponse
from django.utils.text import slugify

from api.models import Collection, FeatureStore
from api.services.feature_serialization import geojson_feature_from_instance
from api.services.feature_service import FeatureService
from api.sharing.models import ShareLink
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response
from api.views.features.bbox.query_builder import _build_base_query, _build_collection_query
from api.views.features.export.kmz_builder import build_kmz_response
from geo_lib.export.geojson_preprocessor import prepare_geojson_for_kmz
from geo_lib.export.geojson_to_kmz import geojson_to_kmz_bytes
from geo_lib.export.share_export import prepare_kmz_options_for_share
from geo_lib.export.single_feature_export import prepare_kmz_options_for_feature
from geo_lib.perf.query_budget import QueryBudget
from geo_lib.sharing.constants import KIND_COLLECTION, KIND_FEATURE, KIND_TAG
from geo_lib.tags.tag_index import TagIndex
from website.settings_utils import get_required_setting


class ShareExportService:
    """KMZ export from an already-resolved ShareLink, or from an authenticated owner query."""

    @staticmethod
    def export_share_kmz(share: ShareLink, feature_id: Optional[int] = None) -> Union[HttpResponse, JsonResponse]:
        if feature_id is not None:
            return ShareExportService._export_share_feature(share, feature_id)
        if share.subject_kind == KIND_FEATURE:
            return ShareExportService._export_share_feature(share, int(share.subject_ref))
        return ShareExportService._export_share_bulk(share)

    @staticmethod
    def export_owner_kmz(
        request,
        *,
        feature_id: Optional[int] = None,
        tag_name: Optional[str] = None,
        collection_id_str: Optional[str] = None,
        export_all: Optional[str] = None,
    ) -> Union[HttpResponse, JsonResponse]:
        if not request.user.is_authenticated:
            return error_response("Unauthorized", code=401)
        if feature_id is not None:
            feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)
            return ShareExportService._kmz_from_features(
                [feature],
                name=_feature_name(feature, fallback=f"feature-{feature.id}"),
                public_safe=False,
                include_tags=True,
                single=True,
            )
        if export_all == "true":
            features = _build_base_query(request.user.id)
            return ShareExportService._kmz_from_queryset(features, "All Features", public_safe=False, include_tags=True)
        if tag_name:
            if not TagIndex.exists(request.user.id, tag_name, scope=None):
                return error_response("Tag not found", code=404)
            features = _build_base_query(request.user.id, tag=tag_name)
            return ShareExportService._kmz_from_queryset(features, tag_name, public_safe=False, include_tags=True)
        if collection_id_str:
            try:
                collection_id = UUID(collection_id_str)
            except ValueError:
                return error_response("Invalid collection ID", code=400)
            collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
            features = _build_collection_query(request.user.id, collection_id)
            return ShareExportService._kmz_from_queryset(
                features, collection.name, public_safe=False, include_tags=True
            )
        return error_response("Invalid parameters", code=400)

    @staticmethod
    def queryset_for_share(share: ShareLink):
        if share.subject_kind == KIND_TAG:
            return _build_base_query(share.owner_id, tag=share.subject_ref)
        if share.subject_kind == KIND_COLLECTION:
            return _build_collection_query(share.owner_id, share.subject_ref)
        return FeatureStore.objects.filter(id=share.subject_ref, user=share.owner)

    @staticmethod
    def _export_share_bulk(share: ShareLink) -> Union[HttpResponse, JsonResponse]:
        if share.subject_kind == KIND_TAG:
            share_name = str(share.subject_ref)
        else:
            collection = Collection.objects.filter(id=share.subject_ref).first()
            share_name = collection.name if collection is not None else "share"
        return ShareExportService._kmz_from_queryset(
            ShareExportService.queryset_for_share(share),
            share_name,
            public_safe=True,
            include_tags=share.include_tags,
            guest=True,
        )

    @staticmethod
    def _export_share_feature(share: ShareLink, feature_id: int) -> Union[HttpResponse, JsonResponse]:
        feature = ShareExportService.queryset_for_share(share).filter(id=feature_id).first()
        if feature is None:
            if FeatureStore.objects.filter(id=feature_id, user=share.owner).exists():
                return error_response("Access denied", code=403)
            return error_response("Invalid request", code=404)
        return ShareExportService._kmz_from_features(
            [feature],
            name=_feature_name(feature, fallback=f"feature-{feature.id}"),
            public_safe=True,
            include_tags=share.include_tags,
            single=True,
        )

    @staticmethod
    def _kmz_from_queryset(features, name: str, *, public_safe: bool, include_tags: bool, guest: bool = False):
        budget = QueryBudget(get_required_setting("MAX_FEATURES_PER_REQUEST")).clamp(None)
        capped = list(features[:budget])
        return ShareExportService._kmz_from_features(
            capped,
            name=name,
            public_safe=public_safe,
            include_tags=include_tags,
            guest=guest,
            single=False,
        )

    @staticmethod
    def _kmz_from_features(
        features,
        *,
        name: str,
        public_safe: bool,
        include_tags: bool,
        guest: bool = False,
        single: bool,
    ) -> Union[HttpResponse, JsonResponse]:
        geojson_features = []
        base_dir = str(get_required_setting("BASE_DIR"))
        icon_storage_dir = get_required_setting("ICON_STORAGE_DIR")
        for feature in features:
            built = geojson_feature_from_instance(
                feature,
                public_safe=public_safe,
                include_tags=include_tags,
            )
            if built is None:
                continue
            geojson_features.append(prepare_geojson_for_kmz(built, base_dir, icon_storage_dir))
        if not geojson_features:
            if guest:
                return error_response("Invalid request", code=404)
            return error_response("No features found", code=404)
        feature_collection = {"type": "FeatureCollection", "features": geojson_features}
        options = (
            prepare_kmz_options_for_feature(name, base_dir)
            if single
            else prepare_kmz_options_for_share(name, base_dir)
        )
        kmz_bytes = geojson_to_kmz_bytes(
            geojson_features[0] if single else feature_collection,
            options=options,
        )
        slug = slugify(name) or ("feature" if single else "export")
        filename = f"{slug}.kmz" if single or not guest else f"{slug}-share.kmz"
        return build_kmz_response(kmz_bytes, filename)


def _feature_name(feature: FeatureStore, fallback: str) -> str:
    return (feature.geojson or {}).get("properties", {}).get("name") or fallback
